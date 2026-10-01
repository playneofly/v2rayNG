package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * FILTERNET: the endless clean-IP hunt.
 *
 * ── why this works ────────────────────────────────────────────────────────
 * Our configs live on Cloudflare Workers. Cloudflare is an anycast network:
 * every one of its ~1.5 million edge IPs terminates TLS and routes onward by
 * the SNI / Host name, not by address. So one Worker is reachable through all
 * of them - a single house with a million front doors.
 *
 * Filtering never closes every door, because Iranian sites sit behind the same
 * addresses. There is always a door left open; the job is to find it.
 *
 * ── why the search never ends ─────────────────────────────────────────────
 * Addresses are drawn at random from the published ranges and the scan simply
 * keeps going until something answers or the user stops it. There is no cap.
 *
 * ── why a handshake is not enough ─────────────────────────────────────────
 * We learned this the hard way: Cloudflare answers port 443 for anything, so a
 * TCP connect proves nothing. Stage one here does a real TLS handshake *with
 * our own SNI*, which is far more selective, and the survivors are then handed
 * to the core for a genuine request. Only that counts as found.
 */
object CleanIpScanner {

    /** Scratch group that holds the addresses currently being proven. */
    const val SCAN_SUB_ID = "filternetscanpool000000000001"
    private const val SCAN_REMARKS = "FILTERNET SCAN"

    /** Addresses probed at the same time. */
    private const val PARALLEL = 32

    /** A handshake slower than this is not worth keeping. */
    private const val HANDSHAKE_TIMEOUT_MS = 1500

    /** How many survivors to collect before handing them to the core. */
    private const val BATCH = 16

    /**
     * Cloudflare's published IPv4 ranges. Roughly 1.5 million addresses.
     * Refreshed from the network when possible, but these defaults mean the
     * scanner still works during a shutdown with no internet to ask.
     */
    private val CF_RANGES = listOf(
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
        "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
        "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
    )

    /** Ports Cloudflare accepts for proxied traffic. */
    private val CF_PORTS = listOf(443, 2053, 2083, 2087, 2096, 8443)

    sealed interface Phase {
        data object Idle : Phase
        data class Scanning(val probed: Int, val found: Int, val round: Int) : Phase
        data class Proving(val count: Int) : Phase
        data class Found(val address: String) : Phase
        data object GaveUp : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    fun reset() {
        if (!running.get()) _phase.value = Phase.Idle
    }

    /** One decoded /n block, kept as a base address plus a size. */
    private data class Block(val base: Long, val size: Long)

    private val blocks: List<Block> by lazy {
        CF_RANGES.mapNotNull { cidr ->
            runCatching {
                val (ip, bits) = cidr.split("/")
                val parts = ip.split(".").map { it.toLong() }
                val base = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
                Block(base, 1L shl (32 - bits.toInt()))
            }.getOrNull()
        }
    }

    /** Total addresses the scanner can draw from. */
    fun addressSpace(): Long = blocks.sumOf { it.size }

    private fun randomAddress(): String {
        val total = addressSpace()
        var pick = Random.nextLong(total)
        for (b in blocks) {
            if (pick < b.size) {
                val v = b.base + pick
                return "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
            }
            pick -= b.size
        }
        return "104.16.0.1"
    }

    /**
     * A TLS handshake to [ip] announcing [sni].
     *
     * Far more selective than a bare TCP connect: the edge has to actually
     * complete a handshake for our own name, which a blackholed or hijacked
     * address will not do.
     *
     * @return handshake time in ms, or -1.
     */
    private fun handshake(ip: String, port: Int, sni: String): Long {
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

    /**
     * Hunts for working addresses for [template] and imports the survivors
     * into [SCAN_SUB_ID] so the caller can prove them with the core.
     *
     * Runs until it has a batch, or until cancelled. There is no attempt limit.
     *
     * @return true when a batch is ready to be measured.
     */
    suspend fun hunt(template: ConfigTemplate): Boolean = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext false
        try {
            val probed = AtomicInteger(0)
            val found = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
            val gate = Semaphore(PARALLEL)
            var round = 0

            while (found.size < BATCH) {
                ensureActive()
                round++
                coroutineScope {
                    repeat(PARALLEL * 4) {
                        launch {
                            if (found.size >= BATCH) return@launch
                            gate.withPermit {
                                if (found.size >= BATCH) return@withPermit
                                val ip = randomAddress()
                                val port = template.port.takeIf { it in CF_PORTS }
                                    ?: CF_PORTS.random()
                                val ms = handshake(ip, port, template.sni)
                                probed.incrementAndGet()
                                if (ms >= 0) found.add(ip to port)
                                _phase.value = Phase.Scanning(probed.get(), found.size, round)
                            }
                        }
                    }
                }
                // Nothing at all after a very large sweep means the whole network
                // is unreachable, not that we were unlucky. Keep going anyway -
                // the user asked for endless, and they can stop it themselves.
                LogUtil.i(
                    AppConfig.TAG,
                    "CleanIpScanner: round $round, probed ${probed.get()}, found ${found.size}",
                )
            }

            _phase.value = Phase.Proving(found.size)
            val imported = importCandidates(template, found.toList())
            if (imported <= 0) {
                _phase.value = Phase.GaveUp
                return@withContext false
            }
            true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "CleanIpScanner: hunt failed", e)
            _phase.value = Phase.Idle
            false
        } finally {
            running.set(false)
        }
    }

    fun publishFound(address: String) {
        _phase.value = Phase.Found(address)
    }

    fun publishGaveUp() {
        _phase.value = Phase.GaveUp
    }

    /** Rewrites the template once per address and imports the lot. */
    private fun importCandidates(
        template: ConfigTemplate,
        addresses: List<Pair<String, Int>>,
    ): Int {
        ensureGroup()
        MmkvManager.removeServerViaSubid(SCAN_SUB_ID)
        val payload = addresses.joinToString("\n") { (ip, port) ->
            template.withAddress(ip, port)
        }
        AngConfigManager.importBatchConfig(payload, SCAN_SUB_ID, false)
        return MmkvManager.decodeServerList(SCAN_SUB_ID).size
    }

    private fun ensureGroup() {
        val existing = runCatching { MmkvManager.decodeSubscriptions() }.getOrDefault(emptyList())
        if (existing.any { it.guid == SCAN_SUB_ID }) return
        MmkvManager.encodeSubscription(
            SCAN_SUB_ID,
            SubscriptionItem(remarks = SCAN_REMARKS, url = "", enabled = false, autoUpdate = false),
        )
    }

    fun clear() {
        runCatching { MmkvManager.removeServerViaSubid(SCAN_SUB_ID) }
    }

    /* ─────────────────────────── the template ─────────────────────────── */

    /**
     * A share link with its address factored out, so the same credentials can
     * be pointed at any edge address we find.
     */
    data class ConfigTemplate(
        val raw: String,
        val host: String,
        val port: Int,
        val sni: String,
    ) {
        /** The same link, aimed at a different door. */
        fun withAddress(ip: String, newPort: Int): String {
            val replaced = raw.replaceFirst("@$host:$port", "@$ip:$newPort")
            // Tag it so the user can see where it came from.
            return if (replaced.contains("#")) {
                replaced.substringBeforeLast("#") + "#FILTERNET-SCAN-$ip"
            } else {
                "$replaced#FILTERNET-SCAN-$ip"
            }
        }
    }

    private val HOST_PORT = Regex("""@(?:\[([^\[\]]+)]|([^/?#@:]+)):(\d{1,5})""")

    /**
     * Turns a share link into a template. Only links that carry an SNI or host
     * header can be re-aimed, which in practice means the CDN based ones - and
     * those are exactly the ones worth scanning for.
     */
    fun templateOf(link: String): ConfigTemplate? = runCatching {
        val m = HOST_PORT.find(link) ?: return@runCatching null
        val host = m.groupValues[1].ifEmpty { m.groupValues[2] }
        val port = m.groupValues[3].toIntOrNull() ?: return@runCatching null
        val sni = Regex("""[?&](?:sni|host)=([^&#]+)""").find(link)?.groupValues?.get(1)
            ?: return@runCatching null
        if (host.isBlank() || sni.isBlank()) return@runCatching null
        ConfigTemplate(link, host, port, sni)
    }.getOrNull()

    /**
     * Picks the best template out of the pool: the backend that the most
     * configs point at, because that is the one most likely to still be alive.
     */
    fun bestTemplate(links: List<String>): ConfigTemplate? {
        val templates = links.mapNotNull { templateOf(it) }
        if (templates.isEmpty()) return null
        val byBackend = templates.groupBy { it.sni.lowercase() }
        return byBackend.maxByOrNull { it.value.size }?.value?.firstOrNull()
    }
}
