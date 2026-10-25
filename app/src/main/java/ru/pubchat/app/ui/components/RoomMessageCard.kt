package ru.pubchat.app.ui.components

import android.media.MediaPlayer
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.widget.VideoView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
    onSaveAttachment: ((PubChatApi.Attachment, Uri, (String?) -> Unit) -> Unit)? = null,
    onPreviewAttachment: ((PubChatApi.Attachment, (File?, String?) -> Unit) -> Unit)? = null,
) {
    // The bubble owns only content and metadata; a 48dp touch target for reply
    // sits *beside* it instead of stretching every short message to 100+dp.
    @Composable
    fun ReplyAction() {
        if (onReply != null) {
            IconButton(
                onClick = onReply,
                modifier = Modifier.size(48.dp).semantics {
                    contentDescription = "Ответить на сообщение"
                },
            ) {
                Text("↩", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
    ) {
        if (own) ReplyAction()
        Surface(
            modifier = Modifier.widthIn(max = 310.dp)
                .let { if (onSelect != null) it.clickable(onClick = onSelect) else it },
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (own) 16.dp else 5.dp,
                bottomEnd = if (own) 5.dp else 16.dp,
            ),
            color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!own) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val avatar = publicMediaUrl(message.senderAvatar)
                        Box(
                            Modifier.size(23.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(message.senderName?.take(1)?.uppercase() ?: "?", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary)
                            if (avatar != null) AsyncImage(
                                avatar, "Аватар автора", Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Text(
                            message.senderName ?: "Участник",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (message.replyToUid != null) {
                    Row(
                        Modifier.widthIn(min = 128.dp, max = 270.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f))
                            .let {
                                if (onReplyJump != null) it.clickable(
                                    onClickLabel = "Открыть исходное сообщение",
                                    onClick = onReplyJump,
                                ) else it
                            }
                            .heightIn(min = if (onReplyJump != null) 48.dp else 0.dp)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).height(28.dp)
                            .background(MaterialTheme.colorScheme.primary))
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                message.replySenderName ?: replySource?.senderName ?: "Ответ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                message.replyContent ?: replySource?.content ?: "Исходное сообщение недоступно",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (message.contentType == "deleted") {
                    Text("Сообщение удалено",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    message.attachments.forEach { attachment ->
                        MessageAttachment(attachment, message.contentType, onSaveAttachment, onPreviewAttachment)
                    }
                    if (message.content.isNotBlank() &&
                        message.attachments.none { it.url == message.content }) {
                        Text(message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        messageTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (own) {
                        Text(
                            if (message.isRead) "✓✓" else "✓",
                            modifier = Modifier.semantics {
                                contentDescription = if (message.isRead) "Прочитано" else "Отправлено"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (!own) ReplyAction()
    }
}

@Composable
private fun MessageAttachment(
    attachment: PubChatApi.Attachment,
    contentType: String,
    onSaveAttachment: ((PubChatApi.Attachment, Uri, (String?) -> Unit) -> Unit)?,
    onPreviewAttachment: ((PubChatApi.Attachment, (File?, String?) -> Unit) -> Unit)?,
) {
    val url = publicMediaUrl(attachment.url)
    val context = LocalContext.current
    var openError by remember(attachment.url) { mutableStateOf(false) }
    var savedUri by remember(attachment.url) { mutableStateOf<Uri?>(null) }
    var saving by remember(attachment.url) { mutableStateOf(false) }
    var preview by remember(attachment.url) { mutableStateOf<String?>(null) }
    var previewLoading by remember(attachment.url) { mutableStateOf(false) }
    var previewFailure by remember(attachment.url) { mutableStateOf<String?>(null) }
    var previewEpoch by remember(attachment.url) { mutableIntStateOf(0) }
    val previewFile = remember(attachment.url) { mutableStateOf<File?>(null) }
    val alive = remember(attachment.url) { AtomicBoolean(true) }
    DisposableEffect(attachment.url) {
        alive.set(true)
        onDispose {
            alive.set(false)
            previewEpoch++
            previewFile.value?.delete()
            previewFile.value = null
        }
    }
    val authenticated = ru.pubchat.app.core.network.authorizedMediaUrl(
        BuildConfig.API_BASE_URL, attachment.url) != null
    fun closePreview() {
        previewEpoch++
        preview = null
        previewLoading = false
        previewFailure = null
        previewFile.value?.delete()
        previewFile.value = null
    }
    fun openPreview(kind: String) {
        closePreview()
        preview = kind
        if (authenticated && onPreviewAttachment != null) {
            val epoch = previewEpoch
            previewLoading = true
            // Legacy attachments can omit MIME while carrying the content type.
            val media = if (attachment.mimeType.isNullOrBlank()) {
                attachment.copy(mimeType = when (kind) {
                    "image" -> "image/jpeg"
                    "audio" -> "audio/mp4"
                    "video" -> "video/mp4"
                    else -> "application/octet-stream"
                })
            } else attachment
            onPreviewAttachment(media) { file, failure ->
                if (!alive.get() || previewEpoch != epoch || preview != kind) {
                    file?.delete()
                } else {
                    previewLoading = false
                    previewFailure = failure
                    previewFile.value = file
                }
            }
        }
    }
    val fileName = attachment.name.substringAfterLast('/').substringAfterLast('\\')
        .filter { it != '/' && it != '\\' && it.code >= 32 }.take(128)
        .ifBlank { "pubchat-file" }
    val mime = attachment.mimeType?.takeIf { it.contains('/') } ?: "application/octet-stream"
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(mime),
    ) { target ->
        if (target != null && onSaveAttachment != null) {
            saving = true
            openError = false
            onSaveAttachment(attachment, target) { failure ->
                saving = false
                if (failure != null) openError = true else savedUri = target
            }
        }
    }
    if (url == null) { Text("Вложение недоступно"); return }
    val image = attachment.mimeType?.startsWith("image/") == true || contentType == "image"
    val audio = attachment.mimeType?.startsWith("audio/") == true || contentType in setOf("audio", "voice")
    val video = attachment.mimeType?.startsWith("video/") == true || contentType == "video"
    if (image && authenticated && onPreviewAttachment != null) {
        TextButton(onClick = { openPreview("image") }, enabled = !previewLoading) {
            Text("🖼 Просмотреть изображение", maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    } else if (image) SubcomposeAsyncImage(
        url, attachment.name,
        Modifier.fillMaxWidth().heightIn(min = 64.dp, max = 180.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { openPreview("image") },
        contentScale = ContentScale.Fit,
        loading = { Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } },
        error = { Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
            Text("Изображение недоступно", style = MaterialTheme.typography.bodySmall)
        } },
    )
    else if (audio) {
        SpaceVoicePlayer(
            attachment = attachment,
            url = url,
            authenticated = authenticated,
            onPreviewAttachment = onPreviewAttachment,
            onDownload = { createDocument.launch(fileName) },
            canDownload = onSaveAttachment != null,
            downloading = saving,
        )
    }
    else if (video) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f),
            modifier = Modifier.widthIn(min = 200.dp, max = 260.dp)
                .heightIn(min = 66.dp).clickable(
                    onClickLabel = "Посмотреть видеосообщение",
                    onClick = { openPreview("video") },
                ),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(ru.pubchat.app.R.drawable.ic_video_note),
                        contentDescription = null, modifier = Modifier.size(23.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text("Видеосообщение", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text("Нажмите для просмотра", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(ru.pubchat.app.R.drawable.ic_media_play),
                    contentDescription = null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    else TextButton(onClick = { createDocument.launch(fileName) },
        enabled = onSaveAttachment != null && !saving) {
        Text(if (saving) "Сохраняем…" else attachment.name.take(56),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (image || video) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(
                onClick = { createDocument.launch(fileName) },
                enabled = onSaveAttachment != null && !saving,
                modifier = Modifier.size(40.dp).semantics {
                    contentDescription = if (video) "Сохранить видеосообщение" else "Сохранить изображение"
                },
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(painterResource(ru.pubchat.app.R.drawable.ic_media_download),
                    contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    savedUri?.let { document ->
        TextButton(onClick = {
            runCatching {
                val open = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(document, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(open)
            }.onFailure { openError = true }
        }) { Text("Открыть сохранённый файл") }
    }
    if (preview != null) {
        val kind = preview
        androidx.compose.ui.window.Dialog(onDismissRequest = ::closePreview) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::closePreview) { Text("Закрыть") }
                    }
                    if (previewLoading) {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        Text("Загружаем медиа внутри приложения…")
                    } else if (previewFailure != null) {
                        Text(previewFailure ?: "Не удалось загрузить",
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { kind?.let(::openPreview) }) { Text("Повторить") }
                    } else {
                        val local = previewFile.value
                        val source: Any = if (authenticated) local ?: "" else url
                        if (kind == "image") {
                            var zoom by remember(source) { mutableFloatStateOf(1f) }
                            AsyncImage(
                                model = source,
                                contentDescription = attachment.name,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 540.dp)
                                    .pointerInput(source) {
                                        detectTransformGestures { _, _, factor, _ ->
                                            zoom = (zoom * factor).coerceIn(1f, 4f)
                                        }
                                    }.graphicsLayer(scaleX = zoom, scaleY = zoom),
                                contentScale = ContentScale.Fit,
                            )
                            Text("Масштаб: ${(zoom * 100).toInt()}% — разведите пальцы для увеличения",
                                style = MaterialTheme.typography.labelSmall)
                        } else if (kind == "audio") {
                            if (local != null) AudioMessage(local.absolutePath) else AudioMessage(url)
                        } else if (kind == "video") {
                            val uri = local?.let(Uri::fromFile) ?: Uri.parse(url)
                            AndroidView(
                                factory = { ctx ->
                                    VideoView(ctx).apply {
                                        setVideoURI(uri)
                                        setMediaController(android.widget.MediaController(ctx))
                                        setOnPreparedListener { player -> player.isLooping = false; start() }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(260.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (openError) Text("Не удалось загрузить вложение", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
