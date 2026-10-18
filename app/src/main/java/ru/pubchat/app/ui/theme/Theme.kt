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

// Keep these colors aligned with frontend/src/assets/main.css in cyrox007/PubChat.
// Disable wallpaper-derived Material You colors so the brand stays consistent.
private val light = lightColorScheme(
    primary = Color(0xFF6045F6),
    tertiary = Color(0xFF16A46C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FF),
    secondaryContainer = Color(0xFFEDE9FF),
    onSecondaryContainer = Color(0xFF3421A4),
    onPrimaryContainer = Color(0xFF111936),
    secondary = Color(0xFF64718E),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FE),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFFF1F3FC), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFBFCFF),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF1F3FC), surfaceContainerHighest = Color(0xFFE7EAF6),
    inverseSurface = Color(0xFF191F35), inverseOnSurface = Color(0xFFF4F6FF), inversePrimary = Color(0xFFA596FF),
    tertiaryContainer = Color(0xFFE6F9F0), onTertiaryContainer = Color(0xFF16A46C), onTertiary = Color.White,
    onBackground = Color(0xFF111936),
    surface = Color.White,
    onSurface = Color(0xFF111936),
    surfaceVariant = Color(0xFFF1F3FC),
    onSurfaceVariant = Color(0xFF64718E),
    outline = Color(0xFFC9D0E6),
    outlineVariant = Color(0xFFE7EAF6),
    error = Color(0xFFF04461),
    onError = Color.White,
)
private val dark = darkColorScheme(
    primary = Color(0xFFA596FF),
    tertiary = Color(0xFF74DBAC),
    onPrimary = Color(0xFF0F1426),
    primaryContainer = Color(0xFF302957),
    secondaryContainer = Color(0xFF302957),
    onSecondaryContainer = Color(0xFFF4F6FF),
    onPrimaryContainer = Color(0xFFF4F6FF),
    secondary = Color(0xFFA5AED0),
    onSecondary = Color(0xFF0F1426),
    background = Color(0xFF0F1426),
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF0F1426), surfaceBright = Color(0xFF29324F),
    surfaceContainerLowest = Color(0xFF0F1426), surfaceContainerLow = Color(0xFF151B2F),
    surfaceContainer = Color(0xFF191F35), surfaceContainerHigh = Color(0xFF222A44), surfaceContainerHighest = Color(0xFF29324F),
    inverseSurface = Color(0xFFF7F8FE), inverseOnSurface = Color(0xFF111936), inversePrimary = Color(0xFF6045F6),
    tertiaryContainer = Color(0xFF194435), onTertiaryContainer = Color(0xFF74DBAC), onTertiary = Color(0xFF0F1426),
    onBackground = Color(0xFFF4F6FF),
    surface = Color(0xFF191F35),
    onSurface = Color(0xFFF4F6FF),
    surfaceVariant = Color(0xFF29324F),
    onSurfaceVariant = Color(0xFFA5AED0),
    outline = Color(0xFF4B5572),
    outlineVariant = Color(0xFF343E5B),
    error = Color(0xFFFF8BA1),
    onError = Color(0xFF0F1426),
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
    var preferDark by remember { mutableStateOf(preferences.getBoolean("dark_theme", false)) }
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
