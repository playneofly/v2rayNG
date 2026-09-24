package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.handler.GiftServerManager
import com.v2ray.ang.ui.compose.FilternetAccentBrush
import com.v2ray.ang.ui.compose.FilternetTokens
import kotlinx.coroutines.launch

/**
 * FILTERNET: the "no server? tap here" flow.
 *
 * Downloads the donated pool, scans it and adds the fastest working profiles to
 * the user's list. Running it again replaces the previous batch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GiftServerSheet(
    onDismiss: () -> Unit,
    onServersAdded: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val progress by GiftServerManager.progress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose { GiftServerManager.reset() }
    }

    val busy = progress is GiftServerManager.Progress.Downloading ||
        progress is GiftServerManager.Progress.Scanning

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(62.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("\uD83C\uDF81", fontSize = 28.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(R.string.fn_gift_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.fn_gift_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            StatusBlock(progress)

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    scope.launch {
                        val added = GiftServerManager.fetchServers()
                        if (added > 0) onServersAdded()
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = stringResource(R.string.fn_gift_button),
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.fn_gift_replace_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StatusBlock(progress: GiftServerManager.Progress) {
    when (progress) {
        GiftServerManager.Progress.Idle -> Unit

        GiftServerManager.Progress.Downloading -> StatusLine(
            text = stringResource(R.string.fn_gift_downloading),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            showBar = true,
            fraction = null,
        )

        is GiftServerManager.Progress.Scanning -> StatusLine(
            text = stringResource(
                R.string.fn_gift_scanning,
                "${progress.checked}/${progress.total}",
                "${progress.found}/${GiftServerManager.WANTED}",
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            showBar = true,
            fraction = if (progress.total <= 0) null
            else (progress.checked.toFloat() / progress.total).coerceIn(0f, 1f),
        )

        is GiftServerManager.Progress.Done -> StatusLine(
            text = stringResource(R.string.fn_gift_done, progress.found.toString()),
            color = FilternetTokens.Emerald,
            showBar = false,
            fraction = null,
        )

        is GiftServerManager.Progress.Failed -> StatusLine(
            text = stringResource(
                when (progress.reason) {
                    GiftServerManager.Reason.NETWORK -> R.string.fn_gift_fail_network
                    GiftServerManager.Reason.EMPTY_LIST -> R.string.fn_gift_fail_empty
                    GiftServerManager.Reason.NONE_WORKING -> R.string.fn_gift_fail_none
                }
            ),
            color = FilternetTokens.Rose,
            showBar = false,
            fraction = null,
        )
    }
}

@Composable
private fun StatusLine(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    showBar: Boolean,
    fraction: Float?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (showBar) {
            Spacer(Modifier.height(10.dp))
            if (fraction == null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .background(FilternetAccentBrush, CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * The call-to-action shown on the home tab, under the traffic panel.
 */
@Composable
internal fun GiftServerCallToAction(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("\uD83C\uDF81", fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.fn_gift_cta),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back_24dp),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
