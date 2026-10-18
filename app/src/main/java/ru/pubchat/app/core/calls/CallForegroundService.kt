package ru.pubchat.app.core.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ru.pubchat.app.MainActivity
import ru.pubchat.app.R

/**
 * Only holds an already-authorized call that was started/answered in the
 * foreground. A push message must NEVER start camera/microphone from background.
 */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Звонки PubChat", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (video) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, activeNotification(video), foregroundType
        )
        return START_NOT_STICKY
    }

    private fun activeNotification(video: Boolean): Notification {
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 104, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Звонок PubChat")
            .setContentText(if (video) "Идёт видеозвонок" else "Идёт голосовой звонок")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    companion object {
        private const val CHANNEL = "pubchat_active_calls"
        private const val NOTIFICATION_ID = 1460
        private const val EXTRA_VIDEO = "video"
        private const val ACTION_STOP = "ru.pubchat.app.call.STOP"

        fun start(context: android.content.Context, video: Boolean) {
            val intent = Intent(context, CallForegroundService::class.java)
                .putExtra(EXTRA_VIDEO, video)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
