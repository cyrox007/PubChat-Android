package ru.pubchat.app.ui.components

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.pubchat.app.R
import ru.pubchat.app.core.network.PubChatApi
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal fun voiceClock(milliseconds: Int): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Small, inline, authenticated Space voice-note player.
 * The network call uses the existing authorized attachment loader; a private
 * URL is never sent to a public MediaPlayer request or an external app.
 */
@Composable
internal fun SpaceVoicePlayer(
    attachment: PubChatApi.Attachment,
    url: String,
    authenticated: Boolean,
    onPreviewAttachment: ((PubChatApi.Attachment, (File?, String?) -> Unit) -> Unit)?,
    onDownload: () -> Unit,
    canDownload: Boolean,
    downloading: Boolean,
) {
    var status by remember(attachment.url) { mutableStateOf("idle") }
    var player by remember(attachment.url) { mutableStateOf<MediaPlayer?>(null) }
    var durationMs by remember(attachment.url) { mutableIntStateOf(0) }
    var positionMs by remember(attachment.url) { mutableIntStateOf(0) }
    var localFile by remember(attachment.url) { mutableStateOf<File?>(null) }
    val alive = remember(attachment.url) { AtomicBoolean(true) }
    var requestEpoch by remember(attachment.url) { mutableIntStateOf(0) }
    val cachedSource = remember(attachment.url) { mutableStateOf<String?>(null) }

    DisposableEffect(attachment.url) {
        alive.set(true)
        onDispose {
            alive.set(false)
            requestEpoch++
            runCatching { player?.release() }
            player = null
            localFile?.delete()
            localFile = null
        }
    }

    LaunchedEffect(status, player) {
        while (status == "playing") {
            positionMs = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
                .coerceIn(0, durationMs.coerceAtLeast(0))
            delay(180)
        }
    }

    fun prepare(source: String) {
        runCatching {
            player?.release()
            player = null
            status = "loading"
            positionMs = 0
            val next = MediaPlayer().apply {
                setOnPreparedListener { ready ->
                    if (alive.get()) {
                        durationMs = ready.duration.coerceAtLeast(0)
                        ready.start()
                        status = "playing"
                    } else ready.release()
                }
                setOnCompletionListener { ready ->
                    if (alive.get()) {
                        status = "paused"
                        positionMs = 0
                        ready.seekTo(0)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    if (alive.get()) status = "error"
                    true
                }
                setDataSource(source)
                prepareAsync()
            }
            player = next
        }.onFailure { status = "error" }
    }

    fun togglePlayback() {
        when (status) {
            "playing" -> runCatching { player?.pause(); status = "paused" }
                .onFailure { status = "error" }
            "paused" -> runCatching { player?.start(); status = "playing" }
                .onFailure { status = "error" }
            "loading" -> Unit
            else -> {
                status = "loading"
                val alreadyLoaded = cachedSource.value
                if (alreadyLoaded != null) {
                    prepare(alreadyLoaded)
                } else if (authenticated) {
                    if (onPreviewAttachment == null) {
                        status = "error"
                        return
                    }
                    val epoch = ++requestEpoch
                    val audio = if (attachment.mimeType.isNullOrBlank()) {
                        attachment.copy(mimeType = "audio/mp4")
                    } else attachment
                    onPreviewAttachment(audio) { file, failure ->
                        if (!alive.get() || epoch != requestEpoch) {
                            file?.delete()
                        } else if (file == null || failure != null) {
                            file?.delete()
                            status = "error"
                        } else {
                            localFile?.delete()
                            localFile = file
                            cachedSource.value = file.absolutePath
                            prepare(file.absolutePath)
                        }
                    }
                } else {
                    cachedSource.value = url
                    prepare(url)
                }
            }
        }
    }

    Row(
        Modifier.widthIn(min = 200.dp, max = 270.dp).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        IconButton(
            onClick = ::togglePlayback,
            enabled = status != "loading",
            modifier = Modifier.size(48.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .semantics {
                    contentDescription = if (status == "playing") "Пауза голосового сообщения"
                    else "Воспроизвести голосовое сообщение"
                },
        ) {
            if (status == "loading") {
                CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    painter = painterResource(
                        if (status == "playing") R.drawable.ic_media_pause else R.drawable.ic_media_play
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                if (status == "error") "Ошибка воспроизведения" else "Голосовое сообщение",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                color = if (status == "error") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                onValueChange = { fraction ->
                    if (durationMs > 0) {
                        val target = (fraction * durationMs).toInt().coerceIn(0, durationMs)
                        positionMs = target
                        runCatching { player?.seekTo(target) }
                    }
                },
                enabled = durationMs > 0 && status in setOf("playing", "paused"),
                modifier = Modifier.fillMaxWidth().height(25.dp).semantics {
                    contentDescription = "Позиция голосового сообщения"
                },
            )
            Text(
                if (durationMs > 0) "${voiceClock(positionMs)} / ${voiceClock(durationMs)}"
                else if (status == "loading") "Загружаем запись…" else "Нажмите для прослушивания",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(
            onClick = onDownload,
            enabled = canDownload && !downloading,
            modifier = Modifier.size(40.dp).semantics {
                contentDescription = "Сохранить голосовое сообщение"
            },
        ) {
            if (downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(painterResource(R.drawable.ic_media_download), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
        }
    }
}
