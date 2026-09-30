package com.v2ray.ang.handler

import android.content.Context
import android.util.Base64
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * FILTERNET: the automatic server pool.
 *
 * The pool file on GitHub may hold *thousands* of share links. Importing all of
 * them would bloat storage and make the app crawl, so nothing is written to the
 * profile database until a server has actually proven itself:
 *
 *   1. download the list (mirrors + on-disk cache)
 *   2. pull host:port straight out of each link, no parsing into profiles
 *   3. measure a wave of [WAVE_SIZE] links at a time, [PARALLEL] at once
 *   4. the first wave that yields enough servers under [MAX_PING_MS] wins
 *   5. only those winners are imported and one of them is selected
 *
 * There is deliberately **no cap** on how many links may be examined - if a
 * wave produces nothing the next wave is tried, until the list runs out.
 */
object ServerPoolManager {

    /** Fixed group that holds whatever the app picked for the user. */
    const val POOL_SUB_ID = "filternetgiftpool00000000000001"
    private const val POOL_REMARKS = "FILTERNET"

    /** A server slower than this is unusable. */
    const val MAX_PING_MS = 1000L

    /** How many links are measured per wave - keeps the first connect fast. */
    const val WAVE_SIZE = 100

    /** Sockets opened at the same time. */
    private const val PARALLEL = 24

    private const val CONNECT_TIMEOUT_MS = 1200

    /** How many winners to keep so the next connect is instant. */
    private const val KEEP = 16

    /** Cache lifetime before the list is refreshed in the background. */
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000

    private const val CACHE_FILE = "filternet_pool.txt"
    private const val CACHE_STAMP = "filternet_pool_stamp"

    sealed interface Phase {
        data object Idle : Phase
        data object Downloading : Phase
        data class Scanning(val checked: Int, val found: Int, val wave: Int) : Phase
        /** Tunnel is up for candidate [index] of [total]; proving it passes traffic. */
        data class Verifying(val index: Int, val total: Int) : Phase
        data class Ready(val found: Int) : Phase
        data class Failed(val reason: Reason) : Phase
    }

    enum class Reason { NO_INTERNET, EMPTY_LIST, NONE_WORKING, NONE_PASSED }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val running = AtomicBoolean(false)

    fun reset() {
        if (!running.get()) _phase.value = Phase.Idle
    }

    /* ─────────────────────────── public API ─────────────────────────── */

    /** Guids the app currently holds from the pool. */
    fun poolGuids(): Set<String> =
        runCatching { MmkvManager.decodeServerList(POOL_SUB_ID).toSet() }.getOrDefault(emptySet())

    fun isPoolServer(guid: String): Boolean = poolGuids().contains(guid)

    fun hasUsableServers(): Boolean =
        runCatching { MmkvManager.decodeAllServerList().isNotEmpty() }.getOrDefault(false)

    /**
     * Refreshes the cached list in the background when it is missing or stale.
     * Safe to call on every app start - it returns straight away when fresh.
     */
    suspend fun refreshIfStale(context: Context) = withContext(Dispatchers.IO) {
        val stamp = MmkvManager.decodeSettingsString(CACHE_STAMP)?.toLongOrNull() ?: 0L
        val cache = File(context.filesDir, CACHE_FILE)
        if (cache.exists() && System.currentTimeMillis() - stamp < CACHE_TTL_MS) return@withContext
        val body = download() ?: return@withContext
        runCatching {
            cache.writeText(body)
            MmkvManager.encodeSettings(CACHE_STAMP, System.currentTimeMillis().toString())
        }
    }

    /* ─────────────────────────── internals ─────────────────────────── */

    private fun cachedOrDownload(context: Context): String? {
        val cache = File(context.filesDir, CACHE_FILE)
        val stamp = MmkvManager.decodeSettingsString(CACHE_STAMP)?.toLongOrNull() ?: 0L
        if (cache.exists() && System.currentTimeMillis() - stamp < CACHE_TTL_MS) {
            val text = runCatching { cache.readText() }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        val fresh = download()
        if (!fresh.isNullOrBlank()) {
            runCatching {
                cache.writeText(fresh)
                MmkvManager.encodeSettings(CACHE_STAMP, System.currentTimeMillis().toString())
            }
            return fresh
        }
        // network is down but an old copy is better than nothing
        return runCatching { if (cache.exists()) cache.readText() else null }.getOrNull()
    }

    private fun download(): String? {
        for (url in AppConfig.FN_GIFT_SERVER_URLS) {
            val body = runCatching {
                HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 20000))
            }.getOrNull()
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    /** A single share link plus the endpoint we can measure without importing. */
    data class PoolEntry(val raw: String, val host: String, val port: Int)

    /**
     * Splits the pool file into measurable entries. The whole file may also be
     * base64 encoded, which is what most subscription providers hand out.
     */
    fun parse(body: String): MutableList<PoolEntry> {
        var text = body
        if (!text.contains("://")) {
            text = runCatching {
                String(Base64.decode(text.trim(), Base64.DEFAULT or Base64.NO_WRAP))
            }.getOrDefault(text)
        }
        val out = ArrayList<PoolEntry>()
        val seen = HashSet<String>()
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("//")) continue
            if (!l.contains("://")) continue
            val ep = endpointOf(l) ?: continue
            // FILTERNET: de-duplicate on the WHOLE link, never on host:port.
            // CDN based configs (Cloudflare and friends) all share a handful of
            // IPs on 443 while differing in SNI, host header and path - keying on
            // the endpoint silently threw most of the pool away.
            if (!seen.add(l)) continue
            out.add(PoolEntry(l, ep.first, ep.second))
        }
        return out
    }

    private val HOST_PORT = Regex("""@\[?([^\[\]/?#@]+?)]?:(\d{1,5})""")

    /**
     * Pulls host and port out of a share link textually. Far cheaper than
     * building a full profile, which matters when the list holds thousands.
     */
    private fun endpointOf(link: String): Pair<String, Int>? = runCatching {
        if (link.startsWith("vmess://", ignoreCase = true)) {
            val payload = link.substringAfter("://").substringBefore("#")
            val json = String(Base64.decode(payload, Base64.DEFAULT or Base64.NO_WRAP))
            val o = JSONObject(json)
            val host = o.optString("add").ifBlank { return@runCatching null }
            val port = o.optString("port").toIntOrNull() ?: return@runCatching null
            return@runCatching host to port
        }
        HOST_PORT.find(link)?.let { m ->
            val host = m.groupValues[1]
            val port = m.groupValues[2].toIntOrNull() ?: return@runCatching null
            if (host.isBlank() || port !in 1..65535) return@runCatching null
            return@runCatching host to port
        }
        // ss:// links sometimes hide the endpoint inside the base64 blob
        if (link.startsWith("ss://", ignoreCase = true)) {
            val payload = link.substringAfter("://").substringBefore("#").substringBefore("?")
            val decoded = runCatching {
                String(Base64.decode(payload, Base64.DEFAULT or Base64.NO_WRAP))
            }.getOrNull() ?: return@runCatching null
            HOST_PORT.find("@$decoded")?.let { m ->
                return@runCatching m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: return@runCatching null)
            }
        }
        val uri = runCatching { URI(link) }.getOrNull() ?: return@runCatching null
        val host = uri.host ?: return@runCatching null
        val port = uri.port.takeIf { it > 0 } ?: return@runCatching null
        host to port
    }.getOrNull()

    /** Measures one wave and returns the fast ones, sorted best first. */
    private suspend fun probeWave(
        wave: List<PoolEntry>,
        checked: AtomicInteger,
        waveIndex: Int,
    ): List<Pair<PoolEntry, Long>> {
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<PoolEntry, Long>>())
        val gate = Semaphore(PARALLEL)
        coroutineScope {
            for (entry in wave) {
                launch {
                    gate.withPermit {
                        // enough winners already - stop burning battery
                        if (results.size >= KEEP) return@withPermit
                        val delay = runCatching {
                            SpeedtestManager.socketConnectTime(entry.host, entry.port, CONNECT_TIMEOUT_MS)
                        }.getOrDefault(-1L)
                        if (delay in 1..MAX_PING_MS) results.add(entry to delay)
                        _phase.value = Phase.Scanning(
                            checked = checked.incrementAndGet(),
                            found = results.size,
                            wave = waveIndex,
                        )
                    }
                }
            }
        }
        return results.sortedBy { it.second }
    }

    private fun ensureGroupExists() {
        val existing = runCatching { MmkvManager.decodeSubscriptions() }.getOrDefault(emptyList())
        if (existing.any { it.guid == POOL_SUB_ID }) return
        MmkvManager.encodeSubscription(
            POOL_SUB_ID,
            SubscriptionItem(remarks = POOL_REMARKS, url = "", enabled = false, autoUpdate = false),
        )
    }

    /* ══════════════════ candidates & real verification ══════════════════ */

    /**
     * FILTERNET: a TCP handshake proves almost nothing for this kind of pool.
     *
     * Most donated configs sit behind a CDN, so port 443 on those IPs answers
     * instantly *whatever* you send it - every server looks perfect and the app
     * happily "connected" to dead configs. That is why the button felt fake.
     *
     * So the flow is now two stages: this returns an ordered short-list, and the
     * caller brings the tunnel up on each one and proves it with real traffic
     * via [verifyThroughTunnel] before declaring success.
     *
     * @return guids to try, best first. Empty when nothing could be prepared.
     */
    suspend fun findCandidates(context: Context): List<String> = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext emptyList()
        try {
            _phase.value = Phase.Downloading
            val raw = cachedOrDownload(context)
            if (raw.isNullOrBlank()) {
                _phase.value = Phase.Failed(Reason.NO_INTERNET)
                return@withContext emptyList()
            }

            val entries = parse(raw)
            LogUtil.i(AppConfig.TAG, "ServerPool: ${entries.size} links in the pool")
            if (entries.isEmpty()) {
                _phase.value = Phase.Failed(Reason.EMPTY_LIST)
                return@withContext emptyList()
            }

            val checked = AtomicInteger(0)
            var wave = 0
            var offset = 0
            while (offset < entries.size) {
                wave++
                val slice = entries.subList(offset, minOf(offset + WAVE_SIZE, entries.size))
                offset += WAVE_SIZE
                val winners = probeWave(slice, checked, wave)
                if (winners.isNotEmpty()) {
                    val guids = importAll(winners)
                    if (guids.isNotEmpty()) return@withContext guids
                }
            }
            _phase.value = Phase.Failed(Reason.NONE_WORKING)
            emptyList()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "ServerPool: candidate search failed", e)
            _phase.value = Phase.Failed(Reason.NO_INTERNET)
            emptyList()
        } finally {
            running.set(false)
        }
    }

    fun publishVerifying(index: Int, total: Int) {
        _phase.value = Phase.Verifying(index, total)
    }

    fun publishConnected() {
        _phase.value = Phase.Ready(1)
    }

    fun publishNonePassed() {
        _phase.value = Phase.Failed(Reason.NONE_PASSED)
    }

    /**
     * True when the tunnel that is currently up actually carries traffic.
     *
     * Runs from the UI process, so the request goes through the VpnService like
     * any other app traffic - if this succeeds the user really is online.
     */
    fun verifyThroughTunnel(): Boolean {
        for (url in VERIFY_URLS) {
            val body = runCatching {
                HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = VERIFY_TIMEOUT_MS))
            }.getOrNull()
            if (body != null) return true
        }
        return false
    }

    private val VERIFY_URLS = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://api.ipify.org",
    )
    private const val VERIFY_TIMEOUT_MS = 5000

    /** Remembers a guid that really passed traffic, so it is tried first later. */
    fun markGood(guid: String) {
        runCatching { MmkvManager.encodeSettings(KEY_LAST_GOOD, guid) }
    }

    fun lastGoodGuid(): String? =
        runCatching { MmkvManager.decodeSettingsString(KEY_LAST_GOOD) }.getOrNull()
            ?.takeIf { it.isNotBlank() && MmkvManager.decodeServerConfig(it) != null }

    private const val KEY_LAST_GOOD = "filternet_last_good_guid"

    /** Imports every winner of a wave and returns their guids, fastest first. */
    private fun importAll(winners: List<Pair<PoolEntry, Long>>): List<String> {
        ensureGroupExists()
        MmkvManager.removeServerViaSubid(POOL_SUB_ID)
        val payload = winners.take(KEEP).joinToString("\n") { it.first.raw }
        AngConfigManager.importBatchConfig(payload, POOL_SUB_ID, false)

        val guids = MmkvManager.decodeServerList(POOL_SUB_ID)
        if (guids.isEmpty()) return emptyList()

        val byEndpoint = winners.associate { "${it.first.host}:${it.first.port}" to it.second }
        val scored = guids.mapNotNull { guid ->
            val p = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            val delay = byEndpoint["${p.server}:${p.serverPort}"] ?: 9999L
            MmkvManager.encodeServerTestDelayMillis(guid, delay)
            guid to delay
        }
        // the one that genuinely worked last time gets first refusal
        val last = lastGoodGuid()
        return scored.sortedBy { it.second }.map { it.first }
            .sortedByDescending { it == last }
    }
}
