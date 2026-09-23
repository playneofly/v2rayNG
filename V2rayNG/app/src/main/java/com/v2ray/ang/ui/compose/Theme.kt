package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import androidx.core.view.WindowCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val LightColor = lightColorScheme(
    primary = Color(0xFF0F3057), // FILTERNET Navy
    onPrimary = Color(0xFFFFFFFF), // White
    primaryContainer = Color(0xFFD3E5F7), // Pale Navy
    onPrimaryContainer = Color(0xFF08243D), // Deep Navy
    secondary = Color(0xFF00838F), // Teal Cyan
    onSecondary = Color(0xFFFFFFFF), // White
    secondaryContainer = Color(0xFFB9F0F5), // Pale Teal
    onSecondaryContainer = Color(0xFF00353D), // Dark Teal
    tertiary = Color(0xFF3D5A98), // Steel Blue
    onTertiary = Color(0xFFFFFFFF), // White
    tertiaryContainer = Color(0xFFDAE2FF), // Pale Blue
    onTertiaryContainer = Color(0xFF1A2C57), // Deep Blue
    error = Color(0xFFBA1A1A), // Red
    errorContainer = Color(0xFFFFDAD6), // Light Red
    onError = Color(0xFFFFFFFF), // White
    onErrorContainer = Color(0xFF410002), // Dark Red
    background = Color(0xFFFFFFFF), // White
    onBackground = Color(0xFF10192A), // Navy
    surface = Color(0xFFFFFFFF), // White
    onSurface = Color(0xFF10192A), // Navy
    surfaceVariant = Color(0xFFE3EBF3), // Light Blue Gray
    onSurfaceVariant = Color(0xFF414A56), // Dark Slate
    outline = Color(0xFF71809A), // Blue Gray
    outlineVariant = Color(0xFFC3CFDD), // Light Blue Gray
    inverseSurface = Color(0xFF1E2A3C), // Dark Navy
    inverseOnSurface = Color(0xFFF4F8FC), // Very Light Blue
    inversePrimary = Color(0xFFB9D4F0), // Light Blue
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF0F3057), // FILTERNET Navy
    surfaceContainerLowest = Color(0xFFFFFFFF), // White
    surfaceContainerLow = Color(0xFFF5F8FC), // Very Light Blue
    surfaceContainer = Color(0xFFEFF3F8), // Light Blue
    surfaceContainerHigh = Color(0xFFE9EEF5), // Light Blue
    surfaceContainerHighest = Color(0xFFE2E8F0), // Light Blue
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFF6FD3F2), // Bright Cyan Blue
    onPrimary = Color(0xFF003347), // Deep Navy
    primaryContainer = Color(0xFF0E4A66), // Navy Blue
    onPrimaryContainer = Color(0xFFD9F4FF), // Pale Cyan
    secondary = Color(0xFF2ED3E8), // Cyan
    onSecondary = Color(0xFF003741), // Deep Teal
    secondaryContainer = Color(0xFF004A57), // Dark Teal
    onSecondaryContainer = Color(0xFFCBF6FF), // Pale Cyan
    tertiary = Color(0xFFA7BDFF), // Soft Blue
    onTertiary = Color(0xFF1B2A55), // Deep Blue
    tertiaryContainer = Color(0xFF27365E), // Dark Blue
    onTertiaryContainer = Color(0xFFDCE3FF), // Pale Blue
    error = Color(0xFFFFB4AB), // Light Red
    errorContainer = Color(0xFF93000A), // Dark Red
    onError = Color(0xFF690005), // Deep Red
    onErrorContainer = Color(0xFFFFDAD6), // Light Red
    background = Color(0xFF0A111E), // Near Black Navy
    onBackground = Color(0xFFE6EDF5), // Light Blue
    surface = Color(0xFF0A111E), // Near Black Navy
    onSurface = Color(0xFFE6EDF5), // Light Blue
    surfaceVariant = Color(0xFF414A56), // Dark Slate
    onSurfaceVariant = Color(0xFFC3CBD6), // Light Slate
    outline = Color(0xFF8C99AB), // Blue Gray
    outlineVariant = Color(0xFF414A56), // Dark Slate
    inverseSurface = Color(0xFFE6EDF5), // Light Blue
    inverseOnSurface = Color(0xFF10192A), // Navy
    inversePrimary = Color(0xFF0F3057), // FILTERNET Navy
    scrim = Color(0xFF000000), // Black
    surfaceTint = Color(0xFF6FD3F2), // Bright Cyan Blue
    surfaceContainerLowest = Color(0xFF060B14), // Deeper Navy Black
    surfaceContainerLow = Color(0xFF0E1626), // Dark Navy
    surfaceContainer = Color(0xFF10192A), // FILTERNET Navy
    surfaceContainerHigh = Color(0xFF1A2436), // Medium Navy
    surfaceContainerHighest = Color(0xFF253044), // Lighter Navy
)

// Semantic Colors
val colorPing = Color(0xFF009966) // Green
val colorPingRed = Color(0xFFFF0099) // Pink Red
val colorConfigType = Color(0xFF0B7C92) // Teal Cyan

// FILTERNET: per-protocol badge colors so the server type is recognizable at a glance.
val colorTypeVless = Color(0xFF4F8DF7) // Blue
val colorTypeVmess = Color(0xFF00BCD4) // Cyan
val colorTypeTrojan = Color(0xFF34C759) // Green
val colorTypeShadowsocks = Color(0xFFFF9F0A) // Orange
val colorTypeHysteria = Color(0xFFAF7BFF) // Purple
val colorTypeWireguard = Color(0xFFFF6482) // Pink
val colorTypeOther = Color(0xFF9AA5B1) // Gray
val colorFabActive = Color(0xFF1D4ED8) // Brand Blue
val colorFabInactiveLight = Color(0xFF9C9C9C) // Gray
val colorFabInactiveDark = Color(0xFF646464) // Dark Gray
val dividerColorLight = Color(0xFFE0E0E0) // Light Gray
val dividerColorDark = Color(0xFF424242) // Dark Gray

// Toast Colors 85%
val toastNormalBgLight = Color(0xD9353A3E) // Dark Gray
val toastNormalBgDark = Color(0xD94A4F54) // Darker Gray
val toastSuccessBg = Color(0xD9388E3C) // Green
val toastErrorBg = Color(0xD9D50000) // Red
val toastInfoBg = Color(0xD90F3057) // Navy Blue
val toastIconCircleBg = Color(0x33FFFFFF) // Semi-transparent White
val toastTextColor = Color.White // White

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
        _themeMode.value =
            MmkvManager.decodeSettingsString(AppConfig.PREF_UI_MODE_NIGHT, "0") ?: "0"
        _dynamicColorEnabled.value =
            MmkvManager.decodeSettingsBool(AppConfig.PREF_DYNAMIC_COLOR, false)
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
    content: @Composable () -> Unit
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
            val window = activity.window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalDarkTheme provides darkTheme,
        LocalAppSnackbar provides snackbarController
    ) {
        MaterialTheme(
            colorScheme = colorScheme
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}
