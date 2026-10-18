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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import ru.pubchat.app.BuildConfig
import ru.pubchat.app.core.network.PubChatApi
import java.net.URI
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal fun publicMediaUrl(value: String?): String? = ru.pubchat.app.core.network.resolveMediaUrl(BuildConfig.API_BASE_URL, value)

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
        Surface(Modifier.widthIn(max = 310.dp).let { if (onSelect != null) it.clickable(onClick = onSelect) else it },
            shape = RoundedCornerShape(12.dp),
            color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val avatar = publicMediaUrl(message.senderAvatar)
                    Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                        Text(message.senderName?.take(1)?.uppercase() ?: "?", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        if (avatar != null) AsyncImage(avatar, "Аватар автора", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text(message.senderName ?: if (own) "Вы" else "Участник", color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
                    Text(messageTime(message.createdAt) + if (own) (if (message.isRead) " ✓✓" else " ✓") else "",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onReply != null) Box(Modifier.size(26.dp).semantics { contentDescription = "Ответить на сообщение" }.clickable(onClick = onReply), contentAlignment = Alignment.Center) {
                        Text("↩", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (message.replyToUid != null) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = .65f))
                        .let { if (onReplyJump != null) it.clickable(onClick = onReplyJump) else it }.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.width(2.dp).height(30.dp).background(MaterialTheme.colorScheme.primary))
                        Column {
                            Text("↪ ${message.replySenderName ?: replySource?.senderName ?: "Ответ на сообщение"}", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                            Text(message.replyContent ?: replySource?.content ?: "Исходное сообщение недоступно", maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, lineHeight = 15.sp)
                        }
                    }
                }
                if (message.contentType == "deleted") Text("Сообщение удалено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    message.attachments.forEach { attachment -> MessageAttachment(attachment, message.contentType) }
                    if (message.content.isNotBlank() && message.attachments.none { it.url == message.content }) Text(message.content, fontSize = 14.sp, lineHeight = 18.sp)
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
    if (image) SubcomposeAsyncImage(url, attachment.name, Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 180.dp).clip(RoundedCornerShape(10.dp))
        .clickable { runCatching { uriHandler.openUri(url) }.onFailure { openError = true } }, contentScale = ContentScale.Fit,
        loading = { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } },
        error = { Box(Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { Text("Изображение недоступно · Открыть", style = MaterialTheme.typography.bodySmall) } })
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
        }, enabled = state != "loading", contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) { Text(if (state == "playing") "Ⅱ" else if (state == "loading") "…" else "▶", fontSize = 18.sp) }
        Text(if (state == "error") "Не удалось воспроизвести" else if (duration > 0) "%d:%02d".format(duration / 60, duration % 60) else "Голосовое сообщение", fontSize = 12.sp)
    }
}
