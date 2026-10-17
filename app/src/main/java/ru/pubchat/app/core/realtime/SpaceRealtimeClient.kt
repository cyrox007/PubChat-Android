package ru.pubchat.app.core.realtime

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import ru.pubchat.app.BuildConfig
import ru.pubchat.app.core.network.PubChatApi
import java.util.concurrent.TimeUnit

class SpaceRealtimeClient(
    private val onFrame: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null

    fun connect(ticket: PubChatApi.RealtimeTicket, resumeToken: String? = null) {
        close()
        onState("connecting")
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val request = Request.Builder().url("$base${ticket.websocketPath}").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onState("authenticating")
                webSocket.send(
                    JSONObject()
                        .put("type", "auth")
                        .put("ticket", ticket.ticket)
                        .put("resume_token", resumeToken ?: JSONObject.NULL)
                        .toString()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (frame.optString("type") == "realtime_ready") onState("connected")
                onFrame(frame)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onState(if (code == 1000) "idle" else "disconnected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onState("disconnected")
            }
        })
    }

    fun sendMessage(content: String, accountUid: String, displayName: String): Boolean {
        val active = socket ?: return false
        val payload = JSONObject()
            .put("frontId", java.util.UUID.randomUUID().toString())
            .put("content", content.trim())
            .put("content_type", "text")
            .put("media_metadata", JSONObject())
            .put(
                "sender",
                JSONObject()
                    .put("uid", accountUid)
                    .put("name", displayName)
            )
        return active.send(payload.toString())
    }

    fun close() {
        socket?.close(1000, "Client disconnect")
        socket = null
    }
}