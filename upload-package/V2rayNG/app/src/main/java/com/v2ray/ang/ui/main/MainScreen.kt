package com.v2ray.ang.ui.main

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
    val groups = uiState.groups
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

    val currentGroupFlow = remember(uiState.selectedGroupId, mainViewModel) {
        mainViewModel.serverGroupState(uiState.selectedGroupId)
    }
    val currentGroup by currentGroupFlow.collectAsStateWithLifecycle()
    val selectedServer = remember(currentGroup.rows, uiState.selectedGuid) {
        currentGroup.rows.firstOrNull { it.guid == uiState.selectedGuid }
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
                            isRunning = uiState.isRunning,
                            isLoading = isLoading,
                            onMenuClick = { scope.launch { drawerState.open() } },
                            onQuickImport = { onAction(MainAction.ImportQRcode) },
                        )
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
                            servers = currentGroup.rows,
                            displayText = displayText,
                            isRunning = uiState.isRunning,
                            isFindingBest = uiState.isFindingBest,
                            onToggleService = { onAction(MainAction.ToggleService) },
                            onFindBest = { onAction(MainAction.ConnectBestServer) },
                            onCancelFindBest = { onAction(MainAction.CancelTesting) },
                            onOpenServers = { selectedTab = MainRootTab.Servers },
                            onTestCurrent = { onAction(MainAction.TestCurrentServer) },
                        )

                        MainRootTab.Servers -> {
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
                        Text(
                            text = stringResource(
                                if (isRunning) R.string.connection_connected
                                else R.string.connection_not_connected
                            ),
                            style = MaterialTheme.typography.labelSmall,
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