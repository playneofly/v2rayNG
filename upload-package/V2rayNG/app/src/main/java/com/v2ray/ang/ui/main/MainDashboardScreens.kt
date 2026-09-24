package com.v2ray.ang.ui.main

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.TrafficStatsManager
import com.v2ray.ang.ui.compose.FilternetAccentBrush
import com.v2ray.ang.ui.compose.FilternetCardShape
import com.v2ray.ang.ui.compose.FilternetGlassBorder
import com.v2ray.ang.ui.compose.FilternetGlassColor
import com.v2ray.ang.ui.compose.FilternetTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private data class MainTrafficSnapshot(
    val days: List<TrafficStatsManager.DayTraffic> = emptyList(),
    val today: TrafficStatsManager.DayTraffic = TrafficStatsManager.DayTraffic("", 0L, 0L),
    val totalUp: Long = 0L,
    val totalDown: Long = 0L,
)

@Composable
internal fun MainTrafficScreen() {
    var reloadKey by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(MainTrafficSnapshot()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadKey) {
        snapshot = withContext(Dispatchers.IO) {
            MainTrafficSnapshot(
                days = TrafficStatsManager.lastDays(7),
                today = TrafficStatsManager.today(),
                totalUp = TrafficStatsManager.totalUp(),
                totalDown = TrafficStatsManager.totalDown(),
            )
        }
    }

    val weekUp = snapshot.days.sumOf { it.up }
    val weekDown = snapshot.days.sumOf { it.down }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrafficSummary(
                title = stringResource(R.string.fn_traffic_today),
                value = snapshot.today.total,
                modifier = Modifier.weight(1f),
            )
            TrafficSummary(
                title = stringResource(R.string.fn_traffic_week),
                value = weekDown + weekUp,
                modifier = Modifier.weight(1f),
            )
            TrafficSummary(
                title = stringResource(R.string.fn_traffic_total),
                value = snapshot.totalDown + snapshot.totalUp,
                modifier = Modifier.weight(1f),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = FilternetGlassColor,
            shape = FilternetCardShape,
            border = FilternetGlassBorder,
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrafficDonut(
                    down = snapshot.totalDown,
                    up = snapshot.totalUp,
                    modifier = Modifier.size(128.dp),
                )
                Spacer(Modifier.width(20.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TrafficLegend(
                        color = MaterialTheme.colorScheme.secondary,
                        label = stringResource(R.string.fn_speed_down),
                        value = snapshot.totalDown.toTrafficString(),
                    )
                    TrafficLegend(
                        color = MaterialTheme.colorScheme.primary,
                        label = stringResource(R.string.fn_speed_up),
                        value = snapshot.totalUp.toTrafficString(),
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = FilternetGlassColor,
            shape = FilternetCardShape,
            border = FilternetGlassBorder,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.fn_traffic_week),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                WeeklyTrafficChart(
                    days = snapshot.days,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                )
            }
        }

        Button(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { TrafficStatsManager.reset() }
                    reloadKey++
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text(stringResource(R.string.fn_traffic_reset))
        }
    }
}

@Composable
private fun TrafficSummary(title: String, value: Long, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = FilternetGlassColor,
        shape = RoundedCornerShape(18.dp),
        border = FilternetGlassBorder,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value.toTrafficString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrafficDonut(down: Long, up: Long, modifier: Modifier = Modifier) {
    val total = max(1L, down + up)
    val fraction by animateFloatAsState(
        targetValue = down.toFloat() / total,
        animationSpec = tween(900),
        label = "traffic-donut",
    )
    val downloadColor = MaterialTheme.colorScheme.secondary
    val uploadColor = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2f
            val bounds = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                color = Color.White.copy(alpha = 0.07f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = bounds,
                style = stroke,
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(downloadColor, uploadColor)
                ),
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = bounds,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(fraction * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = stringResource(R.string.fn_speed_down),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrafficLegend(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WeeklyTrafficChart(
    days: List<TrafficStatsManager.DayTraffic>,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(1f, tween(800), label = "weekly-traffic-bars")
    val barColor = MaterialTheme.colorScheme.secondary
    val uploadColor = MaterialTheme.colorScheme.primary

    Canvas(modifier) {
        if (days.isEmpty()) return@Canvas
        val maxValue = max(1L, days.maxOfOrNull { it.total } ?: 1L).toFloat()
        val slot = size.width / days.size
        val width = slot * 0.46f
        days.forEachIndexed { index, day ->
            val downloadHeight = day.down / maxValue * size.height * progress
            val uploadHeight = day.up / maxValue * size.height * progress
            val x = index * slot + (slot - width) / 2f
            drawRoundRect(
                color = uploadColor,
                topLeft = Offset(x, size.height - downloadHeight - uploadHeight),
                size = Size(width, uploadHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f),
            )
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, size.height - downloadHeight),
                size = Size(width, downloadHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f),
            )
        }
    }
}

@Composable
internal fun MainSettingsHub(onNavigate: (MainDestination) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSection(
            title = stringResource(R.string.title_settings),
            entries = listOf(
                MainDestination.Settings to R.string.fn_settings_general_sub,
                MainDestination.Subscriptions to R.string.fn_settings_subs_sub,
                MainDestination.PerAppProxy to R.string.fn_settings_perapp_sub,
                MainDestination.Routing to R.string.fn_settings_routing_sub,
            ),
            onNavigate = onNavigate,
        )
        SettingsSection(
            title = stringResource(R.string.title_advanced),
            entries = listOf(
                MainDestination.UserAssets to R.string.fn_settings_assets_sub,
                MainDestination.BackupRestore to R.string.fn_settings_backup_sub,
                MainDestination.Logcat to R.string.fn_settings_logcat_sub,
                MainDestination.CheckUpdate to R.string.fn_settings_update_sub,
                MainDestination.About to R.string.fn_settings_about_sub,
            ),
            onNavigate = onNavigate,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "v" + com.v2ray.ang.BuildConfig.VERSION_NAME,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    entries: List<Pair<MainDestination, Int>>,
    onNavigate: (MainDestination) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp, bottom = 7.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = FilternetGlassColor,
            shape = FilternetCardShape,
            border = FilternetGlassBorder,
        ) {
            Column {
                entries.forEachIndexed { index, entry ->
                    val destination = entry.first
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(destination) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(38.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(destination.iconRes),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(destination.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(entry.second),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "\u203A",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index != entries.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 64.dp)
                                .height(1.dp)
                                .background(
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
                                )
                        )
                    }
                }
            }
        }
    }
}
