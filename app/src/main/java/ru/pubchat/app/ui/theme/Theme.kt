package ru.pubchat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

// Shared PubChat 2026-10-18 design palette; see docs/DESIGN_REFERENCE_2026-10-18.md.
// Disable wallpaper-derived Material You colors so the brand stays consistent.
private val light = lightColorScheme(
    primary = Color(0xFF6347F5),
    tertiary = Color(0xFF119968),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECE9FF),
    secondaryContainer = Color(0xFFECE9FF),
    onSecondaryContainer = Color(0xFF4C33D6),
    onPrimaryContainer = Color(0xFF18203D),
    secondary = Color(0xFF69738F),
    onSecondary = Color.White,
    background = Color(0xFFF6F7FF),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFEDF0FB), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9FAFF),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFECEFFF), surfaceContainerHighest = Color(0xFFE2E6F5),
    inverseSurface = Color(0xFF171D34), inverseOnSurface = Color(0xFFF5F6FF), inversePrimary = Color(0xFF9A86FF),
    tertiaryContainer = Color(0xFFE5F2E9), onTertiaryContainer = Color(0xFF119968), onTertiary = Color.White,
    onBackground = Color(0xFF18203D),
    surface = Color.White,
    onSurface = Color(0xFF18203D),
    surfaceVariant = Color(0xFFECEFFF),
    onSurfaceVariant = Color(0xFF69738F),
    outline = Color(0xFFCDD3EC),
    outlineVariant = Color(0xFFE2E6F5),
    error = Color(0xFFDF4B64),
    onError = Color.White,
)
private val dark = darkColorScheme(
    primary = Color(0xFF9A86FF),
    tertiary = Color(0xFF56D3A2),
    onPrimary = Color(0xFF0D1125),
    primaryContainer = Color(0xFF2B2854),
    secondaryContainer = Color(0xFF2B2854),
    onSecondaryContainer = Color(0xFFF0EDFF),
    onPrimaryContainer = Color(0xFFF5F6FF),
    secondary = Color(0xFFB4BFDB),
    onSecondary = Color(0xFF0D1125),
    background = Color(0xFF0D1125),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF0D1125), surfaceBright = Color(0xFF27304C),
    surfaceContainerLowest = Color(0xFF0D1125), surfaceContainerLow = Color(0xFF141A30),
    surfaceContainer = Color(0xFF171D34), surfaceContainerHigh = Color(0xFF222B45), surfaceContainerHighest = Color(0xFF27304C),
    inverseSurface = Color(0xFFF6F7FF), inverseOnSurface = Color(0xFF18203D), inversePrimary = Color(0xFF6347F5),
    tertiaryContainer = Color(0xFF1E3A2E), onTertiaryContainer = Color(0xFF56D3A2), onTertiary = Color(0xFF0D1125),
    onBackground = Color(0xFFF5F6FF),
    surface = Color(0xFF171D34),
    onSurface = Color(0xFFF5F6FF),
    surfaceVariant = Color(0xFF27304C),
    onSurfaceVariant = Color(0xFFB4BFDB),
    outline = Color(0xFF465175),
    outlineVariant = Color(0xFF343D59),
    error = Color(0xFFFF8296),
    onError = Color(0xFF251314),
)

data class ThemeControl(val dark: Boolean, val toggle: () -> Unit)
val LocalThemeControl = staticCompositionLocalOf { ThemeControl(true) {} }

@Composable
fun PubChatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("pubchat_appearance", android.content.Context.MODE_PRIVATE) }
    var preferDark by remember { mutableStateOf(preferences.getBoolean("dark_theme", true)) }
    val useDark = darkTheme ?: preferDark
    SideEffect {
        (context as? android.app.Activity)?.let { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !useDark
                isAppearanceLightNavigationBars = !useDark
            }
        }
    }
    CompositionLocalProvider(LocalThemeControl provides ThemeControl(useDark) {
        preferDark = !useDark
        preferences.edit().putBoolean("dark_theme", preferDark).apply()
    }) {
      MaterialTheme(
        colorScheme = if (useDark) dark else light,
        typography = Typography,
        content = content,
    )
    }
}
