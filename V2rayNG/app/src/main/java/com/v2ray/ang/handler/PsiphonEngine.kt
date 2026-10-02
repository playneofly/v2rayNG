package com.v2ray.ang.handler

import android.content.Context
import ca.psiphon.PsiphonTunnel
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.zip.GZIPInputStream

/**
 * FILTERNET: Psiphon as a second way out, for the networks where ours has none.
 *
 * The scanning engine answers one question - "which address still reaches the
 * CDN" - and on a carrier that filters the CDN path itself, no answer to that
 * question helps. Psiphon asks a different question entirely: it carries an
 * arsenal of transports (obfuscated SSH, meek fronting through ordinary CDNs,
 * QUIC, refraction networking, and peer-to-peer proxying over WebRTC) and
 * tries them until one survives.
 *
 * This runs Psiphon **without** its VPN mode. Android permits exactly one
 * VpnService and ours already owns it, so Psiphon is asked only to open a
 * local SOCKS proxy; the tunnel device stays ours and `hev-socks5-tunnel` is
 * simply pointed at Psiphon's port instead of Xray's. That is possible only
 * because [com.v2ray.ang.service.TProxyService] reads a port number and does
 * not care what is listening on it.
 *
 * ### Why this can be absent at runtime
 *
 * The published Psiphon library ships `armeabi-v7a` only. This project builds
 * a separate APK per ABI, so the arm64 APK has no Psiphon native library in
 * it. Every entry point here is therefore defensive: [isAvailable] is checked
 * before anything is touched, and a missing class or library degrades to "not
 * available" rather than taking the app down. The 32-bit APK runs correctly on
 * 64-bit phones, so the v7a build is the one to install when this is wanted.
 */
object PsiphonEngine {

    /** Where the Psiphon configuration lives. */
    private const val CONFIG_ASSET = "psiphon_config.json"

    /**
     * Four hundred server entries, gzipped.
     *
     * These matter more than they look. Without them Psiphon has to fetch a
     * server list before it can connect to anything - and on the networks
     * this feature exists for, that first fetch is exactly what is blocked.
     * With them it has somewhere to go the moment it starts.
     */
    private const val SERVERS_ASSET = "psiphon_servers.txt.gz"

    enum class State { STOPPED, CONNECTING, CONNECTED, FAILED }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The local SOCKS port Psiphon is listening on, or 0 when there is none. */
    private val _socksPort = MutableStateFlow(0)
    val socksPort: StateFlow<Int> = _socksPort.asStateFlow()

    /** Last diagnostic worth showing a human. Never contains credentials. */
    private val _lastNotice = MutableStateFlow<String?>(null)
    val lastNotice: StateFlow<String?> = _lastNotice.asStateFlow()

    private var tunnel: PsiphonTunnel? = null

    /**
     * True when this build can actually run Psiphon.
     *
     * The Java classes travel in every APK regardless of ABI, so their
     * presence proves nothing; the native library is the part that can be
     * missing. `psi.Psi.touch()` exists precisely to force the gomobile
     * binding to initialise, and that initialiser is what calls
     * `System.loadLibrary("gojni")`. On an APK built for an ABI Psiphon was
     * never published for, this is where it fails - loudly, here, instead of
     * halfway through a connection attempt.
     *
     * The failure is an [UnsatisfiedLinkError] rather than an exception, so
     * this deliberately relies on `runCatching` catching [Throwable].
     */
    val isAvailable: Boolean by lazy {
        runCatching {
            psi.Psi.touch()
            true
        }.getOrElse { e ->
            LogUtil.w(AppConfig.TAG, "Psiphon: native library unavailable (${e.javaClass.simpleName})")
            false
        }
    }

    /** True when the user has supplied a configuration for it to use. */
    fun hasConfig(context: Context): Boolean = runCatching {
        context.assets.open(CONFIG_ASSET).use { it.read() >= 0 }
    }.getOrDefault(false)

    /**
     * Reads the Psiphon configuration out of assets.
     *
     * Deliberately not hardcoded. A Psiphon configuration identifies who is
     * propagating the client, and the honest way to hold one is for the
     * person shipping the app to put their own in place rather than for this
     * code to carry somebody else's.
     */
    private fun readConfig(context: Context): String? = runCatching {
        context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
    }.getOrNull()

    /**
     * The bundled server entries, newline separated, exactly the shape
     * `startTunneling` expects. Returns an empty string when the asset is
     * missing, which is survivable: Psiphon then falls back to fetching a
     * list over the network.
     */
    private fun readServerEntries(context: Context): String = runCatching {
        context.assets.open(SERVERS_ASSET).use { raw ->
            GZIPInputStream(raw).bufferedReader().use { it.readText() }
        }
    }.getOrElse {
        LogUtil.w(AppConfig.TAG, "Psiphon: no bundled server entries")
        ""
    }

    /**
     * Starts Psiphon and returns once it is running, or immediately on
     * failure. The SOCKS port arrives asynchronously through [socksPort];
     * callers should wait on that rather than assume a port exists on return.
     *
     * @return false when Psiphon cannot run here at all.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (!isAvailable) {
            LogUtil.w(AppConfig.TAG, "Psiphon: library not present in this build")
            _state.value = State.FAILED
            return false
        }
        if (tunnel != null) return true

        val config = readConfig(context)
        if (config.isNullOrBlank()) {
            LogUtil.w(AppConfig.TAG, "Psiphon: no $CONFIG_ASSET bundled")
            _state.value = State.FAILED
            return false
        }

        _state.value = State.CONNECTING
        _socksPort.value = 0

        return runCatching {
            val host = Host(context.applicationContext, config)
            val t = PsiphonTunnel.newPsiphonTunnel(host)
            tunnel = t
            // No startRouting(): our own VpnService owns the tunnel device.
            // This asks only for a local SOCKS proxy.
            val entries = readServerEntries(context)
            LogUtil.i(AppConfig.TAG, "Psiphon: starting with ${entries.count { c -> c == '\n' }} embedded servers")
            t.startTunneling(entries)
            true
        }.getOrElse { e ->
            LogUtil.e(AppConfig.TAG, "Psiphon: failed to start", e as? Exception ?: Exception(e))
            _state.value = State.FAILED
            tunnel = null
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { tunnel?.stop() }
        tunnel = null
        _socksPort.value = 0
        _state.value = State.STOPPED
    }

    /**
     * The callbacks Psiphon drives. Only a handful matter to us; the rest are
     * required by the interface and are deliberately inert.
     */
    private class Host(
        private val ctx: Context,
        private val config: String,
    ) : PsiphonTunnel.HostService {

        override fun getAppName(): String = "FILTERNET"

        override fun getContext(): Context = ctx

        // Null is correct here: these are only consulted in Psiphon's own VPN
        // mode, which this integration never enters.
        override fun getVpnService(): Any? = null

        override fun newVpnServiceBuilder(): Any? = null

        override fun getPsiphonConfig(): String = config

        override fun onDiagnosticMessage(message: String) {
            // Psiphon's diagnostics carry no credentials, but they are noisy;
            // keep them at info and out of the user-facing notice.
            LogUtil.i(AppConfig.TAG, "Psiphon: $message")
        }

        override fun onListeningSocksProxyPort(port: Int) {
            LogUtil.i(AppConfig.TAG, "Psiphon: SOCKS on $port")
            _socksPort.value = port
        }

        override fun onConnecting() {
            _state.value = State.CONNECTING
        }

        override fun onConnected() {
            LogUtil.i(AppConfig.TAG, "Psiphon: connected")
            _state.value = State.CONNECTED
        }

        override fun onExiting() {
            _state.value = State.STOPPED
        }

        override fun onUpstreamProxyError(message: String) {
            _lastNotice.value = message
        }

        override fun onStartedWaitingForNetworkConnectivity() {
            _lastNotice.value = "waiting for network"
        }

        override fun onSocksProxyPortInUse(port: Int) {
            LogUtil.w(AppConfig.TAG, "Psiphon: SOCKS port $port already in use")
        }

        override fun onHttpProxyPortInUse(port: Int) {
            LogUtil.w(AppConfig.TAG, "Psiphon: HTTP port $port already in use")
        }

        override fun onListeningHttpProxyPort(port: Int) = Unit

        override fun onAvailableEgressRegions(regions: MutableList<String>?) = Unit

        override fun onHomepage(url: String) = Unit

        override fun onClientRegion(region: String) = Unit

        override fun onClientUpgradeDownloaded(filename: String) = Unit

        override fun onClientIsLatestVersion() = Unit

        override fun onSplitTunnelRegion(region: String) = Unit

        override fun onUntunneledAddress(address: String) = Unit

        override fun onBytesTransferred(sent: Long, received: Long) = Unit

        override fun onActiveAuthorizationIDs(ids: MutableList<String>?) = Unit
    }
}
