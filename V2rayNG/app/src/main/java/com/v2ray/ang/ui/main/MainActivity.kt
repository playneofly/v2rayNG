package com.v2ray.ang.ui.main

import com.v2ray.ang.handler.InternalVault
import com.v2ray.ang.handler.CleanIpScanner
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
            LauncherManager.stopService(this)
        } else {
            requestServiceStart()
        }
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
        if (autoConnectJob?.isActive == true) return
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
     * FILTERNET: the internal tab's last resort.
     *
     * Takes the backend that most of the pool points at, then hunts Cloudflare's
     * entire address space for an edge that still answers for it. One Worker is
     * reachable through about a million and a half addresses, so this keeps
     * going for as long as the user lets it - there is no attempt limit.
     *
     * Survivors of the TLS screen are imported and then proved for real by the
     * core, exactly like the normal flow, because a handshake alone has already
     * fooled us once.
     */
    private fun handleDeepConnect() {
        if (autoConnectJob?.isActive == true) return
        autoConnectJob = lifecycleScope.launch {
            try {
                val links = withContext(Dispatchers.IO) {
                    val priv = InternalVault.configs()
                    priv.ifEmpty { ServerPoolManager.cachedLinks(applicationContext) }
                }
                val template = CleanIpScanner.bestTemplate(links)
                if (template == null) {
                    LogUtil.w(AppConfig.TAG, "DeepConnect: no scannable config in the pool")
                    CleanIpScanner.publishGaveUp()
                    return@launch
                }

                val ready = CleanIpScanner.hunt(template)
                if (!ready) return@launch

                // Prove them with the core, then connect to the winner.
                mainViewModel.connectBestServer()
                val deadline = System.currentTimeMillis() + MEASURE_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    delay(500L)
                    val s = mainViewModel.uiState.value
                    if (s.isRunning) {
                        CleanIpScanner.publishFound(template.sni)
                        return@launch
                    }
                    if (!s.isFindingBest && !s.isTesting) break
                }
                mainViewModel.onAction(MainAction.CancelTesting)
                CleanIpScanner.publishGaveUp()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "DeepConnect failed", e)
                CleanIpScanner.publishGaveUp()
            }
        }
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
