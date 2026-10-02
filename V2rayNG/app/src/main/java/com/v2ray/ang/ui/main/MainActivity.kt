package com.v2ray.ang.ui.main

import com.v2ray.ang.handler.IrcfSource
import kotlinx.coroutines.isActive
import com.v2ray.ang.handler.InternalVault
import com.v2ray.ang.handler.CleanIpScanner
import com.v2ray.ang.handler.FilternetMode
import kotlinx.coroutines.delay
import com.v2ray.ang.handler.ServerPoolManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.handler.FilternetCrashHandler
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.AutoTestScheduler
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.compose.SplashOverlay
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MEASURE_TIMEOUT_MS = 75000L

class MainActivity : HelperBaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                    data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService) LauncherManager.restartService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)

        // FILTERNET: after the "Best" flow picks a server, (re)start the tunnel on it.
        mainViewModel.setOnBestServerPicked {
            // FILTERNET: the search is finished here. Without this the pool phase
            // stayed on "measuring" forever, which kept the button amber and
            // disabled for the rest of the session.
            ServerPoolManager.publishConnected()
            if (mainViewModel.uiState.value.isRunning) {
                LauncherManager.restartService(this)
            } else {
                startV2Ray()
            }
        }

        // FILTERNET: keep the background health checker in sync with its setting.
        AutoTestScheduler.sync(this)

        // FILTERNET: start or stop the "connect when an app opens" watcher.
        com.v2ray.ang.service.AppTriggerService.sync(this)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    override fun onDestroy() {
        mainViewModel.setOnBestServerPicked(null)
        super.onDestroy()
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }

        // FILTERNET: if the app died last time, show the real reason instead of
        // leaving the user with the bare "FILTERNET keeps stopping" system dialog.
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var crashReport by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(FilternetCrashHandler.consume(ctx))
        }
        crashReport?.let { report ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { crashReport = null },
                title = { androidx.compose.material3.Text(stringResource(R.string.fn_crash_title)) },
                text = {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        androidx.compose.material3.Text(
                            text = stringResource(R.string.fn_crash_desc),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                        androidx.compose.foundation.layout.Spacer(
                            androidx.compose.ui.Modifier.height(10.dp)
                        )
                        androidx.compose.material3.Text(
                            text = report,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        Utils.setClipboard(ctx, report)
                        toastSuccess(R.string.toast_success)
                        crashReport = null
                    }) { androidx.compose.material3.Text(stringResource(R.string.fn_crash_copy)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { crashReport = null }) {
                        androidx.compose.material3.Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        // FILTERNET: keep the pool list warm so the very first tap on the
        // connect button does not have to wait for a download.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            runCatching { ServerPoolManager.refreshIfStale(applicationContext) }
        }

        // FILTERNET: nothing is reachable until VPN consent and the notification
        // permission are granted. The gate closes itself the moment they are.
        PermissionGate {
        // FILTERNET: brand splash overlay on top of the main screen.
        SplashOverlay {
        MainScreen(
            mainViewModel = mainViewModel,
            onAction = { action ->
                when (action) {
                    MainAction.ToggleService -> handleFabAction()
                    MainAction.AutoConnect -> handleAutoConnect()
                    MainAction.CancelAutoConnect -> cancelAutoConnect()
                    MainAction.DeepConnect -> handleDeepConnect()
                    MainAction.StrongScan -> handleStrongScan()
                    MainAction.TestCurrentServer -> handleLayoutTestClick()
                    MainAction.ImportQRcode -> importQRcode()
                    MainAction.ImportClipboard -> importClipboard()
                    MainAction.ImportConfigLocal -> importConfigLocal()
                    is MainAction.ImportManually -> importManually(action.type)
                    MainAction.RestartService -> LauncherManager.restartServiceOrStart(this, ::requestServiceStart)
                    MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                    is MainAction.SelectServer -> setSelectServer(action.guid)
                    is MainAction.EditServer -> editServer(action.guid, action.profile)
                    is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                    is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                    else -> mainViewModel.onAction(action)
                }
            },
            onNavigate = { route -> navigateTo(route) },
        )
        }
        }
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.Logcat -> Intent(this, LogcatActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
            MainDestination.Promotion -> {
                Utils.openUri(
                    this,
                    "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}"
                )
                return
            }
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isRunning) {
            stopTunnel()
        } else {
            FilternetMode.claim(FilternetMode.Owner.MAIN)
            requestServiceStart()
        }
    }

    /**
     * FILTERNET: the one way down.
     *
     * Whoever owned the tunnel, stopping it hands the home tab back the
     * profile the hunt borrowed - otherwise the next ordinary connect would
     * silently reuse a scan result that is only good for one operator on one
     * evening.
     */
    private fun stopTunnel() {
        LauncherManager.stopService(this)
        CleanIpScanner.setConnectedVia(null)
        CleanIpScanner.setQuality(CleanIpScanner.Quality.NONE)
        if (FilternetMode.isInternal()) {
            FilternetMode.consumeSelection()?.let { mainViewModel.selectServerQuietly(it) }
        }
        FilternetMode.release()
    }

    private fun requestServiceStart() {
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    /**
     * FILTERNET: the one-button flow.
     *
     * Earlier versions brought the real tunnel up on one candidate after another
     * to see which worked. That is what made the app flap between connected and
     * disconnected - starting and stopping a VpnService five times in a row is
     * violent and the user sees every second of it.
     *
     * The core can already measure a config properly *without* a tunnel: it
     * spins up a throw-away instance in a separate process and performs a real
     * request through it. So now:
     *
     *   1. download the pool and screen it cheaply  (no VPN)
     *   2. let the core really measure the short-list (no VPN, separate process)
     *   3. start the tunnel exactly ONCE, on the winner
     *
     * One start, no flapping.
     */
    private fun handleAutoConnect() {
        // The deep hunt and this share one job slot. Returning silently was
        // the whole of the "I press connect and nothing happens, then it
        // locks itself seconds later" bug: a hunt the user had given up on
        // kept running invisibly, swallowed this tap, and then grabbed the
        // screen when it finally succeeded. Tapping here means the user wants
        // an ordinary connection, so the hunt loses.
        if (autoConnectJob?.isActive == true) {
            cancelAutoConnect()
        }
        FilternetMode.claim(FilternetMode.Owner.MAIN)
        autoConnectJob = lifecycleScope.launch {
            val ready = ServerPoolManager.prepareCandidates(applicationContext)
            if (!ready) return@launch

            // Hands over to the measure-then-connect path, which ends in
            // onBestServerPicked and a single startV2Ray().
            mainViewModel.connectBestServer()

            // Watchdog. The measurement runs in its own process; if that process
            // is killed mid-batch nothing ever reports back and the button would
            // stay amber until the app is restarted. Give it a hard ceiling.
            val deadline = System.currentTimeMillis() + MEASURE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(500L)
                val s = mainViewModel.uiState.value
                if (!s.isFindingBest && !s.isTesting) return@launch
                if (s.isRunning) return@launch
            }
            LogUtil.w(AppConfig.TAG, "AutoConnect: measurement timed out")
            mainViewModel.onAction(MainAction.CancelTesting)
            ServerPoolManager.publishNonePassed()
            autoConnectJob = null
        }
    }

    private var autoConnectJob: kotlinx.coroutines.Job? = null

    /**
     * FILTERNET: the internal tab. Addresses only.
     *
     * This path deliberately ignores the shared pool entirely - those live on
     * the home screen. Here the only thing that varies is the *door*: the
     * credentials stay yours, and we keep trying edge addresses until one of
     * them still lets your Worker through.
     *
     * Three sources feed the same hunt, all started together:
     *
     *   · addresses that really worked on this operator before  - instant
     *   · the per-operator list from ircf.space, over DoH        - seconds
     *   · an endless random sweep of the CDN ranges              - forever
     *
     * Seeds are probed first so the common case finishes quickly, and the
     * sweep carries on behind them so nothing ever waits its turn.
     */
    private fun handleDeepConnect() {
        if (autoConnectJob?.isActive == true) return
        // The private tab owns this tunnel from the first probe, not from the
        // moment it succeeds. Claiming late left a window where the home tab
        // saw a tunnel it did not start and happily offered to disconnect it.
        FilternetMode.claim(FilternetMode.Owner.INTERNAL)
        FilternetMode.rememberSelection(mainViewModel.uiState.value.selectedGuid)
        // Before this the orb sat at rest through refreshRanges, a 600 KB
        // gunzip and a DoH round trip - several seconds on a bad network -
        // so the tap looked ignored. Published synchronously, on this thread.
        CleanIpScanner.beginPreparing()
        CleanIpScanner.setConnectedVia(null)
        autoConnectJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CleanIpScanner.refreshRanges(applicationContext)
                }

                // Credentials come from the private bundle when there is one,
                // otherwise from the shared pool - but only as a template.
                val links = withContext(Dispatchers.IO) {
                    InternalVault.configs().ifEmpty {
                        ServerPoolManager.poolLinks(applicationContext)
                    }
                }
                val template = CleanIpScanner.bestTemplate(links)
                if (template == null) {
                    // Silent before: the orb simply fell back to idle and the
                    // user was left thinking the private tab had quietly done
                    // an ordinary connect. Say what went wrong instead.
                    LogUtil.w(AppConfig.TAG, "DeepConnect: no scannable config available")
                    toastError(R.string.fn_deep_no_template)
                    FilternetMode.release()
                    return@launch
                }

                val carrier = IrcfSource.carrierName(applicationContext)
                val memory = withContext(Dispatchers.IO) { CleanIpScanner.winners(carrier) }
                val ircf = withContext(Dispatchers.IO) {
                    IrcfSource.fetch(applicationContext).addresses
                }

                CleanIpScanner.start(
                    template = template,
                    seeds = memory + ircf,
                    seedIrcf = ircf.size,
                    seedMemory = memory.size,
                )

                // On a congested carrier a dozen simultaneous proxy probes
                // can all time out even when the addresses are fine. That is
                // the "finds a thousand live IPs and never connects" report:
                // the handshakes are real, the parallel measurement is what
                // keeps failing. Count the failures and change tactics.
                var failedRounds = 0

                while (isActive) {
                    if (!CleanIpScanner.batchReady()) {
                        delay(800L)
                        continue
                    }
                    val batch = withContext(Dispatchers.IO) { CleanIpScanner.takeBatch(template) }
                    if (batch.isEmpty()) {
                        delay(800L)
                        continue
                    }
                    // 45 s was a round trip the user had to sit through before
                    // the next batch could even be tried. A dozen handshakes
                    // that are going to answer answer well inside 20.
                    CleanIpScanner.markMeasuring()
                    val winner = mainViewModel.measureRound(batch, timeoutMs = 20_000L)

                    if (winner == null) {
                        failedRounds++
                        if (failedRounds >= ROUNDS_BEFORE_DECIDING &&
                            CleanIpScanner.survivorCount() >= SURVIVORS_BEFORE_DECIDING
                        ) {
                            if (decisiveConnect(template, carrier)) return@launch
                            failedRounds = 0
                        }
                    }

                    if (winner != null) {
                        withContext(Dispatchers.IO) {
                            CleanIpScanner.addressOfScanConfig(winner)?.let {
                                CleanIpScanner.rememberWinner(carrier, it)
                                CleanIpScanner.setConnectedVia(it)
                            }
                        }
                        // Passed the full real-ping: this one is proven.
                        CleanIpScanner.setQuality(CleanIpScanner.Quality.VERIFIED)
                        finishDeepConnect(winner)
                        return@launch
                    }
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "DeepConnect failed", e)
                FilternetMode.release()
            } finally {
                CleanIpScanner.stop()
            }
        }
    }

    /** Survivors that must exist before the hunt is allowed to commit. */
    private val SURVIVORS_BEFORE_DECIDING = 20

    /**
     * Failed parallel rounds before we stop trusting parallel measurement.
     * Two, not more: each round costs the user 20 s of staring at a spinner,
     * and the serial path below is strictly more likely to succeed anyway.
     */
    private val ROUNDS_BEFORE_DECIDING = 2

    /**
     * How many of the best addresses get the slow, careful treatment.
     * Four at 10 s caps this stage at ~40 s before we commit regardless.
     */
    private val DECISIVE_CANDIDATES = 4

    /**
     * FILTERNET: stop hunting, start choosing.
     *
     * Reaching here means plenty of addresses complete a TLS handshake but no
     * batch has ever produced a working proxy measurement. Two things are then
     * worth trying, in order:
     *
     *  1. Measure the best few **one at a time**. Twelve parallel probes over
     *     one congested mobile uplink starve each other; a single probe with
     *     the whole link to itself often succeeds where the batch did not.
     *
     *  2. Failing that, connect to the fastest-handshaking address anyway.
     *     A tunnel the user can test beats a spinner that never ends - and on
     *     a carrier that quietly drops the measurement probe this is the only
     *     way through. It may not work; it is still better than nothing, and
     *     the address is shown on screen so the result is visible either way.
     *
     * @return true when a tunnel was started and the hunt should end.
     */
    private suspend fun decisiveConnect(
        template: CleanIpScanner.ConfigTemplate,
        carrier: String,
    ): Boolean {
        val best = withContext(Dispatchers.IO) {
            CleanIpScanner.bestSurvivors(DECISIVE_CANDIDATES)
        }
        if (best.isEmpty()) return false
        LogUtil.i(AppConfig.TAG, "DeepConnect: deciding between ${best.size} best addresses")

        // ---- one at a time, with room to breathe ------------------------
        for (survivor in best) {
            val guids = withContext(Dispatchers.IO) {
                CleanIpScanner.importAll(template, listOf(survivor))
            }
            val guid = guids.firstOrNull() ?: continue
            val ok = mainViewModel.measureRound(listOf(guid), timeoutMs = 10_000L)
            if (ok != null) {
                withContext(Dispatchers.IO) {
                    CleanIpScanner.rememberWinner(carrier, survivor.ip)
                    CleanIpScanner.setConnectedVia(survivor.ip)
                }
                CleanIpScanner.setQuality(CleanIpScanner.Quality.VERIFIED)
                finishDeepConnect(ok)
                return true
            }
        }

        // ---- nothing measured: commit to the fastest handshake ----------
        val fallback = best.first()
        val guid = withContext(Dispatchers.IO) {
            CleanIpScanner.importAll(template, listOf(fallback))
        }.firstOrNull() ?: return false

        LogUtil.w(
            AppConfig.TAG,
            "DeepConnect: no address passed measurement, committing to the fastest handshake",
        )
        withContext(Dispatchers.IO) {
            CleanIpScanner.rememberWinner(carrier, fallback.ip)
            CleanIpScanner.setConnectedVia(fallback.ip)
        }
        // Nothing proved itself. Mark it so the strongest-scan button appears.
        CleanIpScanner.setQuality(CleanIpScanner.Quality.UNVERIFIED)
        toast(R.string.fn_deep_committed)
        finishDeepConnect(guid)
        return true
    }

    /* ───────────────────── strongest scan ───────────────────── */

    /** Verified addresses to gather before picking the fastest. */
    private val STRONG_TARGET = 5

    /** Once one works, how long to keep looking for a better one. */
    private val STRONG_GRACE_MS = 90_000L

    private var strongScanJob: Job? = null

    fun isStrongScanRunning(): Boolean = strongScanJob?.isActive == true

    fun cancelStrongScan() {
        strongScanJob?.cancel()
        strongScanJob = null
        CleanIpScanner.stop()
    }

    /**
     * FILTERNET: the "it says connected but nothing loads" escape hatch.
     *
     * The ordinary hunt gives up after a while and commits to the fastest
     * handshake, which may be a tunnel that carries nothing. This is the
     * opposite trade: it never commits to anything unproven, and it never
     * stops on its own.
     *
     * Three things make it stronger than the ordinary hunt rather than just
     * longer:
     *
     *  - **Every worker domain, not one.** A carrier can filter one
     *    workers.dev name and leave another alone. The ordinary hunt only
     *    ever tries the most common one.
     *  - **Every Cloudflare TLS port, not one.**
     *  - **Measured one at a time from the start.** Parallel measurement is
     *    what fails on a congested carrier in the first place.
     *
     * The live tunnel is deliberately left up while this runs: the app
     * excludes itself from its own VPN, so the probes are unaffected, and the
     * user keeps whatever they had until there is something better to switch
     * to.
     */
    private fun handleStrongScan() {
        if (isStrongScanRunning()) {
            cancelStrongScan()
            return
        }
        cancelAutoConnect()
        CleanIpScanner.stop()
        // The tunnel this is about to replace is already ours; claiming again
        // is harmless and covers the case where it was started elsewhere.
        FilternetMode.claim(FilternetMode.Owner.INTERNAL)

        strongScanJob = lifecycleScope.launch {
            try {
                CleanIpScanner.beginPreparing()
                val carrier = IrcfSource.carrierName(applicationContext)

                val links = withContext(Dispatchers.IO) {
                    InternalVault.configs().ifEmpty {
                        ServerPoolManager.poolLinks(applicationContext)
                    }
                }
                val templates = withContext(Dispatchers.IO) { CleanIpScanner.allTemplates(links) }
                if (templates.isEmpty()) {
                    toastError(R.string.fn_deep_no_template)
                    return@launch
                }
                LogUtil.i(AppConfig.TAG, "StrongScan: ${templates.size} worker domains in play")

                withContext(Dispatchers.IO) {
                    CleanIpScanner.refreshRanges(applicationContext)
                }
                val memory = withContext(Dispatchers.IO) { CleanIpScanner.winners(carrier) }
                val ircf = withContext(Dispatchers.IO) {
                    IrcfSource.fetch(applicationContext).addresses
                }
                CleanIpScanner.start(
                    template = templates.first(),
                    seeds = memory + ircf,
                    seedIrcf = ircf.size,
                    seedMemory = memory.size,
                    templates = templates,
                    strong = true,
                )

                // Verified winners, fastest kept at the front.
                val found = mutableListOf<Pair<CleanIpScanner.Survivor, String>>()
                var firstHitAt = 0L
                val tried = HashSet<String>()

                while (isActive) {
                    val candidates = withContext(Dispatchers.IO) {
                        CleanIpScanner.bestSurvivors(40)
                            .filter { tried.add("${it.ip}:${it.port}:${it.sni ?: "own"}") }
                    }
                    if (candidates.isEmpty()) {
                        delay(700L)
                        if (shouldSettle(found, firstHitAt)) break
                        continue
                    }

                    for (s in candidates) {
                        if (!isActive) break
                        val guid = withContext(Dispatchers.IO) {
                            CleanIpScanner.importAll(templates.first(), listOf(s))
                        }.firstOrNull() ?: continue

                        // One at a time. The whole uplink to itself.
                        val ok = mainViewModel.measureRound(listOf(guid), timeoutMs = 10_000L)
                        if (ok != null) {
                            found.add(s to ok)
                            if (firstHitAt == 0L) firstHitAt = System.currentTimeMillis()
                            LogUtil.i(AppConfig.TAG, "StrongScan: verified ${found.size}/$STRONG_TARGET")
                        }
                        if (shouldSettle(found, firstHitAt)) break
                    }
                    if (shouldSettle(found, firstHitAt)) break
                }

                val best = found.minByOrNull { it.first.ms } ?: return@launch
                withContext(Dispatchers.IO) {
                    CleanIpScanner.rememberWinner(carrier, best.first.ip)
                    // Show which server name won, not just the address - on a
                    // carrier where the name is the problem, that is the
                    // single most useful thing on the screen.
                    CleanIpScanner.setConnectedVia(CleanIpScanner.describeRoute(best.first))
                }
                CleanIpScanner.setQuality(CleanIpScanner.Quality.VERIFIED)
                toastSuccess(R.string.fn_strong_found)
                finishDeepConnect(best.second)
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StrongScan failed", e)
            } finally {
                CleanIpScanner.stop()
                strongScanJob = null
            }
        }
    }

    /** Enough proven addresses, or long enough since the first one. */
    private fun shouldSettle(
        found: List<Pair<CleanIpScanner.Survivor, String>>,
        firstHitAt: Long,
    ): Boolean {
        if (found.size >= STRONG_TARGET) return true
        return found.isNotEmpty() && System.currentTimeMillis() - firstHitAt >= STRONG_GRACE_MS
    }

    private fun finishDeepConnect(guid: String) {
        CleanIpScanner.stop()
        // Still INTERNAL - selecting the winning scan profile below moves the
        // home tab's pointer, and ownership is what stops it acting on that.
        FilternetMode.claim(FilternetMode.Owner.INTERNAL)
        mainViewModel.selectServerQuietly(guid)
        mainViewModel.onAction(MainAction.RefreshGroups)
        startV2Ray()
    }

    /**
     * FILTERNET: lets the user back out of a search that is taking too long.
     * Cancels the coroutine, the bulk measurement and the amber state.
     */
    private fun cancelAutoConnect() {
        autoConnectJob?.cancel()
        autoConnectJob = null
        mainViewModel.onAction(MainAction.CancelTesting)
        ServerPoolManager.publishIdle()
        CleanIpScanner.reset()
        if (!mainViewModel.uiState.value.isRunning) FilternetMode.release()
    }

    private fun startV2Ray() {
        // FILTERNET: self-healing. The connect button used to do nothing whenever
        // uiState had no selected guid yet (first launch, right after an import, or
        // while the group list was still loading). Now we repair the selection from
        // storage and carry on, and only give up when there is truly no profile.
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            if (!mainViewModel.repairSelectedServer()) {
                toastError(R.string.fn_no_server_msg)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    mainViewModel.onAction(MainAction.ImportBatchConfig(reader.readText()))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
