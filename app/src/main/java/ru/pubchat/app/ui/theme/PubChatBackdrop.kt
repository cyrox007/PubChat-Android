package ru.pubchat.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawRect

/**
 * Quiet ambient depth from the approved PubChat boards. Brushes are cached
 * per size/theme; no shaders, repeating image, scroll animation, or bitmap IO.
 */
@Composable
fun Modifier.pubChatBackdrop(): Modifier {
    val base = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    return this.drawWithCache {
        val backdrop = Brush.verticalGradient(
            colors = listOf(
                Color.lerp(base, primary, .045f),
                base,
                Color.lerp(base, tertiary, .030f),
            ),
        )
        val topGlow = Brush.radialGradient(
            colors = listOf(primary.copy(alpha = .075f), Color.Transparent),
            center = Offset(size.width * .82f, size.height * .04f),
            radius = size.width.coerceAtLeast(1f) * .90f,
        )
        val bottomGlow = Brush.radialGradient(
            colors = listOf(tertiary.copy(alpha = .055f), Color.Transparent),
            center = Offset(size.width * .05f, size.height * .88f),
            radius = size.width.coerceAtLeast(1f) * .75f,
        )
        onDrawBehind {
            drawRect(brush = backdrop)
            drawRect(brush = topGlow)
            drawRect(brush = bottomGlow)
        }
    }
}
