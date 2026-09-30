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
    displayText: String,
    isRunning: Boolean,
    isMeasuring: Boolean,
    onToggleService: () -> Unit,
    onTestCurrent: () -> Unit,
    onAutoConnect: () -> Unit,
    onCancelAutoConnect: () -> Unit,
) {
    var speed by remember { mutableStateOf(LiveSpeedStore.Sample(0L, 0L, emptyList(), 0L)) }
    var pendingConnection by remember { mutableStateOf(false) }
    var uptimeSeconds by remember { mutableLongStateOf(0L) }
    var publicIp by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // FILTERNET: the button is red and refuses to spin when there is no network.
    val hasInternet by rememberHasInternet()
    val poolPhase by ServerPoolManager.phase.collectAsStateWithLifecycle()

    // FILTERNET: amber until the whole hunt is over - screening, the core's real
    // measurement, and the single tunnel start. Never green half way through.
    val busy = pendingConnection ||
        isMeasuring ||
        poolPhase is ServerPoolManager.Phase.Downloading ||
        poolPhase is ServerPoolManager.Phase.Scanning ||
        poolPhase is ServerPoolManager.Phase.Measuring

    // FILTERNET: a live tunnel wins over everything. Measurement now happens
    // BEFORE the tunnel is ever started, so if the service is running the hunt
    // is genuinely over - keeping it amber here is what made the button stick.
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

    // FILTERNET: the short-lived "I just tapped" flag only has to cover the gap
    // before the real flow reports in. Anything conclusive clears it at once.
    LaunchedEffect(poolPhase, isMeasuring, isRunning) {
        if (isRunning ||
            isMeasuring ||
            poolPhase is ServerPoolManager.Phase.Failed ||
            poolPhase is ServerPoolManager.Phase.Ready
        ) {
            pendingConnection = false
        }
    }

    // FILTERNET: belt and braces against a stuck amber button. Once the tunnel
    // is up, or once the measurement has finished without one, the pool phase
    // must go back to neutral - otherwise "measuring" latches forever.
    LaunchedEffect(isRunning) {
        if (isRunning) ServerPoolManager.publishIdle()
    }
    LaunchedEffect(isMeasuring) {
        if (!isMeasuring && poolPhase is ServerPoolManager.Phase.Measuring) {
            delay(1500L)
            if (!isMeasuring) ServerPoolManager.publishIdle()
        }
    }

    // FILTERNET: the public IP proves to the user that traffic really goes
    // through the tunnel. It is fetched a moment after the handshake settles.
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        delay(2500L)
        publicIp = withContext(Dispatchers.IO) { PublicIpProbe.lookup() }
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
                // FILTERNET: the button is never dead. Connected -> disconnect,
                // searching -> abort the search, offline -> nothing to do,
                // otherwise -> go and find a server.
                when {
                    isRunning -> onToggleService()

                    busy -> {
                        pendingConnection = false
                        onCancelAutoConnect()
                    }

                    coreState == CoreState.Offline -> Unit

                    else -> {
                        pendingConnection = true
                        onAutoConnect()
                        scope.launch {
                            delay(8000L)
                            pendingConnection = false
                        }
                    }
                }
            },
        )

        Spacer(Modifier.height(6.dp))

        StatusLine(
            state = coreState,
            uptimeSeconds = uptimeSeconds,
            phase = poolPhase,
            isMeasuring = isMeasuring,
        )

        Spacer(Modifier.height(18.dp))

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
                // FILTERNET: always enabled - a disabled button is how the
                // user ended up unable to stop or cancel anything.
                .clickable(onClick = onClick),
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
                                CoreState.Working -> R.string.fn_core_cancel
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
    isMeasuring: Boolean,
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
                phase is ServerPoolManager.Phase.Measuring -> stringResource(R.string.fn_measuring)
                phase is ServerPoolManager.Phase.Downloading -> stringResource(R.string.fn_gift_downloading)
                phase is ServerPoolManager.Phase.Failed -> stringResource(
                    when (phase.reason) {
                        ServerPoolManager.Reason.NONE_WORKING -> R.string.fn_scan_fail_none
                        ServerPoolManager.Reason.NONE_PASSED -> R.string.fn_scan_fail_passed
                        else -> R.string.fn_scan_fail_net
                    }
                )
                isMeasuring -> stringResource(R.string.fn_measuring)
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

private fun formatUptime(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
