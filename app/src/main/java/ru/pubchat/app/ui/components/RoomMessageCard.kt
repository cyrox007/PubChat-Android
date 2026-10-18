package ru.pubchat.app.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import ru.pubchat.app.BuildConfig
import ru.pubchat.app.core.network.PubChatApi
import java.net.URI
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal fun publicMediaUrl(value: String?): String? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        val base = URI(BuildConfig.API_BASE_URL)
        val uri = base.resolve(value)
        uri.takeIf { it.scheme in setOf("https", "http") && it.host != null }?.toString()
    }.getOrNull()
}

private fun messageTime(value: String?): String = runCatching {
    val raw = value ?: return ""
    val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }
        .getOrElse { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC) }
    DateTimeFormatter.ofPattern("dd.MM · HH:mm").withZone(java.time.ZoneId.systemDefault()).format(instant)
}.getOrDefault("")

@Composable
internal fun RoomMessageCard(
    message: PubChatApi.ChatMessage, own: Boolean,
    replySource: PubChatApi.ChatMessage? = null,
    onReplyJump: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Card(Modifier.widthIn(max = 340.dp).let { if (onSelect != null) it.clickable(onClick = onSelect) else it },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    val avatar = publicMediaUrl(message.senderAvatar)
                    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                        Text(message.senderName?.take(1)?.uppercase() ?: "?", color = MaterialTheme.colorScheme.primary)
                        if (avatar != null) AsyncImage(avatar, "Аватар автора", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(message.senderName ?: if (own) "Вы" else "Участник", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    if (onReply != null) TextButton(onClick = onReply, contentPadding = PaddingValues(2.dp), modifier = Modifier.height(28.dp)) { Text("↩", style = MaterialTheme.typography.titleMedium) }
                }
                if (message.replyToUid != null) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.background.copy(alpha = .55f))
                        .let { if (onReplyJump != null) it.clickable(onClick = onReplyJump) else it }.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.width(3.dp).height(35.dp).background(MaterialTheme.colorScheme.primary))
                        Column {
                            Text("↪ ${message.replySenderName ?: replySource?.senderName ?: "Ответ на сообщение"}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            Text(message.replyContent ?: replySource?.content ?: "Исходное сообщение недоступно", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (message.contentType == "deleted") Text("Сообщение удалено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    message.attachments.forEach { attachment -> MessageAttachment(attachment, message.contentType) }
                    if (message.content.isNotBlank() && message.attachments.none { it.url == message.content }) Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(messageTime(message.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (own) Text(if (message.isRead) "✓✓" else "✓", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MessageAttachment(attachment: PubChatApi.Attachment, contentType: String) {
    val url = publicMediaUrl(attachment.url)
    val uriHandler = LocalUriHandler.current
    var openError by remember(attachment.url) { mutableStateOf(false) }
    if (url == null) { Text("Вложение недоступно"); return }
    val image = attachment.mimeType?.startsWith("image/") == true || contentType == "image"
    val audio = attachment.mimeType?.startsWith("audio/") == true || contentType in setOf("audio", "voice")
    if (image) SubcomposeAsyncImage(url, attachment.name, Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp).clip(RoundedCornerShape(10.dp))
        .clickable { runCatching { uriHandler.openUri(url) }.onFailure { openError = true } }, contentScale = ContentScale.Fit,
        loading = { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } },
        error = { Box(Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { Text("Изображение недоступно · Открыть", style = MaterialTheme.typography.bodySmall) } })
    else if (audio) AudioMessage(url)
    else TextButton(onClick = { runCatching { uriHandler.openUri(url) }.onFailure { openError = true } }) {
        Text(if (contentType == "video") "▶ ${attachment.name}" else "📎 ${attachment.name}", maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (openError) Text("Не удалось открыть вложение", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AudioMessage(url: String) {
    var player by remember(url) { mutableStateOf<MediaPlayer?>(null) }
    var state by remember(url) { mutableStateOf("idle") }
    var duration by remember(url) { mutableStateOf(0) }
    DisposableEffect(url) { onDispose { player?.release() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            if (state == "playing") { player?.pause(); state = "paused" }
            else if (state == "paused") { player?.start(); state = "playing" }
            else {
                player?.release()
                state = "loading"
                runCatching {
                    player = MediaPlayer().apply {
                        setOnPreparedListener { duration = it.duration / 1000; it.start(); state = "playing" }
                        setOnCompletionListener { state = "idle" }
                        setOnErrorListener { _, _, _ -> state = "error"; true }
                        setDataSource(url); prepareAsync()
                    }
                }.onFailure { state = "error" }
            }
        }, enabled = state != "loading") { Text(if (state == "playing") "Ⅱ Пауза" else if (state == "loading") "Загрузка…" else "▶ Слушать") }
        Text(if (state == "error") "Не удалось воспроизвести" else if (duration > 0) "%d:%02d".format(duration / 60, duration % 60) else "Голосовое сообщение", style = MaterialTheme.typography.bodySmall)
    }
}
