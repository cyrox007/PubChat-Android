package ru.pubchat.app.core.realtime

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import ru.pubchat.app.BuildConfig
import ru.pubchat.app.core.network.PubChatApi
import ru.pubchat.app.core.network.HttpClientCleanup
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MessengerRealtimeClient(
    private val onFrame: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder().build()
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
                if (socket !== webSocket) return
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (frame.optString("type") == "realtime_ready") {
                    onState("connected")
                    startHeartbeat(webSocket, frame.optInt("heartbeat_seconds", ticket.heartbeatSeconds))
                }
                if (frame.optString("type") == "ping") {
                    webSocket.send(JSONObject().put("type", "pong").toString())
                }
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

    fun requestConversation(peerUid: String, requestId: String = java.util.UUID.randomUUID().toString()): Boolean =
        send(
            JSONObject()
                .put("action", "get_conversation")
                .put("other_user_uid", peerUid)
                .put("request_id", requestId)
        )

    fun sendMessage(peerUid: String, content: String): Boolean = send(
        JSONObject()
            .put("action", "send_message")
            .put("frontId", java.util.UUID.randomUUID().toString())
            .put("receiver_uid", peerUid)
            .put("content", content.trim())
            .put("content_type", "text")
            .put("media_metadata", JSONObject())
    )

    fun markRead(messageUid: String): Boolean = send(
        JSONObject()
            .put("action", "mark_message_as_read")
            .put("message_uid", messageUid)
    )

    fun subscribeStatuses(userIds: List<String>): Boolean = send(
        JSONObject()
            .put("action", "subscribe_status")
            .put("userIds", JSONArray(userIds.distinct()))
    )

    fun close() {
        closeSocket()
        scheduler.shutdownNow()
        HttpClientCleanup.release(client)
    }

    private fun send(frame: JSONObject): Boolean = socket?.send(frame.toString()) == true

    private fun startHeartbeat(webSocket: WebSocket, seconds: Int) {
        stopHeartbeat()
        val period = seconds.coerceAtLeast(10).toLong()
        heartbeatTask = scheduler.scheduleAtFixedRate(
            {
                webSocket.send(
                    JSONObject()
                        .put("type", "heartbeat")
                        .put("action", "heartbeat")
                        .toString()
                )
            },
            period,
            period,
            TimeUnit.SECONDS,
        )
    }

    private fun stopHeartbeat() {
        heartbeatTask?.cancel(true)
        heartbeatTask = null
    }

    private fun closeSocket() {
        stopHeartbeat()
        val old = socket
        socket = null
        old?.close(1000, "Client disconnect")
    }
}
