package ru.pubchat.app.core.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build

/** Audio is exclusively owned for the duration of a live WebRTC session. */
class CallAudioRouter(context: Context) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var previousMode: Int? = null
    private var previousSpeaker: Boolean? = null
    private var previousMicrophoneMute: Boolean? = null
    private var currentSpeaker = false

    fun start(video: Boolean) {
        if (previousMode != null) return
        previousMode = audio.mode
        previousSpeaker = audio.isSpeakerphoneOn
        previousMicrophoneMute = audio.isMicrophoneMute
        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .build()
            audio.requestAudioFocus(request)
            focusRequest = request
        }
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        selectSpeaker(video)
    }

    fun selectSpeaker(enabled: Boolean) {
        currentSpeaker = enabled
        if (Build.VERSION.SDK_INT >= 31) {
            val type = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audio.availableCommunicationDevices.firstOrNull { it.type == type }
            if (device != null) audio.setCommunicationDevice(device)
            else if (!enabled) audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = enabled
        }
    }

    fun isSpeakerSelected(): Boolean = currentSpeaker

    fun release() {
        if (previousMode == null) return
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = previousSpeaker ?: false
        }
        audio.isMicrophoneMute = previousMicrophoneMute ?: false
        audio.mode = previousMode ?: AudioManager.MODE_NORMAL
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
        previousMode = null
        previousSpeaker = null
        previousMicrophoneMute = null
        currentSpeaker = false
    }
}
