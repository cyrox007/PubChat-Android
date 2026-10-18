package ru.pubchat.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Keep these colors aligned with frontend/src/assets/main.css in cyrox007/PubChat.
// Disable wallpaper-derived Material You colors so the brand stays consistent.
private val light = lightColorScheme(
    primary = Color(0xFF8C2F67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF7E8F0),
    onPrimaryContainer = Color(0xFF5F1F45),
    secondary = Color(0xFF6F6863),
    onSecondary = Color.White,
    background = Color(0xFFF6F4F1),
    onBackground = Color(0xFF272321),
    surface = Color.White,
    onSurface = Color(0xFF272321),
    surfaceVariant = Color(0xFFEFEAE6),
    onSurfaceVariant = Color(0xFF6F6863),
    outline = Color(0xFFCFC6BF),
    outlineVariant = Color(0xFFE2DCD6),
    error = Color(0xFFB53A3A),
    onError = Color.White,
)
private val dark = darkColorScheme(
    primary = Color(0xFFE07BAD),
    onPrimary = Color(0xFF24151E),
    primaryContainer = Color(0xFF402536),
    onPrimaryContainer = Color(0xFFF5DAE8),
    secondary = Color(0xFFB9B1AB),
    onSecondary = Color(0xFF161718),
    background = Color(0xFF161718),
    onBackground = Color(0xFFF3EFEC),
    surface = Color(0xFF202224),
    onSurface = Color(0xFFF3EFEC),
    surfaceVariant = Color(0xFF2B2E30),
    onSurfaceVariant = Color(0xFFB9B1AB),
    outline = Color(0xFF4A4F52),
    outlineVariant = Color(0xFF363A3C),
    error = Color(0xFFFF8C8C),
    onError = Color(0xFF251314),
)

@Composable
fun PubChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) dark else light,
        typography = Typography,
        content = content,
    )
}
