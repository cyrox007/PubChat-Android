package ru.pubchat.app.core.calls

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import ru.pubchat.app.BuildConfig
import ru.pubchat.app.core.network.PubChatApi

/** Dedicated calls socket: Messenger socket does not receive call_signal frames. */
class CallSignaling(
    private val onSignal: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val http = OkHttpClient()
    private var socket: WebSocket? = null
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor()
    private var heartbeat: ScheduledFuture<*>? = null
    @Volatile private var ready = false

    fun connect(ticket: PubChatApi.RealtimeTicket) {
        close()
        val base = BuildConfig.API_BASE_URL.removeSuffix("/api").trimEnd('/')
            .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        socket = http.newWebSocket(Request.Builder().url("$base/ws/v2/calls").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "auth").put("ticket", ticket.ticket).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (frame.optString("type") == "realtime_ready") {
                    ready = true
                    onStatus("ready")
                    heartbeat?.cancel(false)
                    heartbeat = heartbeatExecutor.scheduleAtFixedRate({
                        webSocket.send(JSONObject().put("type", "heartbeat").put("action", "heartbeat").toString())
                    }, 25, 25, TimeUnit.SECONDS)
                } else if (frame.optString("type") == "ping") {
                    webSocket.send(JSONObject().put("type", "pong").toString())
                } else {
                    onSignal(frame)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready = false
                onStatus("disconnected")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready = false
                onStatus("disconnected")
            }
        })
    }

    fun send(peerUid: String, callUid: String, action: String, data: JSONObject = JSONObject()): Boolean {
        if (!ready) return false
        val frame = JSONObject(data.toString()).put("action", action)
            .put("call_uid", callUid).put("target_uid", peerUid)
        return socket?.send(frame.toString()) == true
    }

    fun close() {
        ready = false
        heartbeat?.cancel(false)
        heartbeat = null
        socket?.close(1000, "Client disconnect")
        socket = null
    }

    fun destroy() {
        close()
        heartbeatExecutor.shutdownNow()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
