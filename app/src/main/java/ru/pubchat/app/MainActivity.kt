package ru.pubchat.app

import android.Manifest
import ru.pubchat.app.core.push.FcmRegistration
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
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

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) FcmRegistration.register(applicationContext)
    }
    private val destination = mutableStateOf<PubChatDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        destination.value = parsePubChatDeepLink(intent?.data)
        val controller = AppController(applicationContext)
        setContent {
            PubChatTheme {
                PubChatApp(controller, destination.value) { destination.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destination.value = parsePubChatDeepLink(intent.data)
    }
}
