package com.v2ray.ang.ui.main

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.v2ray.ang.handler.MmkvManager
import androidx.compose.ui.text.style.TextOverflow
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.ui.compose.FilternetTokens
import com.v2ray.ang.ui.compose.QRCodeDialog
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    // FILTERNET: duplicate group ids would crash the pager on a duplicate key.
    val groups = remember(uiState.groups) { uiState.groups.distinctBy { it.id } }
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val displayText = mainViewModel.formatStatus(uiState.status)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(MainRootTab.Home) }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }
    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val context = LocalContext.current

    val currentGroupFlow = remember(uiState.selectedGroupId, mainViewModel) {
        mainViewModel.serverGroupState(uiState.selectedGroupId)
    }
    val currentGroup by currentGroupFlow.collectAsStateWithLifecycle()

    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (uiState.confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = {
            showDelAllConfirm = false
            onAction(MainAction.RemoveAllServers)
        },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = {
            showDelDuplicateConfirm = false
            onAction(MainAction.RemoveDuplicateServers)
        },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = {
            showDelInvalidConfirm = false
            onAction(MainAction.RemoveInvalidServers)
        },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid ->
            showRemoveConfirm = null
            onAction(MainAction.RemoveServer(guid))
        },
    )

    shareTarget?.let { (guid, profile, more) ->
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }

    uiState.shareQRCodeBitmap?.let { bitmap ->
        QRCodeDialog(
            bitmap = bitmap,
            onDismiss = { onAction(MainAction.DismissQRCodeDialog) },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MainDrawerContent(
                drawerState = drawerState,
                onNavigate = { destination ->
                    scope.launch { drawerState.close() }
                    onNavigate(destination)
                },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .drawBehind {
                    drawCircle(
                        color = FilternetTokens.Violet.copy(
                            alpha = if (uiState.isRunning) 0.19f else 0.1f
                        ),
                        radius = size.width * 0.72f,
                        center = Offset(size.width * 0.5f, -size.width * 0.08f),
                    )
                    drawCircle(
                        color = FilternetTokens.Cyan.copy(alpha = 0.07f),
                        radius = size.width * 0.6f,
                        center = Offset(0f, size.height * 0.72f),
                    )
                },
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
                topBar = {
                    MainBrandTopBar(
                        tab = selectedTab,
                        isRunning = uiState.isRunning,
                        isLoading = isLoading,
                        onMenuClick = { scope.launch { drawerState.open() } },
                    )
                },
                bottomBar = {
                    MainNavigationBar(
                        selectedTab = selectedTab,
                        onSelect = { tab -> selectedTab = tab },
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    when (selectedTab) {
                        MainRootTab.Home -> MainHomeScreen(
                            displayText = displayText,
                            isRunning = uiState.isRunning,
                            isMeasuring = uiState.isFindingBest || uiState.isTesting,
                            onToggleService = { onAction(MainAction.ToggleService) },
                            onTestCurrent = { onAction(MainAction.TestCurrentServer) },
                            onAutoConnect = { onAction(MainAction.AutoConnect) },
                            onCancelAutoConnect = { onAction(MainAction.CancelAutoConnect) },
                        )

                        MainRootTab.Internal -> MainInternalScreen(
                            isRunning = uiState.isRunning,
                            onDeepConnect = { onAction(MainAction.DeepConnect) },
                            onCancel = { onAction(MainAction.CancelAutoConnect) },
                            onDisconnect = { onAction(MainAction.ToggleService) },
                        )

                        MainRootTab.Stats -> MainTrafficScreen()
                        MainRootTab.Settings -> MainSettingsHub(onNavigate)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainBrandTopBar(
    tab: MainRootTab,
    isRunning: Boolean,
    isLoading: Boolean,
    onMenuClick: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                // FILTERNET: on the home tab the brand wordmark is the title, with
                // "NET" in the accent gradient, exactly like the new design. The
                // other tabs draw their own big heading, so the bar stays empty.
                if (tab == MainRootTab.Home) {
                    Column {
                        Text(
                            text = buildAnnotatedString {
                                withStyle(
                                    SpanStyle(color = MaterialTheme.colorScheme.onSurface)
                                ) { append("FILTER") }
                                withStyle(
                                    SpanStyle(
                                        brush = Brush.linearGradient(
                                            listOf(FilternetTokens.Accent, FilternetTokens.Accent2)
                                        )
                                    )
                                ) { append("NET") }
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        // FILTERNET: the server is an implementation detail now -
                        // the user never chooses one, so its name is never shown.
                        Text(
                            text = stringResource(R.string.fn_brand_tagline),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isRunning) FilternetTokens.Mint
                            else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            },
            // FILTERNET: no hamburger on the home tab - everything it offered is
            // reachable from the bottom navigation and the settings tab. It stays
            // on the other tabs so the drawer is never unreachable.
            navigationIcon = {
                if (tab != MainRootTab.Home) {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_menu_24dp),
                            contentDescription = stringResource(R.string.acc_open_menu),
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            ),
        )
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}
/** FILTERNET: shown on the servers tab before any subscription group exists. */

