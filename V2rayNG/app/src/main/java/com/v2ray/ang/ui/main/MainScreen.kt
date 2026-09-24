package com.v2ray.ang.ui.main

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
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by rememberSaveable(stateSaver = ServerDeleteTarget.Saver) {
        mutableStateOf<ServerDeleteTarget?>(null)
    }
    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    // FILTERNET: "add a server with…" sheet, reachable from the empty state,
    // the servers-tab FAB and the "no server found" path on the home tab.
    var showAddServer by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val currentGroupFlow = remember(uiState.selectedGroupId, mainViewModel) {
        mainViewModel.serverGroupState(uiState.selectedGroupId)
    }
    val currentGroup by currentGroupFlow.collectAsStateWithLifecycle()
    val selectedServer = remember(currentGroup.rows, uiState.selectedGuid) {
        currentGroup.rows.firstOrNull { it.guid == uiState.selectedGuid }
    }
    // FILTERNET: name of the selected server, resolved even when it belongs to a
    // group other than the one currently on screen.
    val selectedServerName = remember(
        selectedServer,
        uiState.selectedGuid,
        uiState.isRunning,
        currentGroup.rows,
        groups,
    ) {
        selectedServer?.remarks?.takeIf { it.isNotBlank() }
            ?: uiState.selectedGuid
                ?.takeIf { it.isNotEmpty() }
                ?.let { guid ->
                    runCatching { MmkvManager.decodeServerConfig(guid)?.remarks }.getOrNull()
                }?.takeIf { it.isNotBlank() }
            // FILTERNET: last resort, straight from storage. The name at the top used
            // to stay empty whenever uiState.selectedGuid had not been filled in yet.
            ?: mainViewModel.selectedServerNameOrNull()
    }
    // FILTERNET: does the user own any profile at all (in any group)?
    val hasAnyServer = remember(groups, currentGroup.rows, uiState.selectedGuid) {
        currentGroup.rows.isNotEmpty() || mainViewModel.hasAnyServer()
    }

    val removeServer: (String, String) -> Unit = { guid, profileName ->
        if (uiState.confirmRemove) {
            showRemoveConfirm = ServerDeleteTarget(guid, profileName)
        } else {
            onAction(MainAction.RemoveServer(guid))
        }
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) },
    )
    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    LaunchedEffect(groups, uiState.selectedGroupId) {
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (!pagerState.isScrollInProgress && pagerState.settledPage != selectedIndex) {
            pagerState.scrollToPage(selectedIndex)
        }
    }

    val latestGroups by rememberUpdatedState(groups)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
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

    if (showAddServer) {
        AddServerSheet(
            onDismiss = { showAddServer = false },
            onClipboard = {
                showAddServer = false
                onAction(MainAction.ImportClipboard)
            },
            onQrCode = {
                showAddServer = false
                onAction(MainAction.ImportQRcode)
            },
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
                contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
                topBar = {
                    if (selectedTab == MainRootTab.Servers) {
                        MainTopBar(
                            isLoading = isLoading,
                            showSearch = showSearch,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { query ->
                                searchQuery = query
                                onAction(MainAction.Search(query))
                            },
                            onSearchClose = {
                                searchQuery = ""
                                onAction(MainAction.Search(""))
                                showSearch = false
                            },
                            onSearchToggle = { showSearch = it },
                            onMenuClick = { scope.launch { drawerState.open() } },
                            onAction = onAction,
                            onMoreMenuAction = { action ->
                                when (action) {
                                    MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                                    MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                                    MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                                    MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                                    MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                                    MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                                    MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                                    MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                                    MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                                    MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                                }
                            },
                        )
                    } else {
                        MainBrandTopBar(
                            tab = selectedTab,
                            serverName = selectedServerName,
                            isRunning = uiState.isRunning,
                            isLoading = isLoading,
                            onMenuClick = { scope.launch { drawerState.open() } },
                            onQuickImport = { onAction(MainAction.ImportQRcode) },
                        )
                    }
                },
                floatingActionButton = {
                    // FILTERNET: a real, working "add server" button on the servers tab.
                    if (selectedTab == MainRootTab.Servers) {
                        FloatingActionButton(
                            onClick = { showAddServer = true },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape,
                            modifier = Modifier.padding(bottom = 78.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add_24dp),
                                contentDescription = stringResource(R.string.fn_add_server),
                            )
                        }
                    }
                },
                bottomBar = {
                    MainNavigationBar(
                        selectedTab = selectedTab,
                        onSelect = { tab ->
                            selectedTab = tab
                            if (tab != MainRootTab.Servers && showSearch) {
                                showSearch = false
                                searchQuery = ""
                                onAction(MainAction.Search(""))
                            }
                        },
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
                            selectedServer = selectedServer,
                            selectedServerName = selectedServerName,
                            servers = currentGroup.rows,
                            displayText = displayText,
                            isRunning = uiState.isRunning,
                            isFindingBest = uiState.isFindingBest,
                            hasAnyServer = hasAnyServer,
                            onNoServer = {
                                // FILTERNET: tell the user why nothing happened, then take
                                // them straight to the place where they can fix it.
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.fn_no_server_msg),
                                    Toast.LENGTH_LONG,
                                ).show()
                                selectedTab = MainRootTab.Servers
                                showAddServer = true
                            },
                            onToggleService = { onAction(MainAction.ToggleService) },
                            onFindBest = { onAction(MainAction.ConnectBestServer) },
                            onCancelFindBest = { onAction(MainAction.CancelTesting) },
                            onOpenServers = { selectedTab = MainRootTab.Servers },
                            onTestCurrent = { onAction(MainAction.TestCurrentServer) },
                        )

                        MainRootTab.Servers -> {
                            if (groups.isEmpty()) {
                                // FILTERNET: previously this rendered a blank page when the
                                // user had no subscription group yet.
                                NoGroupEmptyState(onAddServer = { showAddServer = true })
                            }
                            if (groups.isNotEmpty()) {
                                Column(Modifier.fillMaxSize()) {
                                    if (groups.size > 1) {
                                        GroupTabBar(
                                            groups = groups,
                                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                                            mainViewModel = mainViewModel,
                                            onTabClick = { targetIndex ->
                                                scope.launch {
                                                    pagerState.navigateToPageOptimized(
                                                        targetPage = targetIndex,
                                                        animateAdjacentPage = true,
                                                    )
                                                }
                                            },
                                        )
                                    }
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxSize(),
                                        userScrollEnabled = true,
                                        beyondViewportPageCount = 1,
                                        key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" },
                                    ) { page ->
                                        val group = groups.getOrNull(page) ?: return@HorizontalPager
                                        GroupPagerPage(
                                            onAddServer = { showAddServer = true },
                                            groupId = group.id,
                                            mainViewModel = mainViewModel,
                                            selectedGuid = uiState.selectedGuid,
                                            locateTarget = uiState.locateTarget,
                                            doubleColumnDisplay = uiState.doubleColumnDisplay,
                                            searchQuery = searchQuery,
                                            lazyListStates = lazyListStates,
                                            lazyGridStates = lazyGridStates,
                                            onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                                            onEditServer = { guid, profile ->
                                                onAction(MainAction.EditServer(guid, profile))
                                            },
                                            onShareServer = { guid, profile ->
                                                shareTarget = Triple(guid, profile, false)
                                            },
                                            onMoreServer = { guid, profile ->
                                                shareTarget = Triple(guid, profile, true)
                                            },
                                            onRemoveServer = removeServer,
                                            contentPadding = PaddingValues(vertical = 10.dp),
                                        )
                                    }
                                }
                            }
                        }

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
    serverName: String?,
    isRunning: Boolean,
    isLoading: Boolean,
    onMenuClick: () -> Unit,
    onQuickImport: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (tab == MainRootTab.Home) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (tab == MainRootTab.Home) {
                        val prefix = stringResource(
                            if (isRunning) R.string.fn_connected_to
                            else R.string.fn_not_connected
                        )
                        Text(
                            text = if (!serverName.isNullOrBlank()) "$prefix · $serverName"
                            else prefix,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isRunning) FilternetTokens.Emerald
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu_24dp),
                        contentDescription = stringResource(R.string.acc_open_menu),
                    )
                }
            },
            actions = {
                if (tab == MainRootTab.Home) {
                    IconButton(onClick = onQuickImport) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add_24dp),
                            contentDescription = stringResource(R.string.menu_item_import_config_qrcode),
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
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
@Composable
private fun NoGroupEmptyState(onAddServer: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier
                    .size(92.dp)
                    .clickable(onClick = onAddServer),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_24dp),
                        contentDescription = stringResource(R.string.fn_add_server),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.fn_no_server_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.fn_no_server_msg),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAddServer, shape = CircleShape) {
                Text(stringResource(R.string.fn_add_server), fontWeight = FontWeight.Bold)
            }
        }
    }
}
