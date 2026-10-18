package ru.pubchat.app.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ru.pubchat.app.MainActivity
import ru.pubchat.app.R

class PubChatFirebaseService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FcmRegistration.registerToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // No sender/DM content in OS notifications: server sends generic privacy-safe text.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val incomingCall = message.data["type"] == "call"
        val channel = if (incomingCall) CALL_CHANNEL else CHANNEL
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                channel,
                if (incomingCall) "Входящие звонки PubChat" else "Сообщения PubChat",
                if (incomingCall) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            ))
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val callUid = message.data["call_uid"]?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
        fun callAction(label: String, actionName: String, requestCode: Int): NotificationCompat.Action {
            val target = Intent(this, MainActivity::class.java).apply {
                action = actionName
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("call_uid", callUid)
            }
            val actionIntent = PendingIntent.getActivity(
                this, requestCode, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Action.Builder(0, label, actionIntent).build()
        }
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (incomingCall) "Входящий звонок PubChat" else "Новое сообщение в PubChat")
            .setContentText(if (incomingCall) "Откройте приложение, чтобы ответить." else "У вас есть новое сообщение.")
            .setCategory(if (incomingCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (incomingCall) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        if (incomingCall && callUid != null) {
            val callNotification = NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Входящий звонок PubChat")
                .setContentText("Ответить или отклонить в приложении")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .addAction(callAction("Ответить", "ru.pubchat.app.call.ANSWER", 1701))
                .addAction(callAction("Отклонить", "ru.pubchat.app.call.DECLINE", 1702))
                .build()
            manager.notify(callUid.hashCode(), callNotification)
        } else {
            manager.notify(message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
        }
    }

    companion object {
        private const val CHANNEL = "pubchat_messages"
        private const val CALL_CHANNEL = "pubchat_incoming_calls"
    }
}
