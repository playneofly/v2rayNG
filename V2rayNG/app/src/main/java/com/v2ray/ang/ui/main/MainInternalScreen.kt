package com.v2ray.ang.ui.main

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
import com.v2ray.ang.handler.CleanIpScanner
import com.v2ray.ang.handler.InternalVault
import com.v2ray.ang.ui.compose.FilternetAccentBrush
import com.v2ray.ang.ui.compose.FilternetTokens
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
) {
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(InternalVault.isUnlocked() || InternalVault.wasUnlockedBefore()) }

    if (!unlocked) {
        LockScreen(onUnlocked = { unlocked = true })
        return
    }

    val scanPhase by CleanIpScanner.phase.collectAsStateWithLifecycle()
    val busy = scanPhase is CleanIpScanner.Phase.Scanning ||
        scanPhase is CleanIpScanner.Phase.Proving

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
                    text = stringResource(R.string.fn_internal_sub),
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
            isRunning = isRunning,
            busy = busy,
            onClick = { if (busy) onCancel() else onDeepConnect() },
        )

        Spacer(Modifier.height(14.dp))

        StageList(phase = scanPhase, isRunning = isRunning)

        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Column(Modifier.padding(13.dp)) {
                Text(
                    text = stringResource(
                        R.string.fn_internal_space,
                        faDigits(CleanIpScanner.addressSpace() / 1000),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.fn_internal_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val privateCount = InternalVault.configs().size
                if (privateCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.fn_internal_private, faDigits(privateCount)),
                        style = MaterialTheme.typography.labelSmall,
                        color = FilternetTokens.Mint,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
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
private fun DeepConnectOrb(isRunning: Boolean, busy: Boolean, onClick: () -> Unit) {
    val tint = when {
        isRunning -> FilternetTokens.Mint
        busy -> FilternetTokens.Amber
        else -> FilternetTokens.Accent
    }
    Surface(
        modifier = Modifier
            .size(170.dp)
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
            }
        }
    }
}

/* ═══════════════════════════ the ladder ═══════════════════════════ */

@Composable
private fun StageList(phase: CleanIpScanner.Phase, isRunning: Boolean) {
    val scanning = phase as? CleanIpScanner.Phase.Scanning
    Column(Modifier.fillMaxWidth()) {
        StageRow(
            index = 1,
            label = stringResource(R.string.fn_stage_normal),
            done = isRunning,
            active = false,
        )
        StageRow(
            index = 2,
            label = stringResource(R.string.fn_stage_private),
            done = isRunning,
            active = false,
        )
        StageRow(
            index = 3,
            label = when {
                scanning != null -> stringResource(
                    R.string.fn_stage_scan_live,
                    faDigits(scanning.probed),
                    faDigits(scanning.found),
                )
                phase is CleanIpScanner.Phase.Proving -> stringResource(R.string.fn_measuring)
                phase is CleanIpScanner.Phase.Found -> stringResource(
                    R.string.fn_stage_scan_found, phase.address,
                )
                else -> stringResource(R.string.fn_stage_scan)
            },
            done = phase is CleanIpScanner.Phase.Found,
            active = scanning != null || phase is CleanIpScanner.Phase.Proving,
        )

        if (scanning != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            ) {
                // The hunt has no end, so the bar reports motion rather than
                // progress - a percentage here would be a lie.
                val f = ((scanning.probed % 400) / 400f)
                Box(
                    Modifier
                        .fillMaxWidth(f)
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
