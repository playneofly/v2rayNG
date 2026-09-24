package com.v2ray.ang.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.TrafficStatsManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.ui.compose.NavigationBarsSpacer
import com.v2ray.ang.ui.compose.colorFabActive
import com.v2ray.ang.ui.compose.colorPing

/**
 * FILTERNET: traffic statistics screen (today / last 7 days / all time)
 * with a small bar chart drawn on a Canvas.
 */
class TrafficStatsActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        TrafficStatsScreen(onBackClick = { finish() })
    }
}

@Composable
private fun TrafficStatsScreen(onBackClick: () -> Unit) {
    var reloadKey by remember { mutableStateOf(0) }
    val days = remember(reloadKey) { TrafficStatsManager.lastDays(7) }
    val today = remember(reloadKey) { TrafficStatsManager.today() }
    val totalUp = remember(reloadKey) { TrafficStatsManager.totalUp() }
    val totalDown = remember(reloadKey) { TrafficStatsManager.totalDown() }
    val weekUp = days.sumOf { it.up }
    val weekDown = days.sumOf { it.down }
    val scrollState = rememberScrollState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.fn_traffic_title),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryCard(stringResource(R.string.fn_traffic_today), today.down, today.up)
            SummaryCard(stringResource(R.string.fn_traffic_week), weekDown, weekUp)
            SummaryCard(stringResource(R.string.fn_traffic_total), totalDown, totalUp)

            if (days.all { it.total == 0L }) {
                Text(
                    text = stringResource(R.string.fn_traffic_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.fn_traffic_week),
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(12.dp))
                        BarChart(
                            values = days.map { it.total },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            days.forEach { day ->
                                Text(
                                    text = day.day.takeLast(2),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    TrafficStatsManager.reset()
                    reloadKey++
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.fn_traffic_reset))
            }
            NavigationBarsSpacer()
        }
    }
}

@Composable
private fun SummaryCard(title: String, down: Long, up: Long) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "\u2193 ${down.toTrafficString()}",
                    color = colorPing,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "\u2191 ${up.toTrafficString()}",
                    color = colorFabActive,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = (down + up).toTrafficString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Simple Canvas bar chart, no charting dependency required.
 */
@Composable
private fun BarChart(values: List<Long>, modifier: Modifier = Modifier) {
    val barColor = colorFabActive
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (values.isEmpty()) return@Canvas
            val maxValue = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
            val slot = size.width / values.size
            val barWidth = slot * 0.55f
            values.forEachIndexed { index, value ->
                val barHeight = (value.toFloat() / maxValue) * size.height
                drawRect(
                    color = barColor,
                    topLeft = Offset(
                        x = index * slot + (slot - barWidth) / 2f,
                        y = size.height - barHeight
                    ),
                    size = Size(barWidth, barHeight)
                )
            }
        }
    }
}
