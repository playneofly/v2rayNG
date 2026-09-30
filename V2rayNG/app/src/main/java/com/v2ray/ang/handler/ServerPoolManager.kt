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
    private const val KEEP = 12

    /** Cache lifetime before the list is refreshed in the background. */
    private const val CACHE_TTL_MS = 6L * 60 * 60 * 1000

    private const val CACHE_FILE = "filternet_pool.txt"
    private const val CACHE_STAMP = "filternet_pool_stamp"

    sealed interface Phase {
        data object Idle : Phase
        data object Downloading : Phase
        data class Scanning(val checked: Int, val found: Int, val wave: Int) : Phase
        data class Ready(val found: Int) : Phase
        data class Failed(val reason: Reason) : Phase
    }

    enum class Reason { NO_INTERNET, EMPTY_LIST, NONE_WORKING }

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

    /**
     * Finds a working server and leaves it selected, ready to connect.
     *
     * @return the guid to connect to, or null when nothing could be found.
     */
    suspend fun findAndSelect(context: Context, forceRescan: Boolean): String? =
        withContext(Dispatchers.IO) {
            if (!running.compareAndSet(false, true)) return@withContext null
            try {
                // ---- fast path: something already proven still answers -------
                if (!forceRescan) {
                    val known = knownGoodGuid()
                    if (known != null) {
                        MmkvManager.setSelectServer(known)
                        _phase.value = Phase.Ready(1)
                        return@withContext known
                    }
                }

                _phase.value = Phase.Downloading
                val raw = cachedOrDownload(context)
                if (raw.isNullOrBlank()) {
                    _phase.value = Phase.Failed(Reason.NO_INTERNET)
                    return@withContext null
                }

                val entries = parse(raw)
                LogUtil.i(AppConfig.TAG, "ServerPool: ${entries.size} links in the pool")
                if (entries.isEmpty()) {
                    _phase.value = Phase.Failed(Reason.EMPTY_LIST)
                    return@withContext null
                }

                // ---- wave scan, no cap on how far we go ----------------------
                val checked = AtomicInteger(0)
                var wave = 0
                var offset = 0
                while (offset < entries.size) {
                    wave++
                    val slice = entries.subList(offset, minOf(offset + WAVE_SIZE, entries.size))
                    offset += WAVE_SIZE
                    val winners = probeWave(slice, checked, wave)
                    if (winners.isNotEmpty()) {
                        val guid = importWinners(winners)
                        if (guid != null) {
                            _phase.value = Phase.Ready(winners.size)
                            return@withContext guid
                        }
                    }
                }

                _phase.value = Phase.Failed(Reason.NONE_WORKING)
                null
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "ServerPool: scan failed", e)
                _phase.value = Phase.Failed(Reason.NO_INTERNET)
                null
            } finally {
                running.set(false)
            }
        }

    /* ─────────────────────────── internals ─────────────────────────── */

    /** A previously imported profile that still answers straight away. */
    private fun knownGoodGuid(): String? {
        val guids = runCatching { MmkvManager.decodeServerList(POOL_SUB_ID) }.getOrNull().orEmpty()
        if (guids.isEmpty()) return null
        // fastest first, so reconnects land on the best known server
        val ordered = guids.sortedBy {
            val d = MmkvManager.decodeServerAffiliationInfo(it)?.testDelayMillis ?: Long.MAX_VALUE
            if (d <= 0L) Long.MAX_VALUE else d
        }
        for (guid in ordered.take(4)) {
            val p = MmkvManager.decodeServerConfig(guid) ?: continue
            val host = p.server ?: continue
            val port = p.serverPort?.toIntOrNull() ?: continue
            val delay = runCatching {
                SpeedtestManager.socketConnectTime(host, port, CONNECT_TIMEOUT_MS)
            }.getOrDefault(-1L)
            if (delay in 1..MAX_PING_MS) {
                MmkvManager.encodeServerTestDelayMillis(guid, delay)
                return guid
            }
        }
        return null
    }

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
            val key = "${ep.first}:${ep.second}"
            if (!seen.add(key)) continue
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

    /**
     * Imports the winners, replacing whatever the previous batch was, and
     * returns the guid of the fastest one.
     */
    private fun importWinners(winners: List<Pair<PoolEntry, Long>>): String? {
        ensureGroupExists()
        MmkvManager.removeServerViaSubid(POOL_SUB_ID)
        val payload = winners.take(KEEP).joinToString("\n") { it.first.raw }
        AngConfigManager.importBatchConfig(payload, POOL_SUB_ID, false)

        val guids = MmkvManager.decodeServerList(POOL_SUB_ID)
        if (guids.isEmpty()) return null

        // carry the measured delay across so the UI has something to show
        val byEndpoint = winners.associate { "${it.first.host}:${it.first.port}" to it.second }
        var best: Pair<String, Long>? = null
        for (guid in guids) {
            val p = MmkvManager.decodeServerConfig(guid) ?: continue
            val delay = byEndpoint["${p.server}:${p.serverPort}"] ?: continue
            MmkvManager.encodeServerTestDelayMillis(guid, delay)
            if (best == null || delay < best!!.second) best = guid to delay
        }
        val chosen = best?.first ?: guids.first()
        MmkvManager.setSelectServer(chosen)
        return chosen
    }

    private fun ensureGroupExists() {
        val existing = runCatching { MmkvManager.decodeSubscriptions() }.getOrDefault(emptyList())
        if (existing.any { it.guid == POOL_SUB_ID }) return
        MmkvManager.encodeSubscription(
            POOL_SUB_ID,
            SubscriptionItem(remarks = POOL_REMARKS, url = "", enabled = false, autoUpdate = false),
        )
    }
}
