package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.extension.isNotNullEmpty
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

/**
 * FILTERNET: "I have no server" free-server pool.
 *
 * A plain text file hosted on GitHub holds the donated profiles (one share link
 * per line, or the whole file base64 encoded - both work). When the user asks
 * for servers we download that list, measure every entry and keep the fastest
 * [WANTED] profiles whose delay is below [MAX_PING_MS].
 *
 * Everything lands in one dedicated group ([GIFT_SUB_ID]) so a later request can
 * replace the previous batch without ever touching the user's own profiles.
 */
object GiftServerManager {

    /** Fixed id of the group that holds the donated profiles. */
    const val GIFT_SUB_ID = "filternetgiftpool00000000000001"

    /** Name of that group as shown in the server list. */
    private const val GIFT_REMARKS = "FILTERNET"

    /** How many working profiles the user should end up with. */
    const val WANTED = 10

    /** A profile slower than this is treated as unusable. */
    const val MAX_PING_MS = 2000L

    private const val CONNECT_TIMEOUT_MS = 2000
    private const val PARALLEL_PROBES = 8
    private const val MAX_CANDIDATES = 400

    sealed interface Progress {
        data object Idle : Progress
        data object Downloading : Progress
        data class Scanning(val checked: Int, val total: Int, val found: Int) : Progress
        data class Done(val found: Int) : Progress
        data class Failed(val reason: Reason) : Progress
    }

    enum class Reason { NETWORK, EMPTY_LIST, NONE_WORKING }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    @Volatile
    private var running = false

    fun reset() {
        if (!running) _progress.value = Progress.Idle
    }

    /** True when the user already holds a batch of donated profiles. */
    fun hasGiftServers(): Boolean =
        runCatching { MmkvManager.decodeServerList(GIFT_SUB_ID).isNotEmpty() }.getOrDefault(false)

    /** Guids of the donated profiles, used to hide the per-row menu for them. */
    fun giftGuids(): Set<String> =
        runCatching { MmkvManager.decodeServerList(GIFT_SUB_ID).toSet() }.getOrDefault(emptySet())

    fun isGiftServer(guid: String): Boolean =
        runCatching { MmkvManager.decodeServerList(GIFT_SUB_ID).contains(guid) }.getOrDefault(false)

    /**
     * Downloads the donated list, keeps the [WANTED] fastest working profiles and
     * replaces any previous batch with them.
     *
     * @return the number of profiles the user ended up with.
     */
    suspend fun fetchServers(): Int = withContext(Dispatchers.IO) {
        if (running) return@withContext 0
        running = true
        try {
            _progress.value = Progress.Downloading

            val raw = download()
            if (raw.isNullOrBlank()) {
                _progress.value = Progress.Failed(Reason.NETWORK)
                return@withContext 0
            }

            // Import everything into a scratch pass first: the old batch is only
            // dropped once we know the new list actually produced something.
            ensureGroupExists()
            MmkvManager.removeServerViaSubid(GIFT_SUB_ID)
            val imported = AngConfigManager.importBatchConfig(raw, GIFT_SUB_ID, false)
            val candidates = MmkvManager.decodeServerList(GIFT_SUB_ID).take(MAX_CANDIDATES)
            LogUtil.i(AppConfig.TAG, "GiftServer: imported=${imported.first} candidates=${candidates.size}")

            if (candidates.isEmpty()) {
                _progress.value = Progress.Failed(Reason.EMPTY_LIST)
                return@withContext 0
            }

            val working = probeAll(candidates)
            if (working.isEmpty()) {
                MmkvManager.removeServerViaSubid(GIFT_SUB_ID)
                _progress.value = Progress.Failed(Reason.NONE_WORKING)
                return@withContext 0
            }

            // Keep the fastest ones, throw the rest away.
            val keep = working.sortedBy { it.second }.take(WANTED)
            val keepGuids = keep.map { it.first }.toSet()
            candidates.filterNot { it in keepGuids }.forEach { MmkvManager.removeServer(it) }
            MmkvManager.encodeServerList(keep.map { it.first }.toMutableList(), GIFT_SUB_ID)
            keep.forEach { (guid, delay) -> MmkvManager.encodeServerTestDelayMillis(guid, delay) }

            _progress.value = Progress.Done(keep.size)
            keep.size
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "GiftServer: fetch failed", e)
            _progress.value = Progress.Failed(Reason.NETWORK)
            0
        } finally {
            running = false
        }
    }

    /** Removes the donated batch without touching the user's own profiles. */
    fun clear() {
        runCatching { MmkvManager.removeServerViaSubid(GIFT_SUB_ID) }
        _progress.value = Progress.Idle
    }

    private fun download(): String? {
        for (url in AppConfig.FN_GIFT_SERVER_URLS) {
            val body = HttpUtil.getUrlContent(UrlContentRequest(url = url, timeout = 20000))
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    private fun ensureGroupExists() {
        val existing = runCatching { MmkvManager.decodeSubscriptions() }.getOrDefault(emptyList())
        if (existing.any { it.guid == GIFT_SUB_ID }) return
        MmkvManager.encodeSubscription(
            GIFT_SUB_ID,
            SubscriptionItem(
                remarks = GIFT_REMARKS,
                url = "",
                enabled = false,
                autoUpdate = false,
            )
        )
    }

    /**
     * Measures every candidate, stopping early once enough fast ones are found.
     */
    private suspend fun probeAll(guids: List<String>): List<Pair<String, Long>> {
        val results = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val checked = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = Semaphore(PARALLEL_PROBES)

        coroutineScope {
            for (guid in guids) {
                launch {
                    if (results.size >= WANTED) return@launch
                    gate.withPermit {
                        if (results.size >= WANTED) return@withPermit
                        val delay = probe(guid)
                        if (delay in 1..MAX_PING_MS) results.add(guid to delay)
                        _progress.value = Progress.Scanning(
                            checked = checked.incrementAndGet(),
                            total = guids.size,
                            found = results.size,
                        )
                    }
                }
            }
        }
        return results.toList()
    }

    /** TCP handshake time to the profile's endpoint, or -1 when unreachable. */
    private fun probe(guid: String): Long = try {
        val profile = MmkvManager.decodeServerConfig(guid)
        val host = profile?.server
        val port = profile?.serverPort?.toIntOrNull()
        if (host.isNotNullEmpty() && port != null) {
            SpeedtestManager.socketConnectTime(host.orEmpty(), port, CONNECT_TIMEOUT_MS)
        } else {
            -1L
        }
    } catch (e: Exception) {
        -1L
    }
}
