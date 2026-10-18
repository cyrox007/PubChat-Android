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
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "Сообщения PubChat", NotificationManager.IMPORTANCE_DEFAULT
            ))
        }
        val pending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Новое сообщение в PubChat")
            .setContentText("У вас есть новое сообщение.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        private const val CHANNEL = "pubchat_messages"
    }
}
