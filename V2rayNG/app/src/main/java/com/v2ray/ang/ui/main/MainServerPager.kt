package com.v2ray.ang.ui.main

import androidx.compose.material3.Button
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.dto.LocateTarget
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.ui.compose.FilternetGlassBorder
import com.v2ray.ang.ui.compose.FilternetGlassColor
import com.v2ray.ang.ui.compose.ReorderableGridItem
import com.v2ray.ang.ui.compose.ReorderableListItem
import com.v2ray.ang.ui.compose.SignalBars
import com.v2ray.ang.ui.compose.colorConfigType
import com.v2ray.ang.ui.compose.colorTypeHysteria
import com.v2ray.ang.ui.compose.colorTypeOther
import com.v2ray.ang.ui.compose.colorTypeShadowsocks
import com.v2ray.ang.ui.compose.colorTypeTrojan
import com.v2ray.ang.ui.compose.colorTypeVless
import com.v2ray.ang.ui.compose.colorTypeVmess
import com.v2ray.ang.ui.compose.colorTypeWireguard
import com.v2ray.ang.ui.compose.countryFlagFor
import com.v2ray.ang.ui.compose.pingColor
import com.v2ray.ang.ui.compose.verticalScrollbar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs

@Composable
fun GroupPagerPage(
    onAddServer: () -> Unit = {},
    groupId: String,
    mainViewModel: MainViewModel,
    selectedGuid: String?,
    locateTarget: LocateTarget?,
    doubleColumnDisplay: Boolean,
    searchQuery: String,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String, String) -> Unit,
    contentPadding: PaddingValues,
) {
    val groupStateFlow = remember(groupId) { mainViewModel.serverGroupState(groupId) }
    val groupState by groupStateFlow.collectAsStateWithLifecycle()
    val canReorder = groupId.isNotEmpty() && searchQuery.isEmpty()
    val actions = remember(onSelectServer, onMoreServer) {
        ServerRowActions(select = onSelectServer, more = onMoreServer)
    }

    ServerListPage(
        onAddServer = onAddServer,
        rows = groupState.rows,
        selectedGuid = selectedGuid,
        locateTarget = locateTarget?.takeIf { it.groupId == groupId },
        canReorder = canReorder,
        doubleColumnDisplay = doubleColumnDisplay,
        groupId = groupId,
        lazyListStates = lazyListStates,
        lazyGridStates = lazyGridStates,
        actions = actions,
        onLocateHandled = { mainViewModel.onAction(MainAction.LocateHandled) },
        onMoveServer = { fromIndex, toIndex ->
            mainViewModel.moveServer(groupId, fromIndex, toIndex)
        },
        contentPadding = contentPadding,
    )
}

private class ServerRowActions(
    val select: (String) -> Unit,
    val more: (String, ProfileItem) -> Unit,
)

@Composable
private fun ServerListPage(
    onAddServer: () -> Unit,
    rows: List<ServerRowUiModel>,
    selectedGuid: String?,
    locateTarget: LocateTarget?,
    canReorder: Boolean,
    doubleColumnDisplay: Boolean,
    groupId: String,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    actions: ServerRowActions,
    onLocateHandled: () -> Unit,
    onMoveServer: (Int, Int) -> Unit,
    contentPadding: PaddingValues,
) {
    if (rows.isEmpty()) {
        EmptyServerState(onAddServer = onAddServer)
        return
    }

    if (doubleColumnDisplay) {
        val gridState = remember(groupId) {
            lazyGridStates.getOrPut(groupId) { LazyGridState() }
        }
        val reorderableGridState = if (canReorder) {
            rememberReorderableLazyGridState(gridState) { from, to ->
                onMoveServer(from.index, to.index)
            }
        } else null

        LocateTargetEffect(locateTarget, rows, gridState, onLocateHandled)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(gridState),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(rows.distinctBy { it.guid }, key = { _, item -> item.guid }) { _, row ->
                val content: @Composable () -> Unit = {
                    ServerCard(
                        row = row,
                        isSelected = row.guid == selectedGuid,
                        compact = true,
                        actions = actions,
                    )
                }
                if (canReorder && reorderableGridState != null) {
                    ReorderableItem(reorderableGridState, key = row.guid) { isDragging ->
                        ReorderableGridItem(scope = this, isDragging = isDragging) { content() }
                    }
                } else {
                    content()
                }
            }
        }
    } else {
        val listState = remember(groupId) {
            lazyListStates.getOrPut(groupId) { LazyListState() }
        }
        val reorderableState = if (canReorder) {
            rememberReorderableLazyListState(listState) { from, to ->
                onMoveServer(from.index, to.index)
            }
        } else null

        LocateTargetEffect(locateTarget, rows, listState, onLocateHandled)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(listState),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(rows.distinctBy { it.guid }, key = { _, item -> item.guid }) { _, row ->
                if (canReorder && reorderableState != null) {
                    ReorderableItem(reorderableState, key = row.guid) { isDragging ->
                        ReorderableListItem(scope = this, isDragging = isDragging) {
                            ServerCard(
                                row = row,
                                isSelected = row.guid == selectedGuid,
                                compact = false,
                                actions = actions,
                            )
                        }
                    }
                } else {
                    ServerCard(
                        row = row,
                        isSelected = row.guid == selectedGuid,
                        compact = false,
                        actions = actions,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyServerState(onAddServer: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier
                    .size(92.dp)
                    .clickable(onClick = onAddServer),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                ),
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
                Icon(
                    painter = painterResource(R.drawable.ic_add_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.fn_add_server), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ServerCard(
    row: ServerRowUiModel,
    isSelected: Boolean,
    compact: Boolean,
    actions: ServerRowActions,
) {
    val selectionLabel = stringResource(R.string.acc_selected_server)
    val actionLabel = row.remarks
    val delayColor = pingColor(row.testDelayMillis)

    Surface(
        modifier = Modifier
            .padding(horizontal = if (compact) 0.dp else 12.dp)
            .fillMaxWidth()
            .semantics(mergeDescendants = false) {
                selected = isSelected
                role = Role.RadioButton
                contentDescription = if (isSelected) "$actionLabel, $selectionLabel" else actionLabel
            }
            .clickable(role = Role.RadioButton) { actions.select(row.guid) },
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else FilternetGlassColor,
        shape = RoundedCornerShape(22.dp),
        border = if (isSelected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.58f))
        } else {
            FilternetGlassBorder
        },
        shadowElevation = if (isSelected) 8.dp else 0.dp,
    ) {
        Box {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(if (compact) 54.dp else 62.dp)
                    .background(delayColor, CircleShape)
            )
            Row(
                modifier = Modifier.padding(
                    start = if (compact) 10.dp else 14.dp,
                    end = 6.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(if (compact) 38.dp else 46.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = countryFlagFor(row.remarks),
                            fontSize = if (compact) 18.sp else 22.sp,
                        )
                    }
                }

                Spacer(Modifier.width(if (compact) 8.dp else 12.dp))

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = row.remarks,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = if (compact) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Surface(
                                modifier = Modifier.size(17.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_action_done),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(11.dp),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ServerInfoChip(
                            text = row.configType.name,
                            color = protocolColor(row.configType),
                        )
                        row.typeDescription
                            .split("/")
                            .drop(1)
                            .take(if (compact) 1 else 2)
                            .forEach { part ->
                                ServerInfoChip(
                                    text = part.trim(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }

                    if (!compact && row.statistics.isNotBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = row.statistics,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SignalBars(row.testDelayMillis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when {
                            row.testDelayMillis > 0L -> stringResource(
                                R.string.server_test_delay_value,
                                row.testDelayMillis,
                            )
                            row.testDelayMillis < 0L -> stringResource(R.string.toast_failure)
                            else -> "--"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = delayColor,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                // FILTERNET: servers handed out by the app have no overflow menu,
                // so they cannot be copied, shared, exported or shown as a QR code.
                if (!row.isGiftServer) {
                    IconButton(onClick = { actions.more(row.guid, row.profile) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert_24dp),
                            contentDescription = stringResource(R.string.acc_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerInfoChip(text: String, color: androidx.compose.ui.graphics.Color) {
    if (text.isBlank()) return
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun LocateTargetEffect(
    target: LocateTarget?,
    rows: List<ServerRowUiModel>,
    state: LazyListState,
    onHandled: () -> Unit,
) {
    if (target == null) return
    LaunchedEffect(target, rows) {
        val index = rows.indexOfFirst { it.guid == target.serverGuid }
        if (index < 0) return@LaunchedEffect
        state.scrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
        onHandled()
    }
}

@Composable
private fun LocateTargetEffect(
    target: LocateTarget?,
    rows: List<ServerRowUiModel>,
    state: LazyGridState,
    onHandled: () -> Unit,
) {
    if (target == null) return
    LaunchedEffect(target, rows) {
        val index = rows.indexOfFirst { it.guid == target.serverGuid }
        if (index < 0) return@LaunchedEffect
        state.scrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
        onHandled()
    }
}

private fun protocolColor(type: EConfigType) = when (type) {
    EConfigType.VLESS -> colorTypeVless
    EConfigType.VMESS -> colorTypeVmess
    EConfigType.TROJAN -> colorTypeTrojan
    EConfigType.SHADOWSOCKS -> colorTypeShadowsocks
    EConfigType.HYSTERIA2, EConfigType.HYSTERIA -> colorTypeHysteria
    EConfigType.WIREGUARD -> colorTypeWireguard
    EConfigType.SOCKS, EConfigType.HTTP -> colorConfigType
    else -> colorTypeOther
}

internal suspend fun PagerState.navigateToPageOptimized(
    targetPage: Int,
    animateAdjacentPage: Boolean = true,
) {
    if (pageCount <= 0) return
    val target = targetPage.coerceIn(0, pageCount - 1)
    val current = settledPage.coerceIn(0, pageCount - 1)
    if (target == current) return
    if (abs(target - current) == 1 && animateAdjacentPage) {
        animateScrollToPage(target)
    } else {
        scrollToPage(target)
    }
}