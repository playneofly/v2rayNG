package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * FILTERNET: the endless clean-IP hunt.
 *
 * Our configs live on Cloudflare Workers, and Cloudflare is an anycast network:
 * every one of its ~1.5 million edge addresses terminates TLS and then routes
 * by name, not by address. One Worker is therefore reachable through all of
 * them - a single house with a million front doors. Filtering never closes
 * every door, because Iranian sites sit behind the same addresses.
 *
 * The scanner runs continuously and hands survivors to the caller in batches,
 * so measuring one batch never stops the hunt for the next.
 */
object CleanIpScanner {

    const val SCAN_SUB_ID = "filternetscanpool000000000001"
    private const val SCAN_REMARKS = "FILTERNET SCAN"

    private const val PARALLEL = 32
    private const val HANDSHAKE_TIMEOUT_MS = 1500

    /** How many survivors make up one batch handed to the core. */
    private const val BATCH = 12

    /**
     * Once the seeds have all been tried, waiting for a full [BATCH] is just
     * stalling: the seeds are the addresses most likely to work, and holding
     * three good ones hostage until the random sweep coughs up nine more was
     * what made the hunt feel dead for the first half-minute.
     */
    private const val MIN_BATCH = 3

    /** ...and never sit on survivors longer than this, however few there are. */
    private const val DRAIN_AFTER_MS = 2_500L

    /**
     * The UI cannot use sixty updates a second and Compose should not be asked
     * to. Misses are coalesced to this interval; a hit always goes out at once
     * because that is the one event worth seeing immediately.
     */
    private const val EMIT_INTERVAL_MS = 150L

    /** Entries kept for the live list. Unbounded scanning, bounded memory. */
    private const val RECENT_LIMIT = 50

    /**
     * Cloudflare's published IPv4 ranges, used for the random sweep.
     *
     * Hard-coded here as a last resort only; [refreshRanges] replaces them
     * with the list bundled in the APK, which the build refreshes from
     * Cloudflare on every release. Fifteen CIDRs describe all 1,524,736
     * addresses, so there is nothing to gain by shipping them expanded.
     */
    private var CF_RANGES = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )

    private val CF_PORTS = listOf(443, 2053, 2083, 2087, 2096, 8443)

    /** One probed address, for the live list. */
    data class Probe(val address: String, val alive: Boolean, val ms: Long)

    /**
     * Where the hunt has got to.
     *
     * PREPARING exists because everything before the first probe - refreshing
     * the ranges, inflating the pool, asking ircf.space over DoH - is seconds
     * of silence on a slow network. The screen used to show a resting orb for
     * all of it, so tapping connect looked like it had done nothing at all.
     */
    enum class Stage { IDLE, PREPARING, SEEDING, SWEEPING, MEASURING }

    data class Progress(
        val probed: Int = 0,
        val alive: Int = 0,
        val recent: List<Probe> = emptyList(),
        val running: Boolean = false,
        /** Addresses handed in by ircf.space for this operator. */
        val seedIrcf: Int = 0,
        /** Addresses that really worked here before. */
        val seedMemory: Int = 0,
        val stage: Stage = Stage.IDLE,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * The clean address the live tunnel is actually running through, so the
     * private tab can prove it is doing what it claims rather than asking to
     * be believed.
     */
    private val _connectedVia = MutableStateFlow<String?>(null)
    val connectedVia: StateFlow<String?> = _connectedVia.asStateFlow()

    fun setConnectedVia(ip: String?) { _connectedVia.value = ip }

    /**
     * Call on the main thread the instant the user taps, before any IO. Owns
     * nothing and blocks nothing - it just stops the UI from lying.
     */
    fun beginPreparing() {
        _progress.value = Progress(running = true, stage = Stage.PREPARING)
    }

    /** The core has the candidates and is timing them for real. */
    fun markMeasuring() {
        _progress.value = _progress.value.copy(stage = Stage.MEASURING)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var huntJob: Job? = null

    /** Survivors waiting to be measured. */
    private val pending = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())

    /** True once every seed has been probed - see [MIN_BATCH]. */
    @Volatile
    private var seedPhaseDone = false

    @Volatile
    private var lastDrainAt = 0L

    @Volatile
    private var lastEmitAt = 0L

    fun isRunning(): Boolean = huntJob?.isActive == true

    fun reset() {
        if (!isRunning()) _progress.value = Progress()
    }

    /* ───────────────────────── address maths ───────────────────────── */

    private data class Block(val base: Long, val size: Long)

    @Volatile
    private var blocksCache: List<Block>? = null

    private val blocks: List<Block>
        get() = blocksCache ?: buildBlocks().also { blocksCache = it }

    private fun buildBlocks(): List<Block> {
        return CF_RANGES.mapNotNull { cidr ->
            runCatching {
                val (ip, bits) = cidr.split("/")
                val p = ip.split(".").map { it.toLong() }
                Block((p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3], 1L shl (32 - bits.toInt()))
            }.getOrNull()
        }
    }

    fun addressSpace(): Long = blocks.sumOf { it.size }

    /**
     * Loads the ranges to sweep: the APK's own copy first, then whatever
     * Cloudflare is publishing today.
     *
     * The order is deliberate. The previous version preferred the network and
     * only touched the asset if the request failed outright - which meant a
     * filtered or hijacked response could silently replace the search space,
     * and a slow one held up the scan. The bundled list is correct, instant
     * and cannot be tampered with, so it is the baseline; a live answer is
     * merged in on top purely to catch a new allocation between releases.
     *
     * Union rather than replace: an address Cloudflare stopped advertising
     * this morning is still answering on port 443 this afternoon.
     */
    fun refreshRanges(context: android.content.Context) = runCatching {
        val bundled = BundledData.cloudflareCidrs(context)
        val fromNet = runCatching {
            com.v2ray.ang.util.HttpUtil.getUrlContent(
                com.v2ray.ang.dto.UrlContentRequest(
                    url = "https://www.cloudflare.com/ips-v4", timeout = 8000,
                )
            )
        }.getOrNull().orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.contains("/") && it.count { c -> c == '.' } == 3 }
            .toList()

        val merged = (bundled + fromNet).distinct()
        if (merged.size >= 10) {
            CF_RANGES = merged
            blocksCache = null
            LogUtil.i(
                AppConfig.TAG,
                "CleanIpScanner: ${merged.size} ranges (${bundled.size} bundled, " +
                    "${fromNet.size} live), ${addressSpace()} addresses",
            )
        }
    }.getOrNull().let { }

    /** A uniformly random address from the published ranges. */
    fun randomAddress(): String {
        var pick = Random.nextLong(addressSpace())
        for (b in blocks) {
            if (pick < b.size) {
                val v = b.base + pick
                return "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
            }
            pick -= b.size
        }
        return "104.16.0.1"
    }

    /** True when [ip] falls inside one of the published ranges. */
    fun isInRange(ip: String): Boolean = runCatching {
        val p = ip.split(".").map { it.toLong() }
        if (p.size != 4) return@runCatching false
        val v = (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
        blocks.any { v >= it.base && v < it.base + it.size }
    }.getOrDefault(false)

    /* ───────────────────────── the probe ───────────────────────── */

    /**
     * A TLS handshake to [ip] announcing [sni].
     *
     * Far more selective than a bare TCP connect - which proves nothing here,
     * since Cloudflare answers port 443 for anything at all.
     *
     * @return handshake time in ms, or -1 when it failed.
     */
    fun handshake(ip: String, port: Int, sni: String): Long {
        var socket: SSLSocket? = null
        val start = System.currentTimeMillis()
        return try {
            val raw = Socket()
            raw.connect(InetSocketAddress(ip, port), HANDSHAKE_TIMEOUT_MS)
            raw.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, ip, port, true) as SSLSocket
            socket.sslParameters = socket.sslParameters.apply {
                serverNames = listOf(SNIHostName(sni))
            }
            socket.startHandshake()
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        } finally {
            runCatching { socket?.close() }
        }
    }

    /* ───────────────────── continuous hunting ───────────────────── */

    /**
     * Starts hunting and never stops on its own.
     *
     * Survivors pile up in [pending]; the caller drains them with [takeBatch]
     * while this keeps probing, so measuring never pauses the search.
     */
    /**
     * Starts hunting and never stops on its own.
     *
     * [seeds] are addresses someone already believes in - the ones ircf.space
     * publishes for this operator, plus any that genuinely worked here before.
     * They are probed first and all at once, so the good case finishes in a
     * second or two; the random sweep then carries on behind them forever.
     *
     * Survivors pile up in [pending]; the caller drains them with [takeBatch]
     * while this keeps probing, so measuring never pauses the search.
     */
    fun start(
        template: ConfigTemplate,
        seeds: List<String> = emptyList(),
        seedIrcf: Int = 0,
        seedMemory: Int = 0,
    ) {
        if (isRunning()) return
        pending.clear()
        seedPhaseDone = false
        lastDrainAt = System.currentTimeMillis()
        lastEmitAt = 0L
        val probed = AtomicInteger(0)
        val alive = AtomicInteger(0)
        val recent = ArrayDeque<Probe>()
        _progress.value = Progress(
            running = true, seedIrcf = seedIrcf, seedMemory = seedMemory,
            stage = if (seeds.isEmpty()) Stage.SWEEPING else Stage.SEEDING,
        )

        fun record(ip: String, ok: Boolean, ms: Long) {
            probed.incrementAndGet()
            if (ok) alive.incrementAndGet()
            synchronized(recent) {
                recent.addFirst(Probe(ip, ok, if (ok) ms else -1L))
                while (recent.size > RECENT_LIMIT) recent.removeLast()

                // Thirty-two workers finishing a 1.5 s handshake each produce
                // roughly twenty of these a second, and the old code turned
                // every single one into a StateFlow emission carrying a fresh
                // fifty-element list. Compose recomposed the whole probe list
                // each time, which is what the scan actually felt like.
                val now = System.currentTimeMillis()
                if (!ok && now - lastEmitAt < EMIT_INTERVAL_MS) return
                lastEmitAt = now

                _progress.value = Progress(
                    probed = probed.get(),
                    alive = alive.get(),
                    recent = recent.toList(),
                    running = true,
                    seedIrcf = seedIrcf,
                    seedMemory = seedMemory,
                    // Rebuilt wholesale each time, so carry the stage across
                    // or the UI drops back to PREPARING on every probe.
                    stage = if (seedPhaseDone) Stage.SWEEPING else Stage.SEEDING,
                )
            }
        }

        huntJob = scope.launch {
            val port = template.port.takeIf { it in CF_PORTS } ?: 443

            // ---- seeds first, all together -------------------------------
            if (seeds.isNotEmpty()) {
                val gate = Semaphore(PARALLEL)
                coroutineScope {
                    seeds.distinct().forEach { ip ->
                        launch {
                            gate.withPermit {
                                val ms = handshake(ip, port, template.sni)
                                if (ms >= 0) pending.add(ip to port)
                                record(ip, ms >= 0, ms)
                            }
                        }
                    }
                }
            }

            seedPhaseDone = true
            _progress.value = _progress.value.copy(stage = Stage.SWEEPING)

            // ---- then the endless random sweep ---------------------------
            //
            // This used to fire waves of 64 through a semaphore inside a
            // coroutineScope, which made every wave wait for its own slowest
            // handshake before the next could start. With a 1.5 s timeout one
            // dead address stalled thirty-one live workers. Persistent workers
            // have no barrier: each takes the next address the moment it is
            // free, so throughput stays flat instead of sawtoothing.
            coroutineScope {
                repeat(PARALLEL) {
                    launch {
                        while (isActive) {
                            val ip = randomAddress()
                            val ms = handshake(ip, port, template.sni)
                            if (ms >= 0) pending.add(ip to port)
                            record(ip, ms >= 0, ms)
                        }
                    }
                }
            }
        }
        huntJob?.invokeOnCompletion {
            _progress.value = _progress.value.copy(running = false)
        }
    }

    fun stop() {
        huntJob?.cancel()
        huntJob = null
        seedPhaseDone = false
        _progress.value = _progress.value.copy(running = false, stage = Stage.IDLE)
    }

    /**
     * True once there is something worth measuring.
     *
     * A full [BATCH] is the happy path, but demanding twelve was a real bug:
     * the seeds - the operator's own known-good addresses - are probed first
     * and typically yield a handful within a second or two. The hunt then sat
     * on them, refusing to measure, until a random sweep of 1.5 million
     * addresses happened to turn up enough strangers to round the number out.
     * That is the "it finds them and then does nothing" stall.
     */
    fun batchReady(): Boolean {
        val n = pending.size
        if (n >= BATCH) return true
        if (n == 0 || !seedPhaseDone) return false
        return n >= MIN_BATCH ||
            System.currentTimeMillis() - lastDrainAt >= DRAIN_AFTER_MS
    }

    fun pendingCount(): Int = pending.size

    /**
     * Takes the survivors found so far, rewrites [template] onto each of them
     * and imports the result.
     *
     * @return guids ready to be measured by the core.
     */
    fun takeBatch(template: ConfigTemplate): List<String> {
        val batch: List<Pair<String, Int>>
        synchronized(pending) {
            if (pending.isEmpty()) return emptyList()
            batch = pending.take(BATCH).toList()
            repeat(batch.size) { if (pending.isNotEmpty()) pending.removeAt(0) }
        }
        lastDrainAt = System.currentTimeMillis()
        return runCatching {
            ensureGroup()
            MmkvManager.removeServerViaSubid(SCAN_SUB_ID)
            val payload = batch.joinToString("\n") { (ip, port) -> template.withAddress(ip, port) }
            AngConfigManager.importBatchConfig(payload, SCAN_SUB_ID, false)
            MmkvManager.decodeServerList(SCAN_SUB_ID)
        }.getOrDefault(emptyList())
    }

    private fun ensureGroup() {
        val existing = runCatching { MmkvManager.decodeSubscriptions() }.getOrDefault(emptyList())
        if (existing.any { it.guid == SCAN_SUB_ID }) return
        MmkvManager.encodeSubscription(
            SCAN_SUB_ID,
            SubscriptionItem(remarks = SCAN_REMARKS, url = "", enabled = false, autoUpdate = false),
        )
    }

    /* ─────────────── memory of addresses that really worked ─────────────── */

    private const val KEY_WINNERS = "fn_scan_winners_"
    private const val WINNER_LIMIT = 12

    /**
     * Remembers an address that passed a real test on this network.
     *
     * Stored per operator, because an address that works on Irancell says
     * nothing about what works on a home connection.
     */
    fun rememberWinner(carrier: String, ip: String) = runCatching {
        if (carrier.isBlank() || !DohResolver.isIpv4(ip)) return@runCatching
        val key = KEY_WINNERS + carrier.lowercase().replace(' ', '_')
        val now = MmkvManager.decodeSettingsString(key).orEmpty()
            .split(",").map { it.trim() }.filter { DohResolver.isIpv4(it) }
        val next = (listOf(ip) + now).distinct().take(WINNER_LIMIT)
        MmkvManager.encodeSettings(key, next.joinToString(","))
    }.getOrNull()

    fun winners(carrier: String): List<String> = runCatching {
        val key = KEY_WINNERS + carrier.lowercase().replace(' ', '_')
        MmkvManager.decodeSettingsString(key).orEmpty()
            .split(",").map { it.trim() }.filter { DohResolver.isIpv4(it) }
    }.getOrDefault(emptyList())

    /** Pulls the address back out of a scan config, for [rememberWinner]. */
    fun addressOfScanConfig(guid: String): String? = runCatching {
        val p = MmkvManager.decodeServerConfig(guid) ?: return@runCatching null
        p.server?.takeIf { DohResolver.isIpv4(it) }
    }.getOrNull()

    fun clear() {
        runCatching { MmkvManager.removeServerViaSubid(SCAN_SUB_ID) }
    }

    /* ───────────────────────── the template ───────────────────────── */

    data class ConfigTemplate(
        val raw: String,
        val host: String,
        val port: Int,
        val sni: String,
    ) {
        /** The same credentials, aimed at a different door. */
        fun withAddress(ip: String, newPort: Int): String {
            val replaced = raw.replaceFirst("@$host:$port", "@$ip:$newPort")
            return if (replaced.contains("#")) {
                replaced.substringBeforeLast("#") + "#SCAN-$ip"
            } else {
                "$replaced#SCAN-$ip"
            }
        }
    }

    private val HOST_PORT = Regex("""@(?:\[([^\[\]]+)]|([^/?#@:]+)):(\d{1,5})""")

    fun templateOf(link: String): ConfigTemplate? = runCatching {
        val m = HOST_PORT.find(link) ?: return@runCatching null
        val host = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val port = m.groupValues[3].toIntOrNull() ?: return@runCatching null
        val sni = Regex("""[?&](?:sni|host)=([^&#]+)""").find(link)?.groupValues?.get(1)
            ?: return@runCatching null
        if (host.isBlank() || sni.isBlank()) return@runCatching null
        ConfigTemplate(link, host, port, sni)
    }.getOrNull()

    /** The backend most of the pool points at is the one most likely alive. */
    fun bestTemplate(links: List<String>): ConfigTemplate? {
        val templates = links.mapNotNull { templateOf(it) }
        if (templates.isEmpty()) return null
        return templates.groupBy { it.sni.lowercase() }
            .maxByOrNull { it.value.size }?.value?.firstOrNull()
    }

    /* ───────────────────── what kind of block is this ───────────────────── */

    enum class BlockType {
        /** Our name works somewhere - scanning will find more doors. */
        ADDRESS_BLOCKED,

        /** Neutral names work, ours does not: the name itself is filtered. */
        NAME_BLOCKED,

        /** Nothing reaches the CDN at all on this network. */
        NETWORK_BLOCKED,

        /** Everything answers - the problem is elsewhere. */
        NOT_BLOCKED,
    }

    data class BlockReport(val type: BlockType, val oursOk: Int, val neutralOk: Int, val tried: Int)

    private const val NEUTRAL_SNI = "www.cloudflare.com"

    /**
     * FILTERNET: answers the only question that decides whether scanning is
     * worth anything on this network.
     *
     * Each address is tried twice - once with our own name, once with a neutral
     * one. If the neutral name sails through while ours is refused, the filter
     * is matching on the name and no amount of address hunting will help; the
     * Worker itself has to be replaced.
     */
    suspend fun classifyBlock(template: ConfigTemplate, samples: Int = 12): BlockReport =
        withContext(Dispatchers.IO) {
            val oursOk = AtomicInteger(0)
            val neutralOk = AtomicInteger(0)
            val gate = Semaphore(12)
            coroutineScope {
                repeat(samples) {
                    launch {
                        gate.withPermit {
                            val ip = randomAddress()
                            val port = template.port.takeIf { it in CF_PORTS } ?: 443
                            if (handshake(ip, port, template.sni) >= 0) oursOk.incrementAndGet()
                            if (handshake(ip, port, NEUTRAL_SNI) >= 0) neutralOk.incrementAndGet()
                        }
                    }
                }
            }
            val o = oursOk.get()
            val n = neutralOk.get()
            val type = when {
                o > 0 && n > 0 -> BlockType.NOT_BLOCKED
                o > 0 -> BlockType.ADDRESS_BLOCKED
                n > 0 -> BlockType.NAME_BLOCKED
                else -> BlockType.NETWORK_BLOCKED
            }
            LogUtil.i(AppConfig.TAG, "CleanIpScanner: block type $type (ours=$o neutral=$n of $samples)")
            BlockReport(type, o, n, samples)
        }
}
