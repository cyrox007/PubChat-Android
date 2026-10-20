package ru.pubchat.app.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import ru.pubchat.app.R

/** The sounds are installed with the APK, not fetched from a server or a URL. */
object PubChatNotificationChannels {
    // Android 8+ channel sound is immutable once created. Use a NEW ID to migrate
    // existing installations that previously used the system-default sound.
    const val MESSENGER = "pubchat_messages_chime_v2"
    const val SPACE_REPLY = "pubchat_space_replies_chime_v2"
    const val CALL = "pubchat_incoming_calls"

    fun install(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val privateSound = Uri.parse("android.resource://${context.packageName}/${R.raw.pubchat_private_notification}")
        val spaceSound = Uri.parse("android.resource://${context.packageName}/${R.raw.pubchat_space_notification}")
        val messenger = NotificationChannel(MESSENGER, "Личные сообщения PubChat", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Новые личные сообщения с фирменным сигналом PubChat"
            setSound(privateSound, attributes)
            enableVibration(true)
        }
        val replies = NotificationChannel(SPACE_REPLY, "Ответы и упоминания в пространствах", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Персональные ответы на сообщения, а не все сообщения комнаты"
            setSound(spaceSound, attributes)
            enableVibration(true)
        }
        val calls = NotificationChannel(CALL, "Входящие звонки PubChat", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannels(listOf(messenger, replies, calls))
    }
}
