package ru.pubchat.app

import android.content.Context
import ru.pubchat.app.core.calls.CallSignaling
import ru.pubchat.app.core.calls.NativeCallEngine
import ru.pubchat.app.core.calls.CallAudioRouter
import ru.pubchat.app.core.calls.CallForegroundService
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import ru.pubchat.app.core.network.PubChatApi
import ru.pubchat.app.core.push.FcmRegistration
import ru.pubchat.app.core.realtime.MessengerRealtimeClient
import ru.pubchat.app.core.realtime.SpaceRealtimeClient
import ru.pubchat.app.core.session.SessionStore
import java.util.concurrent.Executors

sealed interface AppState {
    data object Booting : AppState
    data class SignedOut(val error: String? = null) : AppState
    data class SignedIn(
        val accountUid: String,
        val displayName: String,
        val handle: String,
        val bio: String?,
        val avatar: String?,
        val socialIntent: String?,
        val spaces: List<PubChatApi.SpaceSummary>,
        val loading: Boolean = false,
        val error: String? = null,
    ) : AppState
}

class AppController(context: Context) {
    private val appContext = context.applicationContext
    private val session = SessionStore(appContext)
    private val api = PubChatApi(
        deviceUid = { session.deviceUid },
        accessToken = { session.accessToken() },
        refreshCookie = { session.refreshCookie() },
    )
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var realtime: SpaceRealtimeClient? = null
    private var callSocket: CallSignaling? = null
    private var callEngine: NativeCallEngine? = null
    private val callAudio = CallAudioRouter(appContext)
    private var offerPending: JSONObject? = null
    private var callGeneration = 0L
    private var callConnectionEpoch = 0L
    private var reconnectAttempts = 0
    private val maxCallReconnects = 5
    private var callStatusCallback: ((String, String?, String) -> Unit)? = null
    private var messengerRealtime: MessengerRealtimeClient? = null

    fun bootstrap(onState: (AppState) -> Unit) {
        if (session.accessToken().isNullOrBlank()) {
            onState(AppState.SignedOut())
            return
        }
        executor.execute {
            runCatching {
                val identity = authenticated { api.me() }
                identity to authenticated { api.spaces() }
            }.onSuccess { (identity, spaces) -> main.post { FcmRegistration.register(appContext); onState(identity.toSignedIn(spaces)) } }
                .onFailure {
                    session.clearSession()
                    main.post { onState(AppState.SignedOut()) }
                }
        }
    }

    fun login(identifier: String, password: String, onState: (AppState) -> Unit) {
        if (identifier.isBlank() || password.isBlank()) {
            onState(AppState.SignedOut("Введите логин и пароль"))
            return
        }
        executor.execute {
            runCatching {
                val identity = api.login(identifier, password)
                saveIdentitySession(identity)
                identity to api.spaces()
            }.onSuccess { (identity, spaces) -> main.post { onState(identity.toSignedIn(spaces)) } }
                .onFailure { error ->
                    session.clearSession()
                    main.post { onState(AppState.SignedOut(humanMessage(error))) }
                }
        }
    }

    fun register(handle: String, displayName: String, email: String, password: String, socialIntent: String, onState: (AppState) -> Unit) {
        if (handle.length < 3 || password.length < 8) {
            onState(AppState.SignedOut("@handle — от 3 символов, пароль — от 8"))
            return
        }
        executor.execute {
            runCatching {
                val identity = api.register(handle, displayName, email.ifBlank { null }, password, socialIntent)
                saveIdentitySession(identity)
                identity to api.spaces()
            }.onSuccess { (identity, spaces) -> main.post { onState(identity.toSignedIn(spaces)) } }
                .onFailure { error ->
                    session.clearSession()
                    main.post { onState(AppState.SignedOut(humanMessage(error))) }
                }
        }
    }

    fun reload(current: AppState.SignedIn, onState: (AppState) -> Unit) {
        onState(current.copy(loading = true, error = null))
        executor.execute {
            runCatching { authenticated { api.spaces() } }
                .onSuccess { spaces -> main.post { onState(current.copy(spaces = spaces, loading = false)) } }
                .onFailure { error -> main.post { onState(current.copy(loading = false, error = humanMessage(error))) } }
        }
    }

    fun loadPeople(query: String, onResult: (List<PubChatApi.PersonSummary>, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.people(query) } }
                .onSuccess { people -> main.post { onResult(people, null) } }
                .onFailure { error -> main.post { onResult(emptyList(), humanMessage(error)) } }
        }
    }

    fun loadProfile(accountUid: String, onResult: (PubChatApi.PersonSummary?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.publicProfile(accountUid) } }
                .onSuccess { profile -> main.post { onResult(profile, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun loadNotifications(onResult: (List<PubChatApi.Notice>, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.notifications() } }
                .onSuccess { items -> main.post { onResult(items, null) } }
                .onFailure { error -> main.post { onResult(emptyList(), humanMessage(error)) } }
        }
    }

    fun markNotificationRead(uid: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        api.markNotificationRead(uid)
    }

    fun markAllNotificationsRead(onDone: (String?) -> Unit) = messengerAction(onDone) {
        api.markAllNotificationsRead()
    }

    fun loadDialogs(onResult: (List<PubChatApi.DialogSummary>, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.dialogs() } }
                .onSuccess { dialogs -> main.post { onResult(dialogs, null) } }
                .onFailure { error -> main.post { onResult(emptyList(), humanMessage(error)) } }
        }
    }

    fun openSpace(
        spaceUid: String,
        onReady: (PubChatApi.SpaceSummary) -> Unit,
        onInitialMessages: (List<PubChatApi.ChatMessage>) -> Unit,
        onMessage: (PubChatApi.ChatMessage) -> Unit,
        onConnection: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        realtime?.close()
        executor.execute {
            runCatching {
                var space = authenticated { api.space(spaceUid) }
                if (!space.isActiveMember && space.joinPolicy == "open") space = authenticated { api.joinSpace(spaceUid) }
                if (!space.isActiveMember) throw IllegalStateException("Сначала нужно вступить в пространство")
                space to authenticated { api.realtimeTicket(spaceUid) }
            }.onSuccess { (space, ticket) ->
                main.post {
                    onReady(space)
                    val client = SpaceRealtimeClient(
                        onFrame = { frame -> main.post { handleSpaceFrame(frame, onInitialMessages, onMessage, onError) } },
                        onState = { state -> main.post { onConnection(state) } },
                    )
                    realtime = client
                    client.connect(ticket)
                }
            }.onFailure { error -> main.post { onError(humanMessage(error)) } }
        }
    }

    fun connectMessenger(
        activePeerUid: String?,
        dialogUids: List<String>,
        currentAccountUid: String,
        onConversation: (String, List<PubChatApi.ChatMessage>) -> Unit,
        onMessage: (String, PubChatApi.ChatMessage) -> Unit,
        onRead: (String) -> Unit,
        onStatuses: (Map<String, Boolean>) -> Unit,
        onConnection: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        closeMessenger()
        executor.execute {
            runCatching { authenticated { api.messengerRealtimeTicket() } }
                .onSuccess { ticket ->
                    main.post {
                        val client = MessengerRealtimeClient(
                            onFrame = { frame ->
                                main.post {
                                    when (frame.optString("type")) {
                                        "realtime_ready" -> {
                                            if (dialogUids.isNotEmpty()) clientSubscribe(client = messengerRealtime, uids = dialogUids)
                                            activePeerUid?.let { messengerRealtime?.requestConversation(it) }
                                        }
                                        "conversation" -> {
                                            val peer = frame.optString("other_user_uid")
                                            if (peer.isNotBlank()) onConversation(peer, parseConversation(frame, currentAccountUid))
                                        }
                                        "private_message" -> {
                                            parseMessage(frame)?.let { message ->
                                                val peer = if (message.senderUid == currentAccountUid) message.receiverUid else message.senderUid
                                                if (!peer.isNullOrBlank()) onMessage(peer, message)
                                            }
                                        }
                                        "message_read" -> frame.optString("message_uid").takeIf { it.isNotBlank() }?.let(onRead)
                                        "status_update" -> onStatuses(parseStatuses(frame.optJSONObject("statuses")))
                                        "rate_limited" -> onError("Слишком много сообщений подряд")
                                        "error" -> onError(if (frame.optString("error_type") == "dm_not_allowed") "Личные сообщения этому человеку недоступны" else "Messenger действие не выполнено")
                                    }
                                }
                            },
                            onState = { state -> main.post { onConnection(state) } },
                        )
                        messengerRealtime = client
                        client.connect(ticket, activePeerUid)
                    }
                }
                .onFailure { error -> main.post { onError(humanMessage(error)) } }
        }
    }

    private fun clientSubscribe(client: MessengerRealtimeClient?, uids: List<String>) {
        client?.subscribeStatuses(uids)
    }

    fun requestConversation(peerUid: String): Boolean = messengerRealtime?.requestConversation(peerUid) == true

    fun sendPrivateMessage(peerUid: String, content: String): Boolean {
        if (content.isBlank()) return false
        return messengerRealtime?.sendMessage(peerUid, content) == true
    }

    fun markPrivateRead(messageUid: String): Boolean = messengerRealtime?.markRead(messageUid) == true

    fun editPrivateMessage(messageUid: String, content: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        api.editPrivateMessage(messageUid, content)
    }

    fun deletePrivateMessage(messageUid: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        api.deletePrivateMessage(messageUid)
    }

    fun replyPrivateMessage(messageUid: String, content: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        api.replyPrivateMessage(messageUid, content)
    }

    fun reactPrivateMessage(messageUid: String, emoji: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        api.reactPrivateMessage(messageUid, emoji)
    }

    fun forwardPrivateMessage(messageUid: String, targetPeerUid: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        api.forwardPrivateMessage(messageUid, targetPeerUid)
    }

    private fun messengerAction(onDone: (String?) -> Unit, block: () -> Unit) {
        executor.execute {
            runCatching { authenticated { block() } }
                .onSuccess { main.post { onDone(null) } }
                .onFailure { error -> main.post { onDone(humanMessage(error)) } }
        }
    }

    fun sendSpaceMessage(content: String, accountUid: String, displayName: String): Boolean {
        if (content.isBlank()) return false
        return realtime?.sendMessage(content, accountUid, displayName) == true
    }

    fun sendSpaceAttachment(
        uri: Uri,
        accountUid: String,
        displayName: String,
        onDone: (String?) -> Unit,
    ) {
        executor.execute {
            runCatching {
                val resolver = appContext.contentResolver
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val allowed = mime.startsWith("image/") || mime.startsWith("video/") ||
                    mime.startsWith("audio/") || mime in setOf("application/pdf",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                require(allowed) { "Этот тип файла не поддерживается" }
                var name = "attachment"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                }
                val bytes = resolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(2 * 1024 * 1024 + 1)
                    var used = 0
                    while (used < buffer.size) {
                        val count = stream.read(buffer, used, buffer.size - used)
                        if (count < 0) break
                        used += count
                    }
                    buffer.copyOf(used)
                } ?: error("Не удалось открыть вложение")
                require(bytes.size <= 2 * 1024 * 1024) { "Файл слишком большой (до 2 МБ)" }
                val dataUrl = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                check(realtime?.sendAttachment("", accountUid, displayName, name, mime, bytes.size, dataUrl) == true) {
                    "Разговор сейчас недоступен"
                }
            }.onSuccess { main.post { onDone(null) } }
                .onFailure { error -> main.post { onDone(humanMessage(error)) } }
        }
    }


    fun connectCalls(onStatus: (String, String?, String) -> Unit) {
        callStatusCallback = onStatus
        if (callSocket != null) return
        val epoch = ++callConnectionEpoch
        executor.execute {
            runCatching { authenticated { api.messengerRealtimeTicket() } }
                .onSuccess { ticket -> main.post {
                    if (epoch != callConnectionEpoch || callStatusCallback == null || callSocket != null) return@post
                    lateinit var socket: CallSignaling
                    socket = CallSignaling(
                        onSignal = { frame -> main.post {
                            if (callSocket === socket) handleCallSignal(frame)
                        } },
                        onStatus = { state -> main.post {
                            if (callSocket !== socket) return@post
                            when (state) {
                                "ready" -> {
                                    reconnectAttempts = 0
                                    reloadPendingIncomingCall()
                                }
                                "disconnected" -> handleCallSocketLoss(socket)
                            }
                        } },
                    )
                    callSocket = socket
                    callEngine?.release()
                    callEngine = NativeCallEngine(appContext,
                        sendSignal = { peer, uid, action, payload -> callSocket?.send(peer, uid, action, payload) == true },
                        status = { phase -> main.post {
                            if (phase != "idle") callStatusCallback?.invoke(phase, callEngine?.peerUid, callEngine?.mode ?: "audio")
                        } },
                    )
                    socket.connect(ticket)
                } }
                .onFailure { main.post {
                    if (epoch == callConnectionEpoch) scheduleCallReconnect()
                } }
        }
    }

    private fun handleCallSocketLoss(socket: CallSignaling) {
        if (callSocket !== socket) return
        val hadCall = callEngine?.callUid != null || offerPending != null
        ++callGeneration
        offerPending = null
        callEngine?.release()
        callEngine = null
        callAudio.release()
        CallForegroundService.stop(appContext)
        callSocket = null
        socket.destroy()
        if (hadCall) callStatusCallback?.invoke("disconnected", null, "audio")
        scheduleCallReconnect()
    }

    private fun scheduleCallReconnect() {
        if (callStatusCallback == null || callSocket != null || reconnectAttempts >= maxCallReconnects) {
            if (reconnectAttempts >= maxCallReconnects) callStatusCallback?.invoke("unavailable", null, "audio")
            return
        }
        val epoch = ++callConnectionEpoch
        val delayMs = (1000L shl reconnectAttempts.coerceAtMost(4)).coerceAtMost(16000L)
        reconnectAttempts++
        main.postDelayed({
            val callback = callStatusCallback
            if (epoch == callConnectionEpoch && callSocket == null && callback != null) connectCalls(callback)
        }, delayMs)
    }

    private fun reloadPendingIncomingCall() {
        executor.execute {
            runCatching { authenticated { api.pendingIncomingCall() } }
                .onSuccess { offer ->
                    if (offer != null) main.post {
                        // Same authenticated call_signal envelope as the live WebSocket.
                        // Never accept an offer without the user's explicit consent.
                        if (callSocket != null) handleCallSignal(offer)
                    }
                }
        }
    }

    private fun handleCallSignal(frame: JSONObject) {
        when (frame.optString("type")) {
            "call_error" -> callStatusCallback?.invoke("error", callEngine?.peerUid, callEngine?.mode ?: "audio")
            "call_signal" -> {
                val peer = frame.optString("from_uid")
                val uid = frame.optString("call_uid")
                when (frame.optString("signal_type")) {
                    "call_offer" -> {
                        // The same offer can arrive over the live socket and
                        // the authenticated Redis recovery endpoint. A duplicate
                        // must never be declined as "busy".
                        val pending = offerPending
                        if (pending?.optString("call_uid") == uid && pending.optString("from_uid") == peer) {
                            Unit
                        } else if (callEngine?.callUid == uid && callEngine?.peerUid == peer) {
                            Unit
                        } else if (callEngine?.callUid != null || pending != null) {
                            callSocket?.send(peer, uid, "call_decline", JSONObject().put("reason", "busy"))
                        } else {
                            offerPending = frame
                            callStatusCallback?.invoke("incoming", peer, frame.optString("mode", "audio"))
                        }
                    }
                    "call_answer" -> if (uid == callEngine?.callUid) callEngine?.acceptAnswer(frame.optJSONObject("sdp") ?: JSONObject())
                    "call_ice" -> if (uid == callEngine?.callUid) frame.optJSONObject("candidate")?.let { callEngine?.addIce(it) }
                    "call_decline", "call_end" -> if (uid == callEngine?.callUid || uid == offerPending?.optString("call_uid")) {
                        ++callGeneration
                        offerPending = null
                        callEngine?.end(false)
                        callAudio.release()
                        CallForegroundService.stop(appContext)
                        callStatusCallback?.invoke("ended", peer, "audio")
                    }
                }
            }
        }
    }

    fun startCall(peer: String, video: Boolean) {
        val generation = ++callGeneration
        executor.execute {
            runCatching { authenticated { api.callIceConfig() } }
                .onSuccess { ice -> main.post {
                    if (generation != callGeneration) return@post
                    runCatching {
                        require(callSocket != null) { "Нет соединения для звонков" }
                        callAudio.start(video)
                        CallForegroundService.start(appContext, video)
                        callEngine?.begin(peer, video, ice)
                        callStatusCallback?.invoke("outgoing", peer, if (video) "video" else "audio")
                        callEngine?.offer()
                    }.onFailure { callEngine?.end(false); CallForegroundService.stop(appContext); callAudio.release(); callStatusCallback?.invoke("error", peer, if (video) "video" else "audio") }
                } }
                .onFailure { main.post { if (generation == callGeneration) callStatusCallback?.invoke("error", peer, if (video) "video" else "audio") } }
        }
    }

    fun isPendingCall(callUid: String): Boolean = offerPending?.optString("call_uid") == callUid

    fun acceptCall() {
        val frame = offerPending ?: return
        offerPending = null
        val generation = ++callGeneration
        val peer = frame.optString("from_uid")
        val mode = frame.optString("mode", "audio")
        executor.execute {
            runCatching { authenticated { api.callIceConfig() } }
                .onSuccess { ice -> main.post {
                    if (generation != callGeneration) return@post
                    runCatching {
                        callAudio.start(mode == "video")
                        CallForegroundService.start(appContext, mode == "video")
                        callEngine?.begin(peer, mode == "video", ice, frame.optString("call_uid"))
                        callStatusCallback?.invoke("connecting", peer, mode)
                        callEngine?.answer(frame.optJSONObject("sdp") ?: JSONObject())
                    }.onFailure { callEngine?.end(false); CallForegroundService.stop(appContext); callAudio.release(); callSocket?.send(peer, frame.optString("call_uid"), "call_decline", JSONObject().put("reason", "media_unavailable")); callStatusCallback?.invoke("error", peer, mode) }
                } }
                .onFailure { main.post {
                    if (generation != callGeneration) return@post
                    callSocket?.send(peer, frame.optString("call_uid"), "call_decline", JSONObject().put("reason", "media_unavailable"))
                    callStatusCallback?.invoke("error", peer, mode)
                } }
        }
    }

    fun endCall(decline: Boolean = false) {
        ++callGeneration
        val pending = offerPending
        if (pending != null) {
            callSocket?.send(pending.optString("from_uid"), pending.optString("call_uid"),
                "call_decline", JSONObject().put("reason", "declined"))
        } else callEngine?.end(!decline)
        offerPending = null
        callAudio.release()
        CallForegroundService.stop(appContext)
        callStatusCallback?.invoke("ended", null, "audio")
    }

    fun callVideoContext(): org.webrtc.EglBase.Context? = callEngine?.videoContext
    fun attachRemoteVideo(renderer: org.webrtc.SurfaceViewRenderer) { callEngine?.attachRemote(renderer) }
    fun detachRemoteVideo(renderer: org.webrtc.SurfaceViewRenderer) { callEngine?.detachRemote(renderer) }

    fun setCallSpeaker(enabled: Boolean) { callAudio.selectSpeaker(enabled) }
    fun callSpeakerSelected(): Boolean = callAudio.isSpeakerSelected()

    fun setCallMuted(muted: Boolean) { callEngine?.mute(muted) }
    fun setCallCamera(enabled: Boolean) { callEngine?.camera(enabled) }

    fun disconnectCalls() {
        ++callConnectionEpoch
        reconnectAttempts = 0
        ++callGeneration
        callEngine?.release()
        callAudio.release()
        CallForegroundService.stop(appContext)
        callEngine = null
        callSocket?.destroy()
        callSocket = null
        callStatusCallback = null
        offerPending = null
    }

    fun closeSpace() {
        realtime?.close()
        realtime = null
    }

    fun closeMessenger() {
        messengerRealtime?.close()
        messengerRealtime = null
    }

    fun logout(onState: (AppState) -> Unit) {
        closeSpace()
        closeMessenger()
        disconnectCalls()
        executor.execute {
            runCatching { api.removeNativePush(session.deviceUid) }
            runCatching { api.logout() }
            session.clearSession()
            main.post { onState(AppState.SignedOut()) }
        }
    }

    fun close() {
        closeSpace()
        closeMessenger()
        disconnectCalls()
        executor.shutdownNow()
    }

    private fun handleSpaceFrame(
        frame: JSONObject,
        onInitialMessages: (List<PubChatApi.ChatMessage>) -> Unit,
        onMessage: (PubChatApi.ChatMessage) -> Unit,
        onError: (String) -> Unit,
    ) {
        when (frame.optString("type")) {
            "initial_data" -> {
                val items = frame.optJSONArray("messages")
                val messages = buildList {
                    if (items != null) for (index in items.length() - 1 downTo 0) items.optJSONObject(index)?.let(::parseMessage)?.let(::add)
                }
                onInitialMessages(messages)
            }
            "message" -> parseMessage(frame)?.let(onMessage)
            "rate_limited" -> onError("Слишком много сообщений подряд")
            "error" -> onError("Realtime действие не выполнено")
        }
    }

    private fun parseConversation(frame: JSONObject, currentAccountUid: String): List<PubChatApi.ChatMessage> {
        val items = frame.optJSONArray("messages") ?: return emptyList()
        return buildList {
            for (index in items.length() - 1 downTo 0) {
                items.optJSONObject(index)?.let(::parseMessage)?.let { add(it) }
            }
        }.sortedBy { it.createdAt.orEmpty() }.map { message ->
            if (message.senderUid == currentAccountUid) message else message
        }
    }

    private fun parseStatuses(json: JSONObject?): Map<String, Boolean> {
        if (json == null) return emptyMap()
        return buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, json.optBoolean(key, false))
            }
        }
    }

    private fun saveIdentitySession(identity: PubChatApi.Identity) {
        val token = identity.accessToken ?: throw IllegalStateException("Сервер не вернул access token")
        session.saveSession(token, identity.refreshCookie)
    }

    private fun <T> authenticated(block: () -> T): T {
        return try {
            block()
        } catch (error: PubChatApi.ApiFailure) {
            if (error.code != 401 || session.refreshCookie().isNullOrBlank()) throw error
            val refreshed = api.refresh()
            session.saveSession(refreshed.accessToken, refreshed.refreshCookie ?: session.refreshCookie())
            block()
        }
    }

    private fun PubChatApi.Identity.toSignedIn(spaces: List<PubChatApi.SpaceSummary>) = AppState.SignedIn(
        accountUid = accountUid,
        displayName = displayName,
        handle = handle,
        bio = bio,
        avatar = avatar,
        socialIntent = socialIntent,
        spaces = spaces,
    )

    private fun parseMessage(json: JSONObject): PubChatApi.ChatMessage? {
        val uid = json.optString("uid").ifBlank { json.optString("frontId") }
        if (uid.isBlank()) return null
        val sender = json.optJSONObject("sender")
        val metadata = json.optJSONObject("media_metadata")
        return PubChatApi.ChatMessage(
            uid = uid,
            content = json.optString("content"),
            senderUid = sender?.optString("uid")?.takeIf { it.isNotBlank() } ?: json.optString("sender_uid").takeIf { it.isNotBlank() },
            senderName = sender?.optString("name")?.takeIf { it.isNotBlank() } ?: sender?.optString("display_name")?.takeIf { it.isNotBlank() },
            createdAt = json.optString("created_at").takeIf { it.isNotBlank() },
            contentType = json.optString("content_type").ifBlank { "text" },
            receiverUid = json.optString("receiver_uid").takeIf { it.isNotBlank() },
            isRead = json.optBoolean("is_read", false),
            reactions = metadata?.optJSONObject("reactions")?.toString(),
            replyToUid = metadata?.optJSONObject("reply_to")?.optString("uid")?.takeIf { it.isNotBlank() },
            attachmentName = metadata?.optJSONArray("files")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() },
        )
    }

    private fun humanMessage(error: Throwable): String = when (error) {
        is PubChatApi.ApiFailure -> when (error.code) {
            401 -> "Неверные данные входа или сессия истекла"
            403 -> "Доступ ограничен"
            404 -> "Объект больше недоступен"
            409 -> "Действие конфликтует с текущим состоянием"
            422 -> "Проверьте заполненные данные"
            else -> "Сервер PubChat ответил с ошибкой ${error.code}"
        }
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Не удалось связаться с PubChat"
    }
}