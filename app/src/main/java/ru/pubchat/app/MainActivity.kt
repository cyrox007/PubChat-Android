package ru.pubchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.pubchat.app.ui.theme.PubChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = AppController(applicationContext)
        setContent {
            PubChatTheme {
                PubChatApp(controller)
            }
        }
    }
}
