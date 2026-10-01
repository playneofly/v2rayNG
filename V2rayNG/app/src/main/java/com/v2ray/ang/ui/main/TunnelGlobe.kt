package com.v2ray.ang.ui.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.FilternetTokens
import com.v2ray.ang.ui.compose.faDigits
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * FILTERNET: the live tunnel.
 *
 * A stylised globe with an arc leaving Iran for the exit country and packets
 * travelling along it, their speed following the real download rate. Deliberately
 * not a world map - an accurate map at this size reads as noise, while a clean
 * globe plus a glowing arc reads instantly.
 */
@Composable
internal fun TunnelGlobe(
    isRunning: Boolean,
    country: String?,
    downBytesPerSec: Long,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "globe")
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(42000, easing = LinearEasing)),
        label = "spin",
    )
    // Packets speed up with real traffic: 2.5 s per lap when idle, 0.6 s flat out.
    val lapMillis = if (!isRunning) 2600 else {
        val mb = (downBytesPerSec / 1_000_000f).coerceIn(0f, 5f)
        (2400 - mb * 360).toInt().coerceAtLeast(600)
    }
    val travel by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(lapMillis, easing = LinearEasing)),
        label = "travel",
    )

    val accent = FilternetTokens.Accent
    val accent2 = FilternetTokens.Accent2
    val mint = FilternetTokens.Mint
    val grid = MaterialTheme.colorScheme.outlineVariant

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(FilternetTokens.RadiusLarge),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringRes(R.string.fn_globe_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (isRunning) (country ?: stringRes(R.string.fn_globe_abroad))
                    else stringRes(R.string.fn_state_off),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isRunning) mint else MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(10.dp))

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(132.dp)
            ) {
                val cx = size.width * 0.30f
                val cy = size.height * 0.62f
                val r = size.height * 0.34f

                // ---- globe ----
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = r * 1.9f,
                    ),
                    radius = r * 1.9f,
                    center = Offset(cx, cy),
                )
                drawCircle(grid, radius = r, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))

                // latitude rings
                for (i in 1..3) {
                    val k = i / 4f
                    val ry = r * kotlin.math.sqrt(1f - k * k)
                    drawOval(
                        color = grid.copy(alpha = 0.55f),
                        topLeft = Offset(cx - r, cy - r * k - ry * 0.18f),
                        size = androidx.compose.ui.geometry.Size(r * 2, ry * 0.36f),
                        style = Stroke(0.8.dp.toPx()),
                    )
                    drawOval(
                        color = grid.copy(alpha = 0.55f),
                        topLeft = Offset(cx - r, cy + r * k - ry * 0.18f),
                        size = androidx.compose.ui.geometry.Size(r * 2, ry * 0.36f),
                        style = Stroke(0.8.dp.toPx()),
                    )
                }
                // longitude meridians, rotating to suggest the globe turning
                for (i in 0 until 4) {
                    val phase = Math.toRadians((spin + i * 45.0)) 
                    val squash = cos(phase).toFloat()
                    drawOval(
                        color = grid.copy(alpha = 0.45f),
                        topLeft = Offset(cx - r * kotlin.math.abs(squash), cy - r),
                        size = androidx.compose.ui.geometry.Size(r * 2 * kotlin.math.abs(squash), r * 2),
                        style = Stroke(0.8.dp.toPx()),
                    )
                }

                // ---- origin (Iran) and destination ----
                val from = Offset(cx + r * 0.52f, cy - r * 0.30f)
                val to = Offset(size.width * 0.88f, size.height * 0.26f)

                val arc = Path().apply {
                    moveTo(from.x, from.y)
                    val midX = (from.x + to.x) / 2f
                    val lift = (to.x - from.x) * 0.55f
                    quadraticTo(midX, from.y - lift, to.x, to.y)
                }

                drawPath(
                    arc,
                    brush = Brush.linearGradient(
                        listOf(
                            if (isRunning) mint else grid,
                            if (isRunning) accent2 else grid,
                        ),
                        start = from, end = to,
                    ),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    ),
                )

                // ---- packets riding the arc ----
                if (isRunning) {
                    val measure = PathMeasure().apply { setPath(arc, false) }
                    val len = measure.length
                    for (i in 0 until 3) {
                        val p = ((travel + i / 3f) % 1f)
                        val pos = measure.getPosition(len * p)
                        val fade = (1f - kotlin.math.abs(p - 0.5f) * 1.4f).coerceIn(0.25f, 1f)
                        drawCircle(
                            color = mint.copy(alpha = fade),
                            radius = 3.dp.toPx(),
                            center = pos,
                        )
                        drawCircle(
                            color = mint.copy(alpha = fade * 0.25f),
                            radius = 7.dp.toPx(),
                            center = pos,
                        )
                    }
                }

                // endpoints
                drawCircle(accent, radius = 4.dp.toPx(), center = from)
                drawCircle(accent.copy(alpha = 0.25f), radius = 9.dp.toPx(), center = from)
                drawCircle(
                    if (isRunning) mint else grid,
                    radius = 5.dp.toPx(),
                    center = to,
                )
                if (isRunning) {
                    drawCircle(mint.copy(alpha = 0.22f), radius = 12.dp.toPx(), center = to)
                }
            }
        }
    }
}

@Composable
private fun stringRes(id: Int) = androidx.compose.ui.res.stringResource(id)

/** Shared by the receipt and the recap. */
internal fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    return if (h > 0) faDigits("$h:${m.toString().padStart(2, '0')}") else faDigits("$m")
}

internal fun angleOf(deg: Float): Float = (deg * PI / 180f).toFloat()

internal fun unused(): Float = sin(0f)
