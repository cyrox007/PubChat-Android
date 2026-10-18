package ru.pubchat.app

import android.Manifest
import ru.pubchat.app.core.push.FcmRegistration
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import ru.pubchat.app.ui.theme.PubChatTheme

data class PubChatDeepLink(val kind: String, val uid: String)

fun parsePubChatDeepLink(uri: Uri?): PubChatDeepLink? {
    if (uri == null) return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val parts = uri.pathSegments
    val kind: String
    val uid: String
    if (scheme == "pubchat") {
        kind = uri.host?.lowercase() ?: return null
        uid = parts.firstOrNull() ?: return null
    } else if (scheme == "https" && uri.host?.lowercase() in setOf("pubchat.ru", "www.pubchat.ru")) {
        kind = parts.getOrNull(0)?.lowercase() ?: return null
        uid = parts.getOrNull(1) ?: return null
    } else return null
    if (kind !in setOf("spaces", "messenger")) return null
    if (runCatching { java.util.UUID.fromString(uid) }.isFailure) return null
    return PubChatDeepLink(kind, uid)
}

data class IncomingCallAction(val kind: String, val callUid: String)

class MainActivity : ComponentActivity() {
    private val callAction = mutableStateOf<IncomingCallAction?>(null)
    private fun resolveCallAction(intent: Intent?): IncomingCallAction? {
        val kind = when (intent?.action) {
            "ru.pubchat.app.call.ANSWER" -> "answer"
            "ru.pubchat.app.call.DECLINE" -> "decline"
            else -> return null
        }
        val uid = intent.getStringExtra("call_uid") ?: return null
        if (runCatching { java.util.UUID.fromString(uid) }.isFailure) return null
        return IncomingCallAction(kind, uid)
    }
    private val destination = mutableStateOf<PubChatDeepLink?>(null)

    private fun configureLockScreen(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(show)
            setTurnScreenOn(show)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3001)
        }
        destination.value = parsePubChatDeepLink(intent?.data)
        callAction.value = resolveCallAction(intent)
        configureLockScreen(callAction.value != null)
        val controller = AppController(applicationContext)
        setContent {
            PubChatTheme {
                PubChatApp(controller, destination.value, { destination.value = null }, callAction.value) { callAction.value = null }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 3001 && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            FcmRegistration.register(applicationContext)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destination.value = parsePubChatDeepLink(intent.data)
        callAction.value = resolveCallAction(intent)
        configureLockScreen(callAction.value != null)
    }
}
