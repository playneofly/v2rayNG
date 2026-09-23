package com.v2ray.ang.ui.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.handler.LiveSpeedStore
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.ui.compose.AppDivider
import com.v2ray.ang.ui.compose.colorFabActive
import com.v2ray.ang.ui.compose.colorFabInactiveDark
import com.v2ray.ang.ui.compose.colorFabInactiveLight
import com.v2ray.ang.ui.compose.colorPing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * FILTERNET bottom bar.
 *
 * Layout from top to bottom:
 *  1. status / ping line (tap = test current server) with the connect FAB floating on it
 *  2. live download / upload rates plus a small sparkline while connected
 *  3. a row of rectangular action buttons: Best | Test real delays | Sort
 */
@Composable
fun MainBottomBar(
    displayText: String,
    isRunning: Boolean,
    isTesting: Boolean,
    isDarkTheme: Boolean,
    onAction: (MainAction) -> Unit
) {
    val scope = rememberCoroutineScope()
    val rotationAnim = remember { Animatable(0f) }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            rotationAnim.snapTo(0f)
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            AppDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable(onClick = { onAction(MainAction.TestCurrentServer) })
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(end = 96.dp)
                        .semantics { contentDescription = displayText }
                )
            }

            LiveSpeedRow(isRunning = isRunning)

            BottomActionRow(isTesting = isTesting, onAction = onAction)
        }
        FloatingActionButton(
            onClick = {
                if (!isRunning) {
                    scope.launch {
                        rotationAnim.animateTo(
                            targetValue = 720f,
                            animationSpec = tween(durationMillis = 3000)
                        )
                    }
                }
                onAction(MainAction.ToggleService)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 24.dp)
                .offset(y = (-28).dp)
                .navigationBarsPadding(),
            containerColor = if (isRunning) colorFabActive
            else if (isDarkTheme) colorFabInactiveDark
            else colorFabInactiveLight
        ) {
            Icon(
                painter = if (isRunning) painterResource(R.drawable.ic_stop_24dp)
                else painterResource(R.drawable.ic_play_24dp),
                contentDescription = stringResource(
                    if (isRunning) R.string.acc_stop else R.string.acc_start
                ),
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = rotationAnim.value }
            )
        }
    }
}

/**
 * Rectangular action buttons sitting right under the ping line and the connect button.
 */
@Composable
private fun BottomActionRow(isTesting: Boolean, onAction: (MainAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = { onAction(MainAction.ConnectBestServer) },
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colorFabActive)
        ) {
            Text(
                text = stringResource(R.string.fn_btn_best),
                maxLines = 1,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
        FilledTonalButton(
            onClick = { onAction(if (isTesting) MainAction.CancelTesting else MainAction.TestRealAllServers) },
            modifier = Modifier
                .weight(1.5f)
                .height(44.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
        ) {
            Text(
                text = stringResource(
                    if (isTesting) R.string.connection_test_testing else R.string.fn_btn_test_real
                ),
                maxLines = 1,
                fontSize = 13.sp
            )
        }
        FilledTonalButton(
            onClick = { onAction(MainAction.SortByTestResults) },
            modifier = Modifier
                .weight(1.5f)
                .height(44.dp),
            shape = RoundedCornerShape(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.fn_btn_sort),
                maxLines = 1,
                fontSize = 13.sp
            )
        }
    }
}

/**
 * Live download / upload rate with a compact sparkline of the recent download history.
 */
@Composable
private fun LiveSpeedRow(isRunning: Boolean) {
    var sample by remember { mutableStateOf(LiveSpeedStore.Sample(0L, 0L, emptyList(), 0L)) }

    LaunchedEffect(isRunning) {
        if (!isRunning) {
            sample = LiveSpeedStore.Sample(0L, 0L, emptyList(), 0L)
            return@LaunchedEffect
        }
        while (true) {
            sample = LiveSpeedStore.read()
            val saver = MmkvManager.decodeSettingsBool(AppConfig.PREF_FN_BATTERY_SAVER) == true
            delay(if (saver) 5000L else 2000L)
        }
    }

    if (!isRunning) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "\u2193 ${sample.down.toSpeedString()}",
            style = MaterialTheme.typography.bodySmall,
            color = colorPing,
            maxLines = 1
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "\u2191 ${sample.up.toSpeedString()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(Modifier.width(12.dp))
        Sparkline(
            values = sample.history,
            color = colorPing,
            modifier = Modifier
                .weight(1f)
                .height(22.dp)
        )
    }
}

/**
 * Minimal line chart drawn on a Canvas; no extra dependency needed.
 */
@Composable
private fun Sparkline(values: List<Long>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val maxValue = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val stepX = size.width / (values.size - 1).toFloat()
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - (value.toFloat() / maxValue) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))
        drawLine(
            color = color.copy(alpha = 0.25f),
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.dp.toPx()
        )
    }
}
