package ru.pubchat.app.core.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import ru.pubchat.app.core.network.PubChatApi
import ru.pubchat.app.core.session.SessionStore
import java.util.concurrent.Executors

/**
 * FCM is optional until a project-specific google-services.json is installed.
 * The backend never gets a token for an unauthenticated account.
 */
object FcmRegistration {
    private val executor = Executors.newSingleThreadExecutor()

    fun register(context: Context) {
        val app = context.applicationContext
        if (FirebaseApp.getApps(app).isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (token.isNotBlank()) registerToken(app, token)
        }
    }

    fun registerToken(context: Context, token: String) {
        val app = context.applicationContext
        executor.execute {
            val session = SessionStore(app)
            if (session.accessToken().isNullOrBlank()) return@execute
            val api = PubChatApi(
                deviceUid = { session.deviceUid },
                accessToken = { session.accessToken() },
                refreshCookie = { session.refreshCookie() },
            )
            runCatching { api.registerNativePush(session.deviceUid, token) }
        }
    }

    fun unregister(context: Context, onComplete: () -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val session = SessionStore(app)
            if (!session.accessToken().isNullOrBlank()) {
                val api = PubChatApi(
                    deviceUid = { session.deviceUid },
                    accessToken = { session.accessToken() },
                    refreshCookie = { session.refreshCookie() },
                )
                runCatching { api.removeNativePush(session.deviceUid) }
            }
            onComplete()
        }
    }
}
