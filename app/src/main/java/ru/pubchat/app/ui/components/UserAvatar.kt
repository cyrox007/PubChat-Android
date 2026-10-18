package ru.pubchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
internal fun UserAvatar(name: String, avatar: String?, size: Dp = 48.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).map { it.take(1).uppercase() }.joinToString(""),
            fontSize = (size.value * .34f).sp, color = MaterialTheme.colorScheme.primary)
        publicMediaUrl(avatar)?.let { AsyncImage(it, "Аватар: $name", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}