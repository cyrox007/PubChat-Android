package ru.pubchat.app.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * Short voice notes for the existing Space attachment transport.
 * A recording is local until the user explicitly stops and submits it.
 */
internal class SpaceVoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start(): Unit {
        check(recorder == null) { "Voice recording already active" }
        val file = File(context.cacheDir, "pubchat_voice_" + UUID.randomUUID() + ".m4a")
        try {
            val next = MediaRecorder()
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(48_000)
            next.setAudioSamplingRate(24_000)
            next.setMaxDuration(60_000)
            next.setMaxFileSize(2L * 1024 * 1024)
            next.setOutputFile(file.absolutePath)
            next.prepare()
            next.start()
            recorder = next
            output = file
        } catch (error: Exception) {
            runCatching { recorder?.reset(); recorder?.release() }
            recorder = null
            file.delete()
            output = null
            throw error
        }
    }

    fun finish(): Uri {
        val current = requireNotNull(recorder) { "No active voice recording" }
        val file = requireNotNull(output)
        recorder = null
        output = null
        try {
            current.stop()
        } catch (error: RuntimeException) {
            file.delete()
            throw error
        } finally {
            current.release()
        }
        require(file.length() in 1..(2L * 1024 * 1024)) {
            file.delete()
            "Voice note is empty or exceeds 2 MB"
        }
        return Uri.fromFile(file)
    }

    fun cancel() {
        recorder?.let { current ->
            runCatching { current.stop() }
            current.release()
        }
        recorder = null
        output?.delete()
        output = null
    }
}
