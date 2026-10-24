package ru.pubchat.app.core.media

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/** Small bounded native AAC voice capture for already-supported Space audio attachments. */
internal class SpaceVoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    val isRecording: Boolean get() = recorder != null

    @Suppress("DEPRECATION")
    fun start() {
        check(!isRecording) { "Запись уже идёт" }
        val file = File.createTempFile("pubchat_voice_", ".m4a", context.cacheDir)
        try {
            val instance = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setMaxDuration(60_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = instance
            output = file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    fun finish(): File {
        val active = checkNotNull(recorder) { "Запись не начата" }
        val file = checkNotNull(output)
        recorder = null
        output = null
        try {
            active.stop()
            require(file.length() in 1..2_097_152) { "Голосовое сообщение пустое или превышает 2 МБ" }
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        } finally {
            active.reset()
            active.release()
        }
    }

    fun cancel() {
        val active = recorder
        recorder = null
        val file = output
        output = null
        if (active != null) {
            runCatching { active.stop() }
            active.reset()
            active.release()
        }
        file?.delete()
    }
}
