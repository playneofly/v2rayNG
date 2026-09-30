package com.v2ray.ang.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.FilternetTokens
import kotlinx.coroutines.delay

/**
 * FILTERNET: the app is useless without VPN consent, and on Android 13+ the
 * foreground service is invisible without the notification permission. This
 * gate blocks everything until both are granted.
 *
 * It cannot be dismissed - no back press, no tap outside - and it notices the
 * moment a permission is granted, including when the user grants it from the
 * system settings screen, because it re-checks on every resume and also polls
 * gently while visible.
 */
@Composable
internal fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var vpnOk by remember { mutableStateOf(hasVpnConsent(context)) }
    var notifyOk by remember { mutableStateOf(hasNotificationPermission(context)) }

    fun recheck() {
        vpnOk = hasVpnConsent(context)
        notifyOk = hasNotificationPermission(context)
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { recheck() }

    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { recheck() }

    // re-check whenever the user comes back from a system screen
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_START) recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val blocked = !vpnOk || !notifyOk

    // ...and poll softly, so a grant made in a split-screen settings page or by
    // a vendor permission manager is picked up without any user action.
    LaunchedEffect(blocked) {
        while (blocked) {
            delay(700L)
            recheck()
        }
    }

    content()

    if (blocked) {
        Dialog(
            onDismissRequest = { /* deliberately not dismissible */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
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
                        text = stringResource(R.string.fn_perm_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.fn_perm_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(22.dp))

                    PermissionRow(
                        title = stringResource(R.string.fn_perm_vpn),
                        subtitle = stringResource(R.string.fn_perm_vpn_sub),
                        granted = vpnOk,
                        onClick = {
                            val intent = runCatching { VpnService.prepare(context) }.getOrNull()
                            if (intent != null) vpnLauncher.launch(intent) else recheck()
                        },
                    )

                    Spacer(Modifier.height(10.dp))

                    PermissionRow(
                        title = stringResource(R.string.fn_perm_notify),
                        subtitle = stringResource(R.string.fn_perm_notify_sub),
                        granted = notifyOk,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                recheck()
                            }
                        },
                    )

                    Spacer(Modifier.height(20.dp))

                    Text(
                        text = stringResource(R.string.fn_perm_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (granted) it else it.clickable(onClick = onClick) },
        shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
        color = if (granted) FilternetTokens.Mint.copy(alpha = 0.10f)
        else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (granted) FilternetTokens.Mint.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        if (granted) FilternetTokens.Mint else MaterialTheme.colorScheme.surfaceContainerHighest,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (granted) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_done),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White,
                    )
                } else {
                    Text(
                        text = "!",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!granted) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = FilternetTokens.Accent,
                    contentColor = Color.White,
                ) {
                    Text(
                        text = stringResource(R.string.fn_perm_grant),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private fun hasVpnConsent(context: Context): Boolean =
    runCatching { VpnService.prepare(context) == null }.getOrDefault(false)

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return runCatching {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)
}
