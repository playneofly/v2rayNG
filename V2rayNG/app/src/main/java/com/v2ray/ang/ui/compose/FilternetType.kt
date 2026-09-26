package com.v2ray.ang.ui.compose

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R

/**
 * FILTERNET: Vazirmatn is bundled so Persian text looks identical on every
 * device - stock Android Farsi rendering differs wildly between vendors.
 */
val VazirFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_extrabold, FontWeight.ExtraBold),
)

private fun s(
    size: Int,
    weight: FontWeight,
    lineHeight: Int = (size * 1.45f).toInt(),
    spacing: Float = 0f,
) = TextStyle(
    fontFamily = VazirFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = spacing.sp,
)

/**
 * Scale lifted from the new design prototype: compact, dense, high contrast
 * between a bold title and a light caption.
 */
val FilternetTypography = Typography(
    displayLarge = s(36, FontWeight.ExtraBold),
    displayMedium = s(30, FontWeight.ExtraBold),
    displaySmall = s(26, FontWeight.ExtraBold),

    headlineLarge = s(24, FontWeight.ExtraBold),
    headlineMedium = s(21, FontWeight.ExtraBold),
    headlineSmall = s(19, FontWeight.ExtraBold),

    titleLarge = s(19, FontWeight.ExtraBold),
    titleMedium = s(15, FontWeight.Bold),
    titleSmall = s(13, FontWeight.Bold),

    bodyLarge = s(14, FontWeight.Medium),
    bodyMedium = s(12, FontWeight.Medium),
    bodySmall = s(11, FontWeight.Medium),

    labelLarge = s(13, FontWeight.Bold),
    labelMedium = s(11, FontWeight.Bold),
    labelSmall = s(10, FontWeight.Bold),
)
