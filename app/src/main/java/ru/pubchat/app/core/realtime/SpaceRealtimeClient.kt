package ru.pubchat.app.core.realtime

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.json.JSONArray
import ru.pubchat.app.BuildConfig
import ru.pubchat.app.core.network.PubChatApi
import ru.pubchat.app.core.network.HttpClientCleanup
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class SpaceRealtimeClient(
    private val onFrame: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var heartbeatTask: ScheduledFuture<*>? = null
    @Volatile private var socket: WebSocket? = null

    fun connect(ticket: PubChatApi.RealtimeTicket, resumeToken: String? = null) {
        closeSocket()
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
                if (socket !== webSocket) return
                if (frame.optString("type") == "realtime_ready") {
                    onState("connected")
                    startHeartbeat(webSocket, frame.optInt("heartbeat_seconds", ticket.heartbeatSeconds))
                }
                if (frame.optString("type") == "ping") webSocket.send(JSONObject().put("type", "pong").toString())
                onFrame(frame)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                stopHeartbeat()
                socket = null
                onState(if (RealtimeRetryPolicy.terminalCloseCode(code)) "restricted" else "disconnected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                stopHeartbeat()
                socket = null
                onState("disconnected")
            }
        })
    }

    fun sendMessage(content: String, accountUid: String, displayName: String, replyToUid: String? = null): Boolean {
        val active = socket ?: return false
        val payload = roomTextFrame(content, accountUid, displayName, replyToUid)
        return active.send(payload.toString())
    }

    fun sendAttachment(
        content: String,
        accountUid: String,
        displayName: String,
        fileName: String,
        mimeType: String,
        size: Int,
        dataUrl: String,
    ): Boolean {
        val kind = when {
            mimeType.startsWith("image/") -> "image"
            mimeType.startsWith("video/") -> "video"
            mimeType.startsWith("audio/") -> "audio"
            else -> "file"
        }
        val file = JSONObject().put("name", fileName).put("type", mimeType)
            .put("size", size).put("url", dataUrl)
        val frame = JSONObject()
            .put("frontId", java.util.UUID.randomUUID().toString())
            .put("content", content)
            .put("content_type", kind)
            .put("media_metadata", JSONObject().put("files", JSONArray().put(file)))
            .put("sender", JSONObject().put("uid", accountUid).put("name", displayName))
        return socket?.send(frame.toString()) == true
    }

    fun close() {
        closeSocket()
        scheduler.shutdownNow()
        HttpClientCleanup.release(client)
    }

    private fun startHeartbeat(webSocket: WebSocket, seconds: Int) {
        stopHeartbeat()
        val period = seconds.coerceAtLeast(10).toLong()
        heartbeatTask = scheduler.scheduleAtFixedRate({
            if (socket === webSocket) {
                webSocket.send(JSONObject().put("type", "heartbeat").toString())
            }
        }, period, period, TimeUnit.SECONDS)
    }

    private fun stopHeartbeat() {
        heartbeatTask?.cancel(false)
        heartbeatTask = null
    }

    private fun closeSocket() {
        stopHeartbeat()
        val old = socket
        socket = null
        old?.close(1000, "Client disconnect")
    }
}
internal fun roomTextFrame(content: String, accountUid: String, displayName: String, replyToUid: String?): JSONObject = JSONObject()
    .put("frontId", java.util.UUID.randomUUID().toString())
    .put("content", content.trim())
    .put("content_type", "text")
    .put("media_metadata", JSONObject())
    .put("sender", JSONObject().put("uid", accountUid).put("name", displayName))
    .apply { replyToUid?.takeIf { it.isNotBlank() }?.let { put("reply_to_uid", it) } }