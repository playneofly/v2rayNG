package com.v2ray.ang.ui.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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

private val LightColor = lightColorScheme(
    primary = Color(0xFF6D3BE8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF250067),
    secondary = Color(0xFF007C91),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8EAFA),
    onSecondaryContainer = Color(0xFF001F26),
    tertiary = Color(0xFF006B55),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF8EF8D3),
    onTertiaryContainer = Color(0xFF002018),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onError = Color.White,
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F7FF),
    onBackground = Color(0xFF1C1A22),
    surface = Color(0xFFF9F7FF),
    onSurface = Color(0xFF1C1A22),
    surfaceVariant = Color(0xFFE8E0EE),
    onSurfaceVariant = Color(0xFF4A454F),
    outline = Color(0xFF7B757F),
    outlineVariant = Color(0xFFCCC4D0),
    inverseSurface = Color(0xFF312F36),
    inverseOnSurface = Color(0xFFF4EFF8),
    inversePrimary = Color(0xFFCFBCFF),
    scrim = Color.Black,
    surfaceTint = Color(0xFF6D3BE8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F0FA),
    surfaceContainer = Color(0xFFEDEAF4),
    surfaceContainerHigh = Color(0xFFE7E4EE),
    surfaceContainerHighest = Color(0xFFE1DEE8),
)

private val DarkColor = darkColorScheme(
    primary = Color(0xFFB99AFF),
    onPrimary = Color(0xFF24005D),
    primaryContainer = Color(0xFF4B1EA7),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = FilternetTokens.Cyan,
    onSecondary = Color(0xFF003640),
    secondaryContainer = Color(0xFF004E5C),
    onSecondaryContainer = Color(0xFFA6EEFF),
    tertiary = FilternetTokens.Emerald,
    onTertiary = Color(0xFF003829),
    tertiaryContainer = Color(0xFF00513D),
    onTertiaryContainer = Color(0xFF84F8CC),
    error = Color(0xFFFFB3BA),
    errorContainer = Color(0xFF930019),
    onError = Color(0xFF67000E),
    onErrorContainer = Color(0xFFFFDADF),
    background = FilternetTokens.Ink,
    onBackground = Color(0xFFF3EFFB),
    surface = FilternetTokens.Ink,
    onSurface = Color(0xFFF3EFFB),
    surfaceVariant = Color(0xFF494351),
    onSurfaceVariant = Color(0xFFCCC4D1),
    outline = Color(0xFF958E9C),
    outlineVariant = Color(0xFF494351),
    inverseSurface = Color(0xFFF3EFFB),
    inverseOnSurface = Color(0xFF302D35),
    inversePrimary = Color(0xFF6D3BE8),
    scrim = Color.Black,
    surfaceTint = Color(0xFFB99AFF),
    surfaceContainerLowest = Color(0xFF050309),
    surfaceContainerLow = Color(0xFF100D17),
    surfaceContainer = Color(0xFF15111E),
    surfaceContainerHigh = Color(0xFF201A2B),
    surfaceContainerHighest = Color(0xFF2A2336),
)

private val FilternetShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

val colorPing = Color(0xFF34E0A1)
val colorPingRed = Color(0xFFFF5D73)
val colorConfigType = Color(0xFF22D3EE)
val colorTypeVless = Color(0xFF7CA8FF)
val colorTypeVmess = Color(0xFF22D3EE)
val colorTypeTrojan = Color(0xFF34E0A1)
val colorTypeShadowsocks = Color(0xFFFFB347)
val colorTypeHysteria = Color(0xFFB99AFF)
val colorTypeWireguard = Color(0xFFFF7A98)
val colorTypeOther = Color(0xFFA39BAC)
val colorFabActive = Color(0xFF8B5CF6)
val colorFabInactiveLight = Color(0xFF8B8491)
val colorFabInactiveDark = Color(0xFF5F5968)
val dividerColorLight = Color(0xFFE1DAE7)
val dividerColorDark = Color(0xFF332D3D)

val toastNormalBgLight = Color(0xE63A3541)
val toastNormalBgDark = Color(0xE6231E2B)
val toastSuccessBg = Color(0xE6007D58)
val toastErrorBg = Color(0xE69A1831)
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
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = FilternetShapes,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AppSnackbarBridge(controller = snackbarController)
                content()
                AppSnackbarHost(hostState = snackbarController.hostState)
            }
        }
    }
}