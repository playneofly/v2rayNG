package com.v2ray.ang.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.handler.LiveSpeedStore
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.FilternetAccentBrush
import com.v2ray.ang.ui.compose.FilternetCardShape
import com.v2ray.ang.ui.compose.FilternetGlassBorder
import com.v2ray.ang.ui.compose.FilternetGlassColor
import com.v2ray.ang.ui.compose.FilternetTokens
import com.v2ray.ang.ui.compose.SignalBars
import com.v2ray.ang.ui.compose.countryFlagFor
import com.v2ray.ang.ui.compose.pingColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@Composable
internal fun MainHomeScreen(
    selectedServer: ServerRowUiModel?,
    selectedServerName: String?,
    servers: List<ServerRowUiModel>,
    displayText: String,
    isRunning: Boolean,
    isFindingBest: Boolean,
    hasAnyServer: Boolean,
    onNoServer: () -> Unit,
    onToggleService: () -> Unit,
    onFindBest: () -> Unit,
    onCancelFindBest: () -> Unit,
    onOpenServers: () -> Unit,
    onTestCurrent: () -> Unit,
) {
    var speed by remember { mutableStateOf(LiveSpeedStore.Sample(0L, 0L, emptyList(), 0L)) }
    var pendingConnection by remember { mutableStateOf(false) }
    var uptimeSeconds by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            speed = LiveSpeedStore.Sample(0L, 0L, emptyList(), 0L)
            uptimeSeconds = 0L
            pendingConnection = false
            return@LaunchedEffect
        }
        pendingConnection = false
        while (true) {
            speed = withContext(Dispatchers.IO) { LiveSpeedStore.read() }
            val batterySaver = withContext(Dispatchers.IO) {
                MmkvManager.decodeSettingsBool(AppConfig.PREF_FN_BATTERY_SAVER) == true
            }
            delay(if (batterySaver) 5000L else 1000L)
        }
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            delay(1000L)
            uptimeSeconds++
        }
    }

    if (isFindingBest) {
        BestServerSheet(
            servers = servers,
            onCancel = onCancelFindBest,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ActiveServerPanel(
            server = selectedServer,
            fallbackName = selectedServerName,
            isRunning = isRunning,
            onClick = onOpenServers,
        )

        Spacer(Modifier.height(12.dp))

        PowerOrb(
            isRunning = isRunning,
            isConnecting = pendingConnection,
            uptimeSeconds = uptimeSeconds,
            trafficIntensity = (speed.down / 2_000_000f).coerceIn(0f, 1f),
            onClick = {
                // FILTERNET: without this the button looked completely dead when the
                // user had not imported any server yet.
                if (!hasAnyServer) {
                    onNoServer()
                    return@PowerOrb
                }
                pendingConnection = !isRunning
                onToggleService()
                if (!isRunning) {
                    scope.launch {
                        delay(6000L)
                        pendingConnection = false
                    }
                }
            },
        )

        // FILTERNET: the "Connected. Tap to check connection." line under the orb
        // was noise. The same information now lives in the traffic panel below,
        // where tapping it actually runs the connectivity test.
        Spacer(Modifier.height(8.dp))

        SmartConnectButton(onClick = { if (hasAnyServer) onFindBest() else onNoServer() })

        Spacer(Modifier.height(12.dp))

        LiveTrafficPanel(
            sample = speed,
            isRunning = isRunning,
            statusText = displayText,
            onStatusClick = onTestCurrent,
        )

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ActiveServerPanel(
    server: ServerRowUiModel?,
    fallbackName: String?,
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    val ping = server?.testDelayMillis ?: 0L
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = FilternetGlassColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = FilternetCardShape,
        border = FilternetGlassBorder,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(countryFlagFor(server?.remarks ?: fallbackName.orEmpty()), fontSize = 23.sp)
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .size(13.dp)
                        .background(
                            if (isRunning) FilternetTokens.Emerald else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        )
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    // FILTERNET: the selected server's name, right next to the ping.
                    // An explicit colour so it can never fall back to black-on-black.
                    text = server?.remarks?.takeIf { it.isNotBlank() }
                        ?: fallbackName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.fn_no_server_selected),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ProtocolChip(server?.configType?.name.orEmpty(), accent = true)
                    server?.typeDescription
                        ?.split("/")
                        ?.drop(1)
                        ?.take(2)
                        ?.forEach { ProtocolChip(it.trim()) }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SignalBars(ping)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (ping > 0L) {
                        stringResource(R.string.server_test_delay_value, ping)
                    } else {
                        "--"
                    },
                    color = pingColor(ping),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun ProtocolChip(text: String, accent: Boolean = false) {
    if (text.isBlank()) return
    Surface(
        shape = CircleShape,
        color = if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
        border = BorderStroke(
            1.dp,
            if (accent) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = if (accent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun PowerOrb(
    isRunning: Boolean,
    isConnecting: Boolean,
    uptimeSeconds: Long,
    trafficIntensity: Float,
    onClick: () -> Unit,
) {
    val infinite = rememberInfiniteTransition(label = "power-orb")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnecting) 1200 else 7000),
            repeatMode = RepeatMode.Restart,
        ),
        label = "power-orb-rotation",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600),
            repeatMode = RepeatMode.Restart,
        ),
        label = "power-orb-pulse",
    )
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(2600)),
        label = "power-orb-pulse-alpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (isRunning) 1.02f else 1f,
        animationSpec = tween(650),
        label = "power-orb-scale",
    )
    val haptics = LocalHapticFeedback.current
    val actionLabel = stringResource(if (isRunning) R.string.acc_stop else R.string.acc_start)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier.size(252.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isRunning) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .size(190.dp)
                        .scale(pulse + index * 0.08f)
                        .alpha((pulseAlpha - index * 0.1f).coerceAtLeast(0f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            CircleShape,
                        )
                )
            }
        }

        Canvas(
            modifier = Modifier
                .size(226.dp)
                .rotate(rotation),
        ) {
            val brush = if (isConnecting) {
                Brush.sweepGradient(
                    listOf(Color.Transparent, FilternetTokens.Amber, Color.Transparent)
                )
            } else {
                Brush.sweepGradient(
                    listOf(
                        Color.Transparent,
                        secondary,
                        primary,
                        Color.Transparent,
                    )
                )
            }
            drawCircle(
                brush = brush,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        Box(
            modifier = Modifier
                .size(204.dp)
                .scale(scale)
                .drawBehind {
                    if (isRunning) {
                        drawCircle(
                            color = FilternetTokens.Violet.copy(alpha = 0.13f + trafficIntensity * 0.16f),
                            radius = size.minDimension * (0.48f + trafficIntensity * 0.08f),
                        )
                    }
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (isRunning) 0.23f else 0.08f),
                            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                        )
                    )
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                .clickable(
                    role = Role.Button,
                    onClickLabel = actionLabel,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onClick()
                }
                .semantics {
                    contentDescription = actionLabel
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier.size(58.dp),
                    shape = CircleShape,
                    color = if (isRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = if (isRunning) 14.dp else 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(
                                if (isRunning) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp
                            ),
                            contentDescription = null,
                            tint = if (isRunning) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(27.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                AnimatedContent(
                    targetState = when {
                        isConnecting -> stringResource(R.string.connection_test_testing)
                        isRunning -> stringResource(R.string.connection_connected)
                        else -> stringResource(R.string.connection_not_connected)
                    },
                    label = "connection-status",
                ) { value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isConnecting -> FilternetTokens.Amber
                            isRunning -> FilternetTokens.Emerald
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isRunning) formatUptime(uptimeSeconds) else "00:00:00",
                    style = MaterialTheme.typography.titleMedium,
                    letterSpacing = 1.sp,
                    color = if (isRunning) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
private fun SmartConnectButton(onClick: () -> Unit) {
    val label = stringResource(R.string.fn_btn_best)
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(FilternetAccentBrush, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) {
        BoltGlyph(
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.fn_btn_test_real),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.74f),
            maxLines = 1,
        )
    }
}

@Composable
private fun BoltGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.58f, 0f)
            lineTo(size.width * 0.17f, size.height * 0.56f)
            lineTo(size.width * 0.48f, size.height * 0.56f)
            lineTo(size.width * 0.38f, size.height)
            lineTo(size.width * 0.84f, size.height * 0.4f)
            lineTo(size.width * 0.54f, size.height * 0.4f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun LiveTrafficPanel(
    sample: LiveSpeedStore.Sample,
    isRunning: Boolean,
    statusText: String,
    onStatusClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = FilternetGlassColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = FilternetCardShape,
        border = FilternetGlassBorder,
    ) {
        Column(Modifier.padding(top = 14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SpeedMetric(
                    label = stringResource(R.string.fn_speed_down),
                    value = if (isRunning) sample.down.toSpeedString() else 0L.toSpeedString(),
                    color = MaterialTheme.colorScheme.secondary,
                    arrow = "↓",
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                )
                SpeedMetric(
                    label = stringResource(R.string.fn_speed_up),
                    value = if (isRunning) sample.up.toSpeedString() else 0L.toSpeedString(),
                    color = MaterialTheme.colorScheme.primary,
                    arrow = "↑",
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                )
                // FILTERNET: this block is now genuinely tappable and runs the
                // connectivity test, and it shows the live result instead of the
                // old "tap to check" sentence that did nothing.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = isRunning, onClick = onStatusClick)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        text = when {
                            !isRunning -> stringResource(R.string.connection_not_connected)
                            statusText.isNotBlank() -> statusText
                            else -> stringResource(R.string.fn_status_connected)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isRunning) FilternetTokens.Emerald
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SpeedSparkline(
                values = if (isRunning) sample.history else emptyList(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(70.dp),
            )
        }
    }
}

@Composable
private fun SpeedMetric(label: String, value: String, color: Color, arrow: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$arrow $label",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun SpeedSparkline(values: List<Long>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.secondary
    Canvas(modifier = modifier) {
        repeat(3) { index ->
            val y = size.height * (index + 1) / 4f
            drawLine(
                color = Color.White.copy(alpha = 0.045f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (values.size < 2) return@Canvas
        val maxValue = max(1L, values.maxOrNull() ?: 1L).toFloat()
        val step = size.width / (values.size - 1)
        val path = Path()
        val fill = Path()
        values.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - value / maxValue * size.height * 0.9f
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.3f), Color.Transparent)),
        )
        drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BestServerSheet(
    servers: List<ServerRowUiModel>,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // FILTERNET: LazyColumn crashes hard on a duplicate item key, and fillMaxWidth
    // crashes on a fraction outside 0..1. Both are now impossible here.
    val safeServers = remember(servers) { servers.distinctBy { it.guid } }
    val completed = safeServers.count { it.testDelayMillis != 0L }
    val progress = if (safeServers.isEmpty()) 0f
    else (completed.toFloat() / safeServers.size).coerceIn(0f, 1f)
    val ranked = safeServers.sortedWith(
        compareBy<ServerRowUiModel> { it.testDelayMillis <= 0L }
            .thenBy { if (it.testDelayMillis > 0L) it.testDelayMillis else Long.MAX_VALUE }
    )

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(560.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.fn_best_searching),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.connection_running_task_left, "$completed/${safeServers.size}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            RadarGraphic(
                servers = safeServers,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 12.dp)
                    .size(140.dp),
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(FilternetAccentBrush, CircleShape)
                )
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(ranked, key = { it.guid }) { server ->
                    ScanResultRow(server)
                }
            }
        }
    }
}

@Composable
private fun RadarGraphic(
    servers: List<ServerRowUiModel>,
    modifier: Modifier = Modifier,
) {
    val infinite = rememberInfiniteTransition(label = "server-radar")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1500)),
        label = "server-radar-rotation",
    )
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val accent = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        listOf(0.28f, 0.58f, 0.92f).forEach { fraction ->
            drawCircle(line, radius = size.minDimension / 2f * fraction, style = Stroke(1.dp.toPx()))
        }
        drawLine(line, Offset(center.x, 0f), Offset(center.x, size.height), 1.dp.toPx())
        drawLine(line, Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx())
        rotateDrawScope(rotation) {
            drawArc(
                brush = Brush.sweepGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)),
                startAngle = 0f,
                sweepAngle = 82f,
                useCenter = true,
                topLeft = Offset.Zero,
                size = size,
            )
        }
        servers.filter { it.testDelayMillis > 0L }.forEachIndexed { index, server ->
            val angle = index.toDouble() / max(1, servers.size).toDouble() * PI * 2.0
            val distance = (server.testDelayMillis.coerceAtMost(800L) / 800f) * size.minDimension * 0.42f
            val point = Offset(
                center.x + cos(angle).toFloat() * distance,
                center.y + sin(angle).toFloat() * distance,
            )
            drawCircle(FilternetTokens.Emerald, 3.dp.toPx(), point)
            drawCircle(FilternetTokens.Emerald.copy(alpha = 0.18f), 7.dp.toPx(), point)
        }
    }
}

@Composable
private fun ScanResultRow(server: ServerRowUiModel) {
    val delay = server.testDelayMillis
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (delay > 0L) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.68f)
        else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(countryFlagFor(server.remarks), fontSize = 20.sp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.remarks,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server.typeDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            AnimatedVisibility(
                visible = delay != 0L,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (delay > 0L) {
                            stringResource(R.string.server_test_delay_value, delay)
                        } else {
                            stringResource(R.string.toast_failure)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (delay > 0L) pingColor(delay) else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    SignalBars(delay)
                }
            }
            if (delay == 0L) {
                Box(
                    Modifier
                        .width(48.dp)
                        .height(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            }
        }
    }
}

private fun formatUptime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}