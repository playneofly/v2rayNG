package com.v2ray.ang.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.SessionStatsManager
import com.v2ray.ang.ui.compose.FilternetTokens
import com.v2ray.ang.ui.compose.faDigits
import java.io.File
import java.io.FileOutputStream

/* ═══════════════════════════════════════════════════════════════════════════
   FILTERNET: the two "shareable moment" surfaces.

     · [SessionReceiptSheet] right after disconnecting
     · [MonthlyRecapSheet]   a Spotify-Wrapped style look back

   Both are deliberately screenshot-friendly: big numbers, one colour, no
   chrome. The recap is the only part of the app that asks to be shared.
   ═══════════════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionReceiptSheet(
    session: SessionStatsManager.Session,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.fn_receipt_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BigStat(
                    value = formatDuration(session.durationSeconds),
                    unit = stringResource(
                        if (session.durationSeconds >= 3600) R.string.fn_unit_hour
                        else R.string.fn_unit_minute
                    ),
                    label = stringResource(R.string.fn_receipt_time),
                    tint = FilternetTokens.Mint,
                )
                BigStat(
                    value = faDigits(session.total.toTrafficString().substringBefore(" ")),
                    unit = session.total.toTrafficString().substringAfter(" "),
                    label = stringResource(R.string.fn_receipt_data),
                    tint = FilternetTokens.Accent,
                )
            }

            if (!session.country.isNullOrBlank()) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = session.country,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.fn_receipt_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonthlyRecapSheet(
    recap: SessionStatsManager.MonthRecap,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // the card itself - this is what gets screenshotted
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = Color.Transparent,
            ) {
                Box(
                    Modifier.background(
                        Brush.linearGradient(
                            listOf(FilternetTokens.Accent, FilternetTokens.Accent2)
                        )
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.fn_recap_header, persianMonth(recap.monthKey)),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                        Spacer(Modifier.height(18.dp))

                        Text(
                            text = formatDuration(recap.totalSeconds),
                            fontSize = 46.sp,
                            style = MaterialTheme.typography.displayLarge,
                            color = Color.White,
                        )
                        Text(
                            text = stringResource(R.string.fn_recap_free_hours),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.9f),
                        )

                        Spacer(Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            RecapCell(
                                value = faDigits(recap.totalBytes.toTrafficString()),
                                label = stringResource(R.string.fn_recap_data),
                            )
                            RecapCell(
                                value = faDigits(recap.sessions),
                                label = stringResource(R.string.fn_recap_sessions),
                            )
                        }

                        if (recap.favouriteHour != null || recap.favouriteCountry != null) {
                            Spacer(Modifier.height(16.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Color.White.copy(alpha = 0.22f))
                            )
                            Spacer(Modifier.height(14.dp))
                            recap.favouriteHour?.let {
                                Text(
                                    text = stringResource(
                                        R.string.fn_recap_fav_hour,
                                        faDigits("$it:00"),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.92f),
                                )
                            }
                            recap.favouriteCountry?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.fn_recap_fav_country, it),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.92f),
                                )
                            }
                        }

                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = "FILTERNET",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { shareRecapText(context, recap) },
                shape = RoundedCornerShape(FilternetTokens.RadiusMedium),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_share_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.fn_recap_share),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecapCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun BigStat(value: String, unit: String, label: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                color = tint,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Turns "202603" into a Persian month name, best effort. */
internal fun persianMonth(monthKey: String): String {
    if (monthKey.length < 6) return ""
    val m = monthKey.substring(4).toIntOrNull() ?: return ""
    // Gregorian month -> the Persian month it mostly overlaps.
    val names = listOf(
        "دی", "بهمن", "اسفند", "فروردین", "اردیبهشت", "خرداد",
        "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر",
    )
    return names.getOrElse(m - 1) { "" }
}

private fun shareRecapText(context: Context, recap: SessionStatsManager.MonthRecap) {
    val text = buildString {
        appendLine("🛡 ${persianMonth(recap.monthKey)} من با فیلترنت")
        appendLine()
        appendLine("⏱ ${formatDuration(recap.totalSeconds)} ساعت آزاد")
        appendLine("📊 ${faDigits(recap.totalBytes.toTrafficString())}")
        appendLine("🔌 ${faDigits(recap.sessions)} بار اتصال")
        recap.favouriteCountry?.let { appendLine("🌍 $it") }
        appendLine()
        append("FILTERNET — فیلترشکن رایگان و بدون ثبت‌نام")
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                null,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
