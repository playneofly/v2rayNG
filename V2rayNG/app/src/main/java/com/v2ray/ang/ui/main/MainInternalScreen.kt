package com.v2ray.ang.ui.main

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.BundledData
import com.v2ray.ang.handler.CleanIpScanner
import com.v2ray.ang.handler.FilternetMode
import com.v2ray.ang.handler.InternalVault
import com.v2ray.ang.handler.ServerPoolManager
import com.v2ray.ang.ui.compose.FilternetAccentBrush
import com.v2ray.ang.ui.compose.FilternetTokens
import com.v2ray.ang.ui.compose.faCount
import com.v2ray.ang.ui.compose.faDigits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FILTERNET: the private tab.
 *
 * Locked behind a password that is never checked against anything - it simply
 * decrypts the bundle, so the whole thing works during a total shutdown.
 *
 * Behind the lock sits the last resort: the endless clean-IP hunt.
 */
@Composable
internal fun MainInternalScreen(
    isRunning: Boolean,
    onDeepConnect: () -> Unit,
    onCancel: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(InternalVault.isUnlocked() || InternalVault.wasUnlockedBefore()) }

    if (!unlocked) {
        LockScreen(onUnlocked = { unlocked = true })
        return
    }

    val scan by CleanIpScanner.progress.collectAsStateWithLifecycle()
    val busy = scan.running
    // Only call it connected when this tab is the one holding the tunnel. A
    // tunnel the home tab started is none of this screen's business, and
    // showing its own disconnect button for it was how the two tabs ended up
    // fighting over the same service.
    val tunnelOwner by FilternetMode.owner.collectAsStateWithLifecycle()
    val connectedVia by CleanIpScanner.connectedVia.collectAsStateWithLifecycle()
    val minePending = isRunning && tunnelOwner == FilternetMode.Owner.INTERNAL
    val scope = rememberCoroutineScope()
    var blockReport by remember { mutableStateOf<CleanIpScanner.BlockReport?>(null) }
    var classifying by remember { mutableStateOf(false) }
    val carrier = remember { com.v2ray.ang.handler.IrcfSource.carrierName(context) }

    // The mirror of the message the home tab shows. Without it this screen
    // would read "not connected" while the home tab's tunnel was up, and the
    // orb would cheerfully start a deep hunt on top of a live connection.
    //
    // The test is "not mine" rather than "the home tab's" on purpose: a tunnel
    // started from the quick-settings tile, the widget or boot autostart has no
    // owner recorded at all, and that is still not this screen's to replace.
    if (isRunning && tunnelOwner != FilternetMode.Owner.INTERNAL) {
        MainModeHoldsTunnel()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.fn_internal_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.fn_internal_carrier, carrier),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                modifier = Modifier.clickable {
                    InternalVault.lock()
                    unlocked = false
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Text(
                    text = stringResource(R.string.fn_internal_lock),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        DeepConnectOrb(
            isRunning = minePending,
            busy = busy,
            stage = scan.stage,
            onClick = {
                when {
                    minePending -> onDisconnect()
                    busy -> onCancel()
                    else -> onDeepConnect()
                }
            },
        )

        if (minePending || busy) {
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (minePending) onDisconnect() else onCancel() },
                shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
                color = FilternetTokens.Rose.copy(alpha = 0.10f),
                contentColor = FilternetTokens.Rose,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, FilternetTokens.Rose.copy(alpha = 0.35f),
                ),
            ) {
                Text(
                    text = stringResource(
                        if (minePending) R.string.fn_internal_disconnect
                        else R.string.fn_core_cancel
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = FilternetTokens.Rose,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 13.dp),
                )
            }
        }

        // Proof, not reassurance: the actual address carrying the traffic.
        // Copied to a local first - a `by` delegate cannot be smart cast.
        val via = connectedVia
        if (minePending && via != null) {
            Spacer(Modifier.height(12.dp))
            ConnectedViaCard(address = via)
        }

        Spacer(Modifier.height(14.dp))

        StageList(scan = scan, isRunning = minePending)

        Spacer(Modifier.height(12.dp))

        // ── what this build can do with no network ───────────────────────
        OfflineDataCard()

        Spacer(Modifier.height(12.dp))

        // ── what kind of block is this? ──────────────────────────────────
        BlockCheckCard(
            report = blockReport,
            busy = classifying,
            onRun = {
                classifying = true
                blockReport = null
                scope.launch {
                    val links = withContext(Dispatchers.IO) {
                        InternalVault.configs().ifEmpty {
                            com.v2ray.ang.handler.ServerPoolManager.poolLinks(context)
                        }
                    }
                    val tpl = CleanIpScanner.bestTemplate(links)
                    blockReport = if (tpl == null) null
                    else withContext(Dispatchers.IO) { CleanIpScanner.classifyBlock(tpl) }
                    classifying = false
                }
            },
        )

        Spacer(Modifier.height(12.dp))

        // ── the live address list ────────────────────────────────────────
        if (scan.recent.isNotEmpty()) {
            ProbeList(scan.recent)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/* ═══════════════════ the home tab is holding the tunnel ═══════════════════ */

/**
 * FILTERNET: the mirror image of the home tab's notice.
 *
 * Android has one VPN slot. Whichever tab filled it owns it, and the other
 * one says so rather than pretending it could start a second.
 */
@Composable
private fun MainModeHoldsTunnel() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            modifier = Modifier.size(108.dp),
            shape = CircleShape,
            color = FilternetTokens.Mint.copy(alpha = 0.12f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, FilternetTokens.Mint.copy(alpha = 0.45f),
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = FilternetTokens.Mint,
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = stringResource(R.string.fn_main_holds_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.fn_main_holds_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/* ═══════════════════════ live address list ═══════════════════════ */

@Composable
private fun ProbeList(items: List<CleanIpScanner.Probe>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            items.take(50).forEach { p ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (p.alive) "✓" else "✕",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (p.alive) FilternetTokens.Mint else FilternetTokens.Rose,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = p.address,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (p.alive) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                    )
                    if (p.alive) {
                        Text(
                            text = faDigits(p.ms) + " ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = FilternetTokens.Mint,
                        )
                    }
                }
            }
        }
    }
}

/* ═══════════════════════ block classifier ═══════════════════════ */

@Composable
private fun BlockCheckCard(
    report: CleanIpScanner.BlockReport?,
    busy: Boolean,
    onRun: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onRun),
        shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                text = stringResource(
                    if (busy) R.string.fn_block_running else R.string.fn_block_title
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            val (msgRes, tint) = when (report?.type) {
                null -> R.string.fn_block_hint to MaterialTheme.colorScheme.onSurfaceVariant
                CleanIpScanner.BlockType.ADDRESS_BLOCKED ->
                    R.string.fn_block_address to FilternetTokens.Mint
                CleanIpScanner.BlockType.NAME_BLOCKED ->
                    R.string.fn_block_name to FilternetTokens.Rose
                CleanIpScanner.BlockType.NETWORK_BLOCKED ->
                    R.string.fn_block_network to FilternetTokens.Amber
                CleanIpScanner.BlockType.NOT_BLOCKED ->
                    R.string.fn_block_none to FilternetTokens.Mint
            }
            Text(
                text = stringResource(msgRes),
                style = MaterialTheme.typography.bodySmall,
                color = tint,
            )
            if (report != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.fn_block_counts,
                        faDigits(report.oursOk),
                        faDigits(report.neutralOk),
                        faDigits(report.tried),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/* ═════════════════════ what is inside this build ═════════════════════ */

/**
 * FILTERNET: the data this APK carries, and the only button that touches the
 * network on purpose.
 *
 * Every figure comes out of the manifest [BundledData] reads from the APK, not
 * from a constant someone has to remember to edit. Add configs to the pool file
 * and push: the build bakes them in and this card reports the new number by
 * itself. That property is the whole reason the manifest exists.
 *
 * Tapping refreshes the pool from the mirrors. It is allowed to fail - the card
 * says so and carries on with the bundled copy, because the bundled copy was
 * never a fallback in the first place. It is the default.
 */
@Composable
private fun OfflineDataCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var manifest by remember { mutableStateOf(BundledData.Manifest()) }
    var available by remember { mutableStateOf(0) }
    var privateCount by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshFailed by remember { mutableStateOf(false) }

    // Reading the manifest touches the APK and counting the pool inflates
    // 600 KB of gzip; neither belongs in composition. The card draws with
    // zeroes for one frame and fills itself in.
    LaunchedEffect(Unit) {
        val read = withContext(Dispatchers.IO) { BundledData.manifest(context) }
        manifest = read
        available = read.poolConfigs
        // What is really loaded right now - a refresh may have replaced the
        // bundled list with a longer one.
        val counted = withContext(Dispatchers.IO) { ServerPoolManager.availableCount(context) }
        available = maxOf(counted, read.poolConfigs)
        // A bundle that was sealed elsewhere and committed ready-made cannot be
        // counted by the build script, which writes -1 for it. This card only
        // ever draws behind the unlock screen, so the open vault is the better
        // witness; the manifest is the fallback for the build-time path.
        privateCount = maxOf(InternalVault.configs().size, read.internalConfigs)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !refreshing) {
                refreshing = true
                refreshFailed = false
                scope.launch {
                    val fresh = ServerPoolManager.refreshNow(context)
                    if (fresh == null) {
                        refreshFailed = true
                    } else {
                        available = fresh
                    }
                    refreshing = false
                }
            },
        shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                text = stringResource(R.string.fn_offline_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))

            OfflineDataRow(R.string.fn_offline_pool, faCount(available))
            if (privateCount > 0) {
                OfflineDataRow(R.string.fn_offline_internal, faCount(privateCount))
            }
            OfflineDataRow(R.string.fn_offline_ircf, faCount(manifest.ircfAddresses))
            OfflineDataRow(R.string.fn_offline_cloudflare, faCount(manifest.cloudflareAddresses))

            Spacer(Modifier.height(7.dp))
            Text(
                text = when {
                    refreshing -> stringResource(R.string.fn_offline_refreshing)
                    refreshFailed -> stringResource(R.string.fn_offline_refresh_failed)
                    else -> stringResource(R.string.fn_offline_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    refreshFailed -> FilternetTokens.Amber
                    refreshing -> FilternetTokens.Mint
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (manifest.builtAt.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    // "2026-10-01T16:45:00Z" reads better as just the date here.
                    text = stringResource(
                        R.string.fn_offline_built,
                        faDigits(manifest.builtAt.substringBefore('T')),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun OfflineDataRow(labelRes: Int, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/* ═══════════════════════════ lock screen ═══════════════════════════ */

@Composable
private fun LockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    fun attempt() {
        if (checking || password.isBlank()) return
        checking = true
        error = false
        scope.launch {
            // Key derivation is deliberately slow, so keep it off the main thread.
            val ok = withContext(Dispatchers.Default) {
                InternalVault.unlock(context, password.trim())
            }
            checking = false
            if (ok) onUnlocked() else { error = true; password = "" }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(74.dp)
                .background(
                    Brush.linearGradient(
                        listOf(FilternetTokens.Accent, FilternetTokens.Accent2)
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_shield_24dp),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = Color.White,
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = stringResource(R.string.fn_internal_locked_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.fn_internal_locked_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(22.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (error) FilternetTokens.Rose else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (password.isEmpty()) {
                    Text(
                        text = stringResource(R.string.fn_internal_password),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                BasicTextField(
                    value = password,
                    onValueChange = { password = it; error = false },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { attempt() }),
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (error) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.fn_internal_wrong),
                style = MaterialTheme.typography.bodySmall,
                color = FilternetTokens.Rose,
            )
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !checking) { attempt() },
            shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
            color = Color.Transparent,
        ) {
            Box(
                modifier = Modifier.background(FilternetAccentBrush),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        if (checking) R.string.fn_internal_checking else R.string.fn_internal_enter
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
        }
    }
}

/* ═══════════════════════════ the orb ═══════════════════════════ */

@Composable
private fun DeepConnectOrb(
    isRunning: Boolean,
    busy: Boolean,
    stage: CleanIpScanner.Stage,
    onClick: () -> Unit,
) {
    val tint = when {
        isRunning -> FilternetTokens.Mint
        busy -> FilternetTokens.Amber
        else -> FilternetTokens.Accent
    }

    // FILTERNET: the orb had no animation of any kind, so a hunt that was
    // genuinely working was indistinguishable from a dead button. Two cues:
    // the whole orb breathes while busy, and a ring sweeps around it so there
    // is visible motion even when the counters have not changed for a while.
    val pulse = rememberInfiniteTransition(label = "orb")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (busy) 1.045f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val sweep by pulse.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    Box(contentAlignment = Alignment.Center) {
        if (busy) {
            Canvas(Modifier.size(186.dp)) {
                drawArc(
                    color = tint,
                    startAngle = sweep,
                    sweepAngle = 72f,
                    useCenter = false,
                    style = Stroke(width = 5f, cap = StrokeCap.Round),
                )
            }
        }
    Surface(
        modifier = Modifier
            .size(170.dp)
            .scale(if (busy) scale else 1f)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = Color.White,
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(listOf(tint, FilternetTokens.Accent2))
            ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(R.drawable.ic_bolt_24dp),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        when {
                            isRunning -> R.string.fn_core_on
                            busy -> R.string.fn_core_cancel
                            else -> R.string.fn_internal_deep
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                // Which of the four phases, in words. Without this the user
                // cannot tell a stalled hunt from a slow one.
                val stageLabel = when (stage) {
                    CleanIpScanner.Stage.PREPARING -> R.string.fn_stage_preparing
                    CleanIpScanner.Stage.SEEDING -> R.string.fn_stage_seeding
                    CleanIpScanner.Stage.SWEEPING -> R.string.fn_stage_sweeping
                    CleanIpScanner.Stage.MEASURING -> R.string.fn_stage_measuring
                    CleanIpScanner.Stage.IDLE -> null
                }
                if (busy && stageLabel != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = stringResource(stageLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
    }
}

/* ══════════════════════ which address are we on ══════════════════════ */

/**
 * FILTERNET: shows the clean IP the tunnel is actually running through.
 *
 * The private tab's whole claim is that it reaches the backend by a CDN
 * address the filter has not got to yet. Asserting that in a help text is
 * worth nothing; printing the address is checkable.
 */
@Composable
private fun ConnectedViaCard(address: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
        color = FilternetTokens.Mint.copy(alpha = 0.10f),
        contentColor = FilternetTokens.Mint,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, FilternetTokens.Mint.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(
                text = stringResource(R.string.fn_connected_via),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = address,
                style = MaterialTheme.typography.titleMedium,
                color = FilternetTokens.Mint,
            )
        }
    }
}

/* ═══════════════════════════ the ladder ═══════════════════════════ */

@Composable
private fun StageList(scan: CleanIpScanner.Progress, isRunning: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        StageRow(
            index = 1,
            label = stringResource(R.string.fn_src_memory, faDigits(scan.seedMemory)),
            done = isRunning,
            active = scan.running,
        )
        StageRow(
            index = 2,
            label = stringResource(R.string.fn_src_ircf, faDigits(scan.seedIrcf)),
            done = isRunning,
            active = scan.running,
        )
        StageRow(
            index = 3,
            label = if (scan.probed > 0) stringResource(
                R.string.fn_stage_scan_live,
                faDigits(scan.probed),
                faDigits(scan.alive),
            ) else stringResource(R.string.fn_stage_scan),
            done = isRunning,
            active = scan.running,
        )

        if (scan.running) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            ) {
                // The hunt has no end, so the bar shows motion, not progress -
                // a percentage here would simply be a lie.
                Box(
                    Modifier
                        .fillMaxWidth(((scan.probed % 400) / 400f))
                        .height(5.dp)
                        .background(FilternetAccentBrush, CircleShape)
                )
            }
        }
    }
}

@Composable
private fun StageRow(index: Int, label: String, done: Boolean, active: Boolean) {
    val tint = when {
        done -> FilternetTokens.Mint
        active -> FilternetTokens.Amber
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(tint.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (done) "✓" else faDigits(index),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (active) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
