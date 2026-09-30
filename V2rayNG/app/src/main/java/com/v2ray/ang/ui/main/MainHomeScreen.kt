package com.v2ray.ang.ui.main

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.handler.ServerPoolManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.LiveSpeedStore
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.compose.FilternetAccentBrush
import com.v2ray.ang.ui.compose.FilternetTokens
import com.v2ray.ang.ui.compose.faDigits
import com.v2ray.ang.ui.compose.pingToneColor
import com.v2ray.ang.extension.toSpeedString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/* ═══════════════════════════════════════════════════════════════════════════
   FILTERNET home tab, rebuilt on the new design language:

     · status pill                 · connect core with orbit rings + halos
     · status line + session timer · smart auto select with live scan progress
     · current server card         · three stat cards (down / up / new IP)
     · free server pool CTA
   ═══════════════════════════════════════════════════════════════════════════ */

@Composable
internal fun MainHomeScreen(
    selectedServer: ServerRowUiModel?,
    selectedServerName: String?,
    servers: List<ServerRowUiModel>,
    displayText: String,
    isRunning: Boolean,
    isFindingBest: Boolean,
    onToggleService: () -> Unit,
    onFindBest: () -> Unit,
    onCancelFindBest: () -> Unit,
    onTestCurrent: () -> Unit,
    onAutoConnect: () -> Unit,
) {
    var speed by remember { mutableStateOf(LiveSpeedStore.Sample(0L, 0L, emptyList(), 0L)) }
    var pendingConnection by remember { mutableStateOf(false) }
    var uptimeSeconds by remember { mutableLongStateOf(0L) }
    var publicIp by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // FILTERNET: the button is red and refuses to spin when there is no network.
    val hasInternet by rememberHasInternet()
    val poolPhase by ServerPoolManager.phase.collectAsStateWithLifecycle()

    val busy = pendingConnection ||
        poolPhase is ServerPoolManager.Phase.Downloading ||
        poolPhase is ServerPoolManager.Phase.Scanning

    val coreState = when {
        isRunning -> CoreState.Connected
        busy -> CoreState.Working
        !hasInternet -> CoreState.Offline
        else -> CoreState.Idle
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            speed = LiveSpeedStore.Sample(0L, 0L, emptyList(), 0L)
            uptimeSeconds = 0L
            pendingConnection = false
            publicIp = null
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

    // FILTERNET: the public IP proves to the user that traffic really goes
    // through the tunnel. It is fetched a moment after the handshake settles.
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        delay(2500L)
        publicIp = withContext(Dispatchers.IO) { PublicIpProbe.lookup() }
    }

    if (isFindingBest) {
        BestServerSheet(servers = servers, onCancel = onCancelFindBest)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPill(state = coreState)

        Spacer(Modifier.height(4.dp))

        ConnectCore(
            state = coreState,
            onClick = {
                // FILTERNET: one button, four states. Offline does nothing but
                // say so; otherwise we either stop, or hand over to the
                // automatic "find a server and connect" flow.
                if (coreState == CoreState.Offline) return@ConnectCore
                if (isRunning) {
                    onToggleService()
                    return@ConnectCore
                }
                if (busy) return@ConnectCore
                pendingConnection = true
                onAutoConnect()
                scope.launch {
                    delay(45000L)
                    pendingConnection = false
                }
            },
        )

        Spacer(Modifier.height(6.dp))

        StatusLine(
            state = coreState,
            uptimeSeconds = uptimeSeconds,
            phase = poolPhase,
        )

        Spacer(Modifier.height(14.dp))

        SmartConnectButton(
            isScanning = busy,
            onClick = { if (coreState != CoreState.Offline) onFindBest() },
        )

        Spacer(Modifier.height(10.dp))

        CurrentServerCard(
            server = selectedServer,
            fallbackName = selectedServerName,
        )

        Spacer(Modifier.height(10.dp))

        StatCardsRow(
            isRunning = isRunning,
            sample = speed,
            publicIp = publicIp,
            statusText = displayText,
            onStatusClick = onTestCurrent,
        )

        Spacer(Modifier.height(16.dp))
    }
}

/* ════════════════════════════ core state ════════════════════════════ */

/**
 * FILTERNET: the four states of the big button.
 *
 *   Offline   red    - no internet at all, pressing it is pointless
 *   Idle      grey   - ready, waiting for a tap
 *   Working   amber  - searching for a server / handshaking
 *   Connected green  - tunnel is up
 */
internal enum class CoreState { Offline, Idle, Working, Connected }

private val CoreState.tint: Color
    get() = when (this) {
        CoreState.Offline -> FilternetTokens.Rose
        CoreState.Idle -> FilternetTokens.Accent
        CoreState.Working -> FilternetTokens.Amber
        CoreState.Connected -> FilternetTokens.Mint
    }

/* ════════════════════════════ status pill ════════════════════════════ */

@Composable
private fun StatusPill(state: CoreState) {
    val tint = state.tint
    val neutral = state == CoreState.Idle
    val bg = if (neutral) MaterialTheme.colorScheme.surfaceContainerHighest
    else tint.copy(alpha = 0.12f)
    val fg = if (neutral) MaterialTheme.colorScheme.onSurfaceVariant else tint

    val transition = rememberInfiniteTransition(label = "pill")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Surface(color = bg, contentColor = fg, shape = CircleShape) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .scale(if (state == CoreState.Connected || state == CoreState.Working) pulse else 1f)
                    .background(if (neutral) MaterialTheme.colorScheme.outline else tint, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    when (state) {
                        CoreState.Offline -> R.string.fn_state_offline
                        CoreState.Working -> R.string.fn_state_searching
                        CoreState.Connected -> R.string.fn_state_secure
                        CoreState.Idle -> R.string.fn_state_off
                    }
                ),
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
    }
}

/* ════════════════════════════ connect core ════════════════════════════ */

@Composable
private fun ConnectCore(state: CoreState, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "core")
    val slowSpin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing)),
        label = "slowSpin",
    )
    val fastSpin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "fastSpin",
    )
    val halo by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing)),
        label = "halo",
    )

    val connected = state == CoreState.Connected
    val working = state == CoreState.Working
    val offline = state == CoreState.Offline
    val tint = state.tint
    val ringIdle = MaterialTheme.colorScheme.outlineVariant

    // The filled button uses a gradient when connected, a flat colour otherwise.
    val fillStart = if (connected) FilternetTokens.Mint else tint
    val fillEnd = if (connected) FilternetTokens.Accent else tint

    Box(modifier = Modifier.size(248.dp), contentAlignment = Alignment.Center) {
        if (connected) {
            Canvas(Modifier.fillMaxSize()) {
                val base = size.minDimension / 2f
                for (i in 0..1) {
                    val p = ((halo + i * 0.5f) % 1f)
                    drawCircle(
                        color = (if (i == 0) FilternetTokens.Mint else FilternetTokens.Accent)
                            .copy(alpha = (1f - p) * 0.45f),
                        radius = base * (0.56f + p * 0.42f),
                        style = Stroke(width = (2f - p).coerceAtLeast(0.6f).dp.toPx()),
                    )
                }
            }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .rotate(if (working) fastSpin else slowSpin)
        ) {
            drawCircle(
                color = if (offline) FilternetTokens.Rose.copy(alpha = 0.35f)
                else if (connected || working) tint.copy(alpha = 0.45f)
                else ringIdle,
                radius = size.minDimension / 2f - 4.dp.toPx(),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 10.dp.toPx())),
                ),
            )
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .padding(10.dp)
                .rotate(-slowSpin * 1.6f)
        ) {
            drawCircle(
                color = if (connected) FilternetTokens.Accent.copy(alpha = 0.5f)
                else ringIdle.copy(alpha = 0.5f),
                radius = size.minDimension / 2f,
                style = Stroke(
                    width = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 14.dp.toPx())),
                ),
            )
        }

        if (working) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .padding(18.dp)
                    .rotate(fastSpin)
            ) {
                drawArc(
                    brush = Brush.linearGradient(
                        listOf(FilternetTokens.Amber, FilternetTokens.Accent2)
                    ),
                    startAngle = 0f,
                    sweepAngle = 96f,
                    useCenter = false,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        val filled = connected || working || offline

        Surface(
            modifier = Modifier
                .size(148.dp)
                .clip(CircleShape)
                .clickable(enabled = !working, onClick = onClick),
            shape = CircleShape,
            color = if (filled) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (filled) Color.White else MaterialTheme.colorScheme.onSurface,
            border = if (filled) null
            else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = if (filled) 0.dp else 6.dp,
        ) {
            Box(
                modifier = Modifier.then(
                    if (filled) Modifier.background(
                        Brush.linearGradient(listOf(fillStart, fillEnd))
                    ) else Modifier
                ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painter = painterResource(
                            if (offline) R.drawable.ic_language_24dp
                            else R.drawable.ic_power_settings_new_24dp
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = if (filled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            when (state) {
                                CoreState.Offline -> R.string.fn_core_offline
                                CoreState.Connected -> R.string.fn_core_on
                                else -> R.string.fn_core_off
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (filled) Color.White.copy(alpha = 0.92f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ════════════════════════════ status line ════════════════════════════ */

@Composable
private fun StatusLine(
    state: CoreState,
    uptimeSeconds: Long,
    phase: ServerPoolManager.Phase,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(
                when (state) {
                    CoreState.Offline -> R.string.fn_line_offline
                    CoreState.Connected -> R.string.fn_line_protected
                    CoreState.Working -> R.string.fn_line_wait
                    CoreState.Idle -> R.string.fn_line_tap
                }
            ),
            style = MaterialTheme.typography.titleMedium,
            color = if (state == CoreState.Offline) FilternetTokens.Rose
            else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            // While hunting we show live progress, which is the difference
            // between "it froze" and "it is working for me".
            text = when {
                state == CoreState.Connected -> faDigits(formatUptime(uptimeSeconds))
                phase is ServerPoolManager.Phase.Scanning -> stringResource(
                    R.string.fn_scan_wave,
                    faDigits(phase.checked),
                    faDigits(phase.found),
                )
                phase is ServerPoolManager.Phase.Downloading -> stringResource(R.string.fn_gift_downloading)
                phase is ServerPoolManager.Phase.Failed -> stringResource(
                    when (phase.reason) {
                        ServerPoolManager.Reason.NONE_WORKING -> R.string.fn_scan_fail_none
                        else -> R.string.fn_scan_fail_net
                    }
                )
                state == CoreState.Working -> "HANDSHAKE…"
                state == CoreState.Offline -> ""
                else -> "SECURE · PRIVATE · FAST"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (phase is ServerPoolManager.Phase.Failed) FilternetTokens.Rose
            else MaterialTheme.colorScheme.outline,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/* ══════════════════════ smart connect button ══════════════════════ */

@Composable
private fun SmartConnectButton(isScanning: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FilternetTokens.RadiusMedium))
            .clickable(enabled = !isScanning, onClick = onClick),
        shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier.background(FilternetAccentBrush),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier.padding(vertical = 14.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_bolt_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.fn_pick_best),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
            }
        }
    }
}

/* ══════════════════════ current server card ══════════════════════ */

@Composable
private fun CurrentServerCard(
    server: ServerRowUiModel?,
    fallbackName: String?,
) {
    val name = server?.remarks?.takeIf { it.isNotBlank() }
        ?: fallbackName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.fn_no_server_selected)
    val delay = server?.testDelayMillis ?: 0L
    val gradient = FilternetTokens.gradientFor(name)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FilternetTokens.RadiusLarge)),
        shape = RoundedCornerShape(FilternetTokens.RadiusLarge),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(gradient.first, gradient.second))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = serverInitials(name),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.fn_ping_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (delay > 0L) faDigits(delay) else "—",
                        style = MaterialTheme.typography.labelMedium,
                        color = pingToneColor(delay),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = stringResource(R.string.fn_ms),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (server?.typeDescription?.isNotBlank() == true) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = server.typeDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun serverInitials(name: String): String {
    val cleaned = name.trim()
    if (cleaned.isEmpty()) return "?"
    val latin = cleaned.filter { it in 'a'..'z' || it in 'A'..'Z' }
    if (latin.length >= 2) return latin.take(2).uppercase()
    return cleaned.take(2)
}

/* ════════════════════════════ stat cards ════════════════════════════ */

@Composable
private fun StatCardsRow(
    isRunning: Boolean,
    sample: LiveSpeedStore.Sample,
    publicIp: String?,
    statusText: String,
    onStatusClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.fn_speed_down),
            value = if (isRunning) faDigits(sample.down.toSpeedString()) else faDigits(0L.toSpeedString()),
            tint = FilternetTokens.Accent,
            iconRes = R.drawable.ic_arrow_downward_24dp,
            active = isRunning,
            history = if (isRunning) sample.history else emptyList(),
        )
        StatCard(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.fn_speed_up),
            value = if (isRunning) faDigits(sample.up.toSpeedString()) else faDigits(0L.toSpeedString()),
            tint = FilternetTokens.Accent2,
            iconRes = R.drawable.ic_arrow_upward_24dp,
            active = isRunning,
            history = emptyList(),
        )
        StatCard(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(FilternetTokens.RadiusLarge))
                .clickable(enabled = isRunning, onClick = onStatusClick),
            label = stringResource(R.string.fn_new_ip),
            value = when {
                !isRunning -> "—"
                publicIp != null -> publicIp
                else -> "…"
            },
            hint = statusText.takeIf { isRunning && it.isNotBlank() },
            tint = FilternetTokens.Mint,
            iconRes = R.drawable.ic_language_24dp,
            active = isRunning,
            history = emptyList(),
            ltr = true,
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    hint: String? = null,
    tint: Color,
    iconRes: Int,
    active: Boolean,
    history: List<Long>,
    ltr: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(FilternetTokens.RadiusLarge),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = tint,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontSize = if (ltr) 13.sp else 15.sp,
                color = if (active) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.5.sp,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                SpeedSparkline(
                    values = history,
                    color = tint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp),
                )
            }
        }
    }
}

@Composable
private fun SpeedSparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val maxValue = max(1L, values.maxOrNull() ?: 1L).toFloat()
        val step = size.width / (values.size - 1)
        val path = Path()
        val fill = Path()
        values.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - value / maxValue * size.height * 0.9f
            if (index == 0) {
                path.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y)
            } else {
                path.lineTo(x, y); fill.lineTo(x, y)
            }
        }
        fill.lineTo(size.width, size.height)
        fill.close()
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)),
        )
        drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
    }
}

/* ══════════════════════ best server scan sheet ══════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BestServerSheet(
    servers: List<ServerRowUiModel>,
    onCancel: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // FILTERNET: duplicate keys crash a LazyColumn instantly, and fillMaxWidth
    // throws on a fraction outside 0..1. Both are made impossible here.
    val safeServers = remember(servers) { servers.distinctBy { it.guid } }
    val completed = safeServers.count { it.testDelayMillis != 0L }
    val total = safeServers.size.coerceAtLeast(1)
    val progress = (completed.toFloat() / total).coerceIn(0f, 1f)

    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RadarGraphic(progress = progress)

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.fn_scan_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.fn_scan_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(FilternetAccentBrush)
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = stringResource(
                    R.string.fn_scan_counter,
                    faDigits(completed),
                    faDigits(safeServers.size),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FilternetTokens.RadiusMedium))
                    .clickable(onClick = onCancel),
                shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Box(Modifier.padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.fn_scan_cancel),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarGraphic(progress: Float) {
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "sweep",
    )
    val ring = MaterialTheme.colorScheme.outlineVariant
    val accent = FilternetTokens.Accent
    val accent2 = FilternetTokens.Accent2

    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            for (i in 1..3) {
                drawCircle(color = ring, radius = r * i / 3f, style = Stroke(1.dp.toPx()))
            }
            drawArc(
                brush = Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.55f), Color.Transparent)),
                startAngle = sweep,
                sweepAngle = 80f,
                useCenter = true,
            )
            drawCircle(
                brush = Brush.linearGradient(listOf(accent, accent2)),
                radius = r * 0.16f,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
        Text(
            text = faDigits("${(progress * 100).toInt()}٪"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 46.dp),
        )
    }
}

private fun formatUptime(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
