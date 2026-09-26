package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/* ═══════════════════════════════════════════════════════════════════════════
   FILTERNET design system - colours taken 1:1 from the new prototype.

   accent  #4A6CF7   accent2 #8B5CF6   mint #10C98D
   amber   #FFB020   rose    #F4557B

   light: snow #F3F5FA / card #FFFFFF / line #E7EAF2 / ink #0D1220
   dark : night #0A0E19 / card #121828 / line #1E2740 / sub #8B93AD
   ═══════════════════════════════════════════════════════════════════════════ */

private val LightColor = lightColorScheme(
    primary = FilternetTokens.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FE),
    onPrimaryContainer = Color(0xFF0A1B54),
    secondary = FilternetTokens.Accent2,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7DEFE),
    onSecondaryContainer = Color(0xFF241155),
    tertiary = FilternetTokens.Mint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC7F3E4),
    onTertiaryContainer = Color(0xFF00382A),
    error = FilternetTokens.Rose,
    onError = Color.White,
    errorContainer = Color(0xFFFFDBE2),
    onErrorContainer = Color(0xFF5B0018),
    background = FilternetTokens.Snow,
    onBackground = FilternetTokens.InkText,
    surface = FilternetTokens.Snow,
    onSurface = FilternetTokens.InkText,
    surfaceVariant = Color(0xFFE7EAF2),
    onSurfaceVariant = FilternetTokens.SubLight,
    outline = FilternetTokens.FaintLight,
    outlineVariant = FilternetTokens.LineLight,
    inverseSurface = FilternetTokens.InkText,
    inverseOnSurface = Color(0xFFF3F5FA),
    inversePrimary = Color(0xFF9DB1FB),
    scrim = Color.Black,
    surfaceTint = FilternetTokens.Accent,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFAFBFE),
    surfaceContainerHighest = Color(0xFFEEF1F7),
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFF7D97FA),
    onPrimary = Color(0xFF041038),
    primaryContainer = Color(0xFF1F3080),
    onPrimaryContainer = Color(0xFFDDE4FE),
    secondary = Color(0xFFA989FF),
    onSecondary = Color(0xFF1B0B45),
    secondaryContainer = Color(0xFF3A2A6B),
    onSecondaryContainer = Color(0xFFE7DEFE),
    tertiary = FilternetTokens.Mint,
    onTertiary = Color(0xFF00281D),
    tertiaryContainer = Color(0xFF0A4F3B),
    onTertiaryContainer = Color(0xFFC7F3E4),
    error = FilternetTokens.Rose,
    onError = Color(0xFF3F0011),
    errorContainer = Color(0xFF7A1130),
    onErrorContainer = Color(0xFFFFDBE2),
    background = FilternetTokens.Night,
    onBackground = Color(0xFFF2F5FC),
    surface = FilternetTokens.Night,
    onSurface = Color(0xFFF2F5FC),
    surfaceVariant = FilternetTokens.NightLine,
    onSurfaceVariant = FilternetTokens.SubDark,
    outline = Color(0xFF6C7590),
    outlineVariant = FilternetTokens.NightLine,
    inverseSurface = Color(0xFFF2F5FC),
    inverseOnSurface = FilternetTokens.Night,
    inversePrimary = FilternetTokens.Accent,
    scrim = Color.Black,
    surfaceTint = Color(0xFF7D97FA),
    surfaceContainerLowest = Color(0xFF06090F),
    surfaceContainerLow = Color(0xFF0E1320),
    surfaceContainer = FilternetTokens.NightCard,
    surfaceContainerHigh = Color(0xFF161D31),
    surfaceContainerHighest = Color(0xFF1C2439),
)

private val FilternetShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

val colorPing = FilternetTokens.Mint
val colorPingRed = FilternetTokens.Rose
val colorConfigType = FilternetTokens.Accent
val colorTypeVless = Color(0xFF4A6CF7)
val colorTypeVmess = Color(0xFF36D1DC)
val colorTypeTrojan = FilternetTokens.Mint
val colorTypeShadowsocks = FilternetTokens.Amber
val colorTypeHysteria = FilternetTokens.Accent2
val colorTypeWireguard = FilternetTokens.Rose
val colorTypeOther = Color(0xFF8B93AD)
val colorFabActive = FilternetTokens.Accent
val colorFabInactiveLight = Color(0xFF8B8491)
val colorFabInactiveDark = Color(0xFF5F5968)
val dividerColorLight = FilternetTokens.LineLight
val dividerColorDark = FilternetTokens.NightLine

val toastNormalBgLight = Color(0xEB0D1220)
val toastNormalBgDark = Color(0xEB121828)
val toastSuccessBg = Color(0xEB0A8F65)
val toastErrorBg = Color(0xEBB02A4C)
val toastInfoBg = Color(0xE64B1EA7)
val toastIconCircleBg = Color(0x33FFFFFF)
val toastTextColor = Color.White

object ThemeManager {
    private val _themeMode = MutableStateFlow(
        MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
    )
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(
        MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    )
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    fun setThemeMode(mode: String) {
        MmkvManager.encodeSettings(AppConfig.PREF_UI_MODE_NIGHT, mode)
        _themeMode.value = mode
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_COLOR, enabled)
        _dynamicColorEnabled.value = enabled
    }

    fun refresh() {
        _themeMode.value = MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value = MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
    }
}

@Composable
fun resolveDarkTheme(): Boolean {
    val mode by ThemeManager.themeMode.collectAsState()
    return when (mode) {
        "1" -> false
        "2" -> true
        else -> isSystemInDarkTheme()
    }
}

val LocalDarkTheme = compositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dynamicColor by ThemeManager.dynamicColorEnabled.collectAsState()
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColor
        else -> LightColor
    }
    val snackbarController = rememberAppSnackbarController()
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController,
        // FILTERNET: Material's default LocalContentColor is plain black. Any Text
        // that does not set a colour of its own (the up/down figures, the server
        // name, the traffic statistics) rendered black-on-dark and was invisible.
        // Anchoring it to the theme fixes every one of them at once.
        LocalContentColor provides colorScheme.onBackground,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = FilternetShapes,
            typography = FilternetTypography,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}