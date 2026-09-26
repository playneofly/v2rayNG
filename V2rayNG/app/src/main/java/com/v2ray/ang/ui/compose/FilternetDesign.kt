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
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp

    val RadiusSmall = 14.dp
    val RadiusMedium = 20.dp
    val RadiusLarge = 24.dp
    val RadiusXLarge = 30.dp

    /* ---- brand ---- */
    val Accent = Color(0xFF4A6CF7)
    val Accent2 = Color(0xFF8B5CF6)
    val Mint = Color(0xFF10C98D)
    val Amber = Color(0xFFFFB020)
    val Rose = Color(0xFFF4557B)

    /* ---- dark surfaces ---- */
    val Night = Color(0xFF0A0E19)
    val NightCard = Color(0xFF121828)
    val NightLine = Color(0xFF1E2740)
    val SubDark = Color(0xFF8B93AD)

    /* ---- light surfaces ---- */
    val Snow = Color(0xFFF3F5FA)
    val LineLight = Color(0xFFE7EAF2)
    val InkText = Color(0xFF0D1220)
    val SubLight = Color(0xFF68718A)
    val FaintLight = Color(0xFF9AA3B8)

    /* kept for older call sites */
    val Cyan = Accent
    val Violet = Accent2
    val Emerald = Mint
    val Ink = Night

    /**
     * Per-country avatar gradients, straight out of the prototype. Picked from
     * the profile name so the same server always gets the same colours.
     */
    val AvatarGradients: List<Pair<Color, Color>> = listOf(
        Color(0xFF4A6CF7) to Color(0xFF8B5CF6),
        Color(0xFFF77062) to Color(0xFFFE5196),
        Color(0xFF36D1DC) to Color(0xFF5B86E5),
        Color(0xFF7F7FD5) to Color(0xFF91EAE4),
        Color(0xFF43CEA2) to Color(0xFF185A9D),
        Color(0xFFFF9A9E) to Color(0xFFFAD0C4),
        Color(0xFFF6D365) to Color(0xFFFDA085),
        Color(0xFFA18CD1) to Color(0xFFFBC2EB),
        Color(0xFF84FAB0) to Color(0xFF8FD3F4),
        Color(0xFF89F7FE) to Color(0xFF66A6FF),
    )

    fun gradientFor(key: String): Pair<Color, Color> {
        if (key.isEmpty()) return AvatarGradients[0]
        var h = 0
        for (c in key) h = h * 31 + c.code
        return AvatarGradients[((h % AvatarGradients.size) + AvatarGradients.size) % AvatarGradients.size]
    }
}

/* ═══════════════════ Persian helpers ═══════════════════ */

private val PERSIAN_DIGITS = charArrayOf('\u06F0', '\u06F1', '\u06F2', '\u06F3', '\u06F4', '\u06F5', '\u06F6', '\u06F7', '\u06F8', '\u06F9')

/** Turns 1234 into Persian digits. Non digits pass through untouched. */
fun faDigits(value: Any?): String {
    val s = value?.toString() ?: return ""
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in '0'..'9') PERSIAN_DIGITS[c - '0'] else c)
    return sb.toString()
}

/**
 * FILTERNET: ping thresholds tuned for Iran. A 60 ms hop to Europe simply does
 * not happen here, so the prototype's thresholds would have marked every single
 * server as "poor".
 */
enum class PingTone { Good, Mid, Bad, Unknown }

fun pingTone(delayMillis: Long): PingTone = when {
    delayMillis <= 0L -> PingTone.Unknown
    delayMillis < 150L -> PingTone.Good
    delayMillis < 300L -> PingTone.Mid
    else -> PingTone.Bad
}

@Composable
fun pingToneColor(delayMillis: Long): Color = when (pingTone(delayMillis)) {
    PingTone.Good -> FilternetTokens.Mint
    PingTone.Mid -> FilternetTokens.Amber
    PingTone.Bad -> FilternetTokens.Rose
    PingTone.Unknown -> MaterialTheme.colorScheme.outline
}

/* ═══════════════════ surfaces ═══════════════════ */

val FilternetAccentBrush: Brush
    @Composable get() = Brush.linearGradient(
        listOf(FilternetTokens.Accent, FilternetTokens.Accent2)
    )

/** Flat card colour - the new design dropped the translucent "glass" look. */
val FilternetGlassColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainer

val FilternetGlassBorder: BorderStroke
    @Composable get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)

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