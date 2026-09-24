package com.v2ray.ang.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

object FilternetTokens {
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space6 = 24.dp

    val RadiusSmall = 12.dp
    val RadiusMedium = 18.dp
    val RadiusLarge = 26.dp
    val RadiusXLarge = 34.dp

    val Cyan = Color(0xFF22D3EE)
    val Violet = Color(0xFF8B5CF6)
    val Emerald = Color(0xFF34E0A1)
    val Amber = Color(0xFFFFC24B)
    val Rose = Color(0xFFFF5D73)
    val Ink = Color(0xFF08060F)
}

val FilternetAccentBrush: Brush
    @Composable get() = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.primary)
    )

val FilternetGlassColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f)

val FilternetGlassBorder: BorderStroke
    @Composable get() = BorderStroke(
        1.dp,
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
    )

val FilternetCardShape = RoundedCornerShape(FilternetTokens.RadiusLarge)

@Composable
fun SignalBars(
    delayMillis: Long,
    modifier: Modifier = Modifier,
    width: Dp = 20.dp,
    height: Dp = 16.dp,
) {
    val activeBars = when {
        delayMillis <= 0L -> 0
        delayMillis < 100L -> 4
        delayMillis < 250L -> 3
        delayMillis < 600L -> 2
        else -> 1
    }
    val activeColor = pingColor(delayMillis)
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Canvas(modifier = modifier.size(width, height)) {
        val gap = size.width * 0.08f
        val barWidth = (size.width - gap * 3f) / 4f
        repeat(4) { index ->
            val barHeight = size.height * (0.3f + index * 0.22f)
            drawRoundRect(
                color = if (index < activeBars) activeColor else inactiveColor,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
fun pingColor(delayMillis: Long): Color = when {
    delayMillis <= 0L -> MaterialTheme.colorScheme.onSurfaceVariant
    delayMillis < 100L -> FilternetTokens.Emerald
    delayMillis < 250L -> Color(0xFF91E36B)
    delayMillis < 600L -> FilternetTokens.Amber
    else -> FilternetTokens.Rose
}

@Composable
fun MainNavigationGlyph(
    type: MainNavigationGlyphType,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier.size(22.dp)) {
        val stroke = Stroke(width = max(1.8.dp.toPx(), size.minDimension * 0.075f))
        when (type) {
            MainNavigationGlyphType.Home -> {
                val path = Path().apply {
                    moveTo(size.width * 0.14f, size.height * 0.48f)
                    lineTo(size.width * 0.5f, size.height * 0.18f)
                    lineTo(size.width * 0.86f, size.height * 0.48f)
                    lineTo(size.width * 0.8f, size.height * 0.48f)
                    lineTo(size.width * 0.8f, size.height * 0.84f)
                    lineTo(size.width * 0.2f, size.height * 0.84f)
                    lineTo(size.width * 0.2f, size.height * 0.48f)
                }
                drawPath(path, color, style = stroke)
            }
            MainNavigationGlyphType.Servers -> {
                drawCircle(color, radius = size.minDimension * 0.37f, style = stroke)
                drawOval(
                    color,
                    topLeft = Offset(size.width * 0.34f, size.height * 0.13f),
                    size = Size(size.width * 0.32f, size.height * 0.74f),
                    style = stroke,
                )
                drawLine(color, Offset(size.width * 0.13f, size.height / 2f), Offset(size.width * 0.87f, size.height / 2f), stroke.width)
            }
            MainNavigationGlyphType.Stats -> {
                val widths = size.width * 0.16f
                listOf(0.46f, 0.72f, 0.34f).forEachIndexed { index, fraction ->
                    drawRoundRect(
                        color,
                        topLeft = Offset(size.width * (0.16f + index * 0.26f), size.height * (0.88f - fraction)),
                        size = Size(widths, size.height * fraction),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(widths / 2f),
                    )
                }
            }
            MainNavigationGlyphType.Settings -> {
                drawCircle(color, radius = size.minDimension * 0.34f, style = stroke)
                drawCircle(color, radius = size.minDimension * 0.12f, style = stroke)
                repeat(8) { index ->
                    val angle = Math.toRadians(index * 45.0)
                    val start = Offset(
                        x = center.x + kotlin.math.cos(angle).toFloat() * size.minDimension * 0.35f,
                        y = center.y + kotlin.math.sin(angle).toFloat() * size.minDimension * 0.35f,
                    )
                    val end = Offset(
                        x = center.x + kotlin.math.cos(angle).toFloat() * size.minDimension * 0.45f,
                        y = center.y + kotlin.math.sin(angle).toFloat() * size.minDimension * 0.45f,
                    )
                    drawLine(color, start, end, stroke.width)
                }
            }
        }
    }
}

enum class MainNavigationGlyphType { Home, Servers, Stats, Settings }

/** Best-effort visual hint from common country names/codes in profile remarks. */
fun countryFlagFor(remarks: String): String {
    val value = remarks.lowercase()
    return when {
        listOf("germany", "de ", "de-", "frankfurt").any(value::contains) -> "🇩🇪"
        listOf("netherlands", "nl ", "nl-", "amsterdam").any(value::contains) -> "🇳🇱"
        listOf("turkey", "tr ", "tr-", "istanbul").any(value::contains) -> "🇹🇷"
        listOf("emirates", "uae", "dubai").any(value::contains) -> "🇦🇪"
        listOf("france", "fr ", "fr-", "paris").any(value::contains) -> "🇫🇷"
        listOf("britain", "england", "uk ", "london").any(value::contains) -> "🇬🇧"
        listOf("sweden", "se ", "stockholm").any(value::contains) -> "🇸🇪"
        listOf("switzerland", "ch ", "zurich").any(value::contains) -> "🇨🇭"
        listOf("singapore", "sg ").any(value::contains) -> "🇸🇬"
        listOf("japan", "jp ", "tokyo").any(value::contains) -> "🇯🇵"
        listOf("canada", "ca ", "toronto").any(value::contains) -> "🇨🇦"
        listOf("united states", "usa", "us ").any(value::contains) -> "🇺🇸"
        else -> "🌐"
    }
}