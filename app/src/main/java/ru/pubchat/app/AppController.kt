package ru.pubchat.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import ru.pubchat.app.core.crypto.CryptoDeviceMaterial
import ru.pubchat.app.core.crypto.CryptoDeviceSnapshot
import ru.pubchat.app.core.crypto.CryptoDeviceStore
import ru.pubchat.app.core.crypto.E2eeInboundText
import ru.pubchat.app.core.crypto.E2eeSessionStore
import ru.pubchat.app.core.crypto.PeerDirectoryContinuity
import ru.pubchat.app.core.crypto.PeerDirectoryTrustStore
import ru.pubchat.app.core.crypto.PeerDirectoryInspection
import ru.pubchat.app.core.network.MessengerEncryptionStatus
import ru.pubchat.app.core.network.requirePlaintextMessengerTransport
import ru.pubchat.app.core.network.SpaceInvitationPage
import ru.pubchat.app.core.network.SpaceInvitationDecision
import ru.pubchat.app.core.network.invitationErrorMessage
import ru.pubchat.app.core.space.SpaceCreationDraft
import ru.pubchat.app.core.network.parseChatMessage
import ru.pubchat.app.core.push.FcmRegistration
import ru.pubchat.app.core.realtime.MessengerRealtimeClient
import ru.pubchat.app.core.realtime.SpaceRealtimeClient
import ru.pubchat.app.core.realtime.RealtimeRetryPolicy
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
        val city: String? = null,
        val country: String? = null,
    ) : AppState
}

class AppController(context: Context) {
    private val appContext = context.applicationContext
    private val session = SessionStore(appContext)
    private val cryptoDeviceStore = CryptoDeviceStore(appContext)
    private val e2eeSessionStore = E2eeSessionStore(appContext)
    private val peerDirectoryTrustStore = PeerDirectoryTrustStore(appContext)
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
    private var iceRecoveryEpoch = 0L
    private val maxCallReconnects = 5
    private var callStatusCallback: ((String, String?, String) -> Unit)? = null
    private var messengerRealtime: MessengerRealtimeClient? = null

    private data class SpaceSubscription(
        val uid: String,
        val onReady: (PubChatApi.SpaceSummary) -> Unit,
        val onInitialMessages: (List<PubChatApi.ChatMessage>) -> Unit,
        val onMessage: (PubChatApi.ChatMessage) -> Unit,
        val onConnection: (String) -> Unit,
        val onError: (String) -> Unit,
    )

    private data class MessengerSubscription(
        var activePeerUid: String?,
        val dialogUids: List<String>,
        val currentAccountUid: String,
        val onConversation: (String, List<PubChatApi.ChatMessage>) -> Unit,
        val onMessage: (String, PubChatApi.ChatMessage) -> Unit,
        val onRead: (String) -> Unit,
        val onStatuses: (Map<String, Boolean>) -> Unit,
        val onConnection: (String) -> Unit,
        val onError: (String) -> Unit,
        val onSendConfirmed: (String, String) -> Unit,
        val onSendFailed: (String, String) -> Unit,
    )

    // Owned by Android main thread. Epochs prevent a stale ticket/socket from
    // resurrecting a room or messenger after navigation/logout.
    private var spaceSubscription: SpaceSubscription? = null
    private var messengerSubscription: MessengerSubscription? = null
    private var spaceEpoch = 0L
    private var messengerEpoch = 0L
    private var spaceConnecting = false
    private var spaceInitialized = false
    private var messengerConnecting = false
    private var spaceStatus = "idle"
    private var messengerStatus = "idle"
    private var spaceRetries = 0
    private var messengerRetries = 0
    private var spaceRetryTask: Runnable? = null
    private var messengerRetryTask: Runnable? = null
    private var foreground = true
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { reconnectOnNetworkReturn() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                main.post { reconnectOnNetworkReturn() }
            }
        }
    }

    init {
        // A crashed process cannot run the preview's onDispose cleanup.
        // Purge only our private temporary previews from previous sessions.
        runCatching {
            appContext.cacheDir.listFiles()?.asSequence()
                ?.filter { it.isFile && it.name.startsWith("pubchat_preview_") }
                ?.forEach { it.delete() }
        }
        // Connectivity callback is only a fast path; bounded retries and
        // foreground resume also recover if an OEM suppresses notifications.
        runCatching { connectivityManager.registerDefaultNetworkCallback(networkCallback) }
    }

    fun setForeground(active: Boolean) {
        foreground = active
        if (active) reconnectOnNetworkReturn()
        else {
            cancelSpaceRetry()
            cancelMessengerRetry()
        }
    }

    private fun reconnectOnNetworkReturn() {
        if (!foreground) return
        if (spaceSubscription != null && !spaceConnecting && spaceStatus in setOf("disconnected", "reconnecting")) {
            cancelSpaceRetry()
            connectSpace(spaceEpoch, initial = !spaceInitialized)
        }
        if (messengerSubscription != null && !messengerConnecting && messengerStatus in setOf("disconnected", "reconnecting")) {
            cancelMessengerRetry()
            connectMessengerSocket(messengerEpoch)
        }
    }

    private fun cancelSpaceRetry() {
        spaceRetryTask?.let(main::removeCallbacks)
        spaceRetryTask = null
    }

    private fun cancelMessengerRetry() {
        messengerRetryTask?.let(main::removeCallbacks)
        messengerRetryTask = null
    }

    private fun scheduleSpaceRetry(epoch: Long) {
        if (epoch != spaceEpoch || spaceSubscription == null) return
        spaceStatus = "reconnecting"
        spaceSubscription?.onConnection("reconnecting")
        cancelSpaceRetry()
        if (!foreground) return
        val delay = RealtimeRetryPolicy.delayMillis(spaceRetries++)
        val task = Runnable {
            spaceRetryTask = null
            connectSpace(epoch, initial = !spaceInitialized)
        }
        spaceRetryTask = task
        main.postDelayed(task, delay)
    }

    private fun scheduleMessengerRetry(epoch: Long) {
        if (epoch != messengerEpoch || messengerSubscription == null) return
        messengerStatus = "reconnecting"
        messengerSubscription?.onConnection("reconnecting")
        cancelMessengerRetry()
        if (!foreground) return
        val delay = RealtimeRetryPolicy.delayMillis(messengerRetries++)
        val task = Runnable {
            messengerRetryTask = null
            connectMessengerSocket(epoch)
        }
        messengerRetryTask = task
        main.postDelayed(task, delay)
    }

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

    fun checkHandleAvailability(handle: String, callback: (Boolean?, String?) -> Unit) {
        executor.execute {
            runCatching { api.handleAvailable(handle) }
                .onSuccess { available -> main.post { callback(available, null) } }
                .onFailure { main.post { callback(null, "Не удалось проверить username") } }
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

    fun createSpace(
        draft: SpaceCreationDraft,
        onResult: (PubChatApi.SpaceSummary?, String?) -> Unit,
    ) {
        draft.validationError()?.let { onResult(null, it); return }
        executor.execute {
            runCatching { authenticated { api.createSpace(draft) } }
                .onSuccess { created -> main.post { onResult(created, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun loadSpaceInvitations(
        offset: Int = 0,
        onResult: (SpaceInvitationPage?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching { authenticated { api.spaceInvitations(offset = offset) } }
                .onSuccess { page -> main.post { onResult(page, null) } }
                .onFailure { error -> main.post { onResult(null, invitationErrorMessage(error)) } }
        }
    }

    fun respondSpaceInvitation(
        uid: String,
        expectedSpaceUid: String,
        action: String,
        onResult: (SpaceInvitationDecision?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching { authenticated { api.respondSpaceInvitation(uid, expectedSpaceUid, action) } }
                .onSuccess { result -> main.post { onResult(result, null) } }
                .onFailure { error -> main.post { onResult(null, invitationErrorMessage(error)) } }
        }
    }

    fun loadSpaceCategories(onResult: (List<PubChatApi.SpaceCategory>, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.spaceCategories() } }
                .onSuccess { categories -> main.post { onResult(categories, null) } }
                .onFailure { error -> main.post { onResult(emptyList(), humanMessage(error)) } }
        }
    }

    fun loadSpacesForCategory(
        category: String,
        onResult: (List<PubChatApi.SpaceSummary>?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching { authenticated { api.spaces(category) } }
                .onSuccess { spaces -> main.post { onResult(spaces, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun loadPeople(
        query: String,
        intent: String? = null,
        availableNow: Boolean = false,
        onResult: (List<PubChatApi.PersonSummary>, String?) -> Unit,
    ) {
        executor.execute {
            runCatching { authenticated { api.people(query, intent, availableNow) } }
                .onSuccess { people -> main.post { onResult(people, null) } }
                .onFailure { error -> main.post { onResult(emptyList(), humanMessage(error)) } }
        }
    }

    fun loadSocialAvailability(onResult: (PubChatApi.SocialAvailability?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.mySocialAvailability() } }
                .onSuccess { value -> main.post { onResult(value, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun updateSocialAvailability(
        intent: String,
        duration: Int,
        note: String,
        onResult: (PubChatApi.SocialAvailability?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching { authenticated { api.setSocialAvailability(intent, duration, note) } }
                .onSuccess { value -> main.post { onResult(value, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun clearSocialAvailability(onResult: (String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.clearSocialAvailability() } }
                .onSuccess { main.post { onResult(null) } }
                .onFailure { error -> main.post { onResult(humanMessage(error)) } }
        }
    }

    fun loadDMConsent(onResult: (PubChatApi.DMConsentSnapshot?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.myDMConsent() } }
                .onSuccess { value -> main.post { onResult(value, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun saveDMConsent(
        expected: PubChatApi.DMConsentSnapshot,
        policy: PubChatApi.DMConsentPolicy,
        onResult: (PubChatApi.DMConsentSnapshot?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching {
                authenticated { api.updateDMConsent(policy) }.also { result ->
                    check(result.accountUid == expected.accountUid &&
                        result.personaUid == expected.personaUid &&
                        result.policy == policy) {
                        "Сервер не подтвердил приватность текущей Persona"
                    }
                }
            }
                .onSuccess { value -> main.post { onResult(value, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun loadProfile(accountUid: String, onResult: (PubChatApi.PersonSummary?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.publicProfile(accountUid) } }
                .onSuccess { profile -> main.post { onResult(profile, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun loadFriendships(
        tab: String,
        offset: Int = 0,
        onResult: (PubChatApi.FriendshipPage?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching { authenticated { api.friendshipPage(tab, offset) } }
                .onSuccess { page -> main.post { onResult(page, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun loadRelationship(
        accountUid: String,
        onResult: (PubChatApi.SocialRelationship?, String?) -> Unit,
    ) = socialAction(onResult) { api.relationship(accountUid) }

    fun setFollowing(
        accountUid: String,
        currentlyFollowing: Boolean,
        onResult: (PubChatApi.SocialRelationship?, String?) -> Unit,
    ) = socialAction(onResult) { api.setFollowing(accountUid, currentlyFollowing) }

    fun friendAction(
        accountUid: String,
        action: String,
        onResult: (PubChatApi.SocialRelationship?, String?) -> Unit,
    ) = socialAction(onResult) { api.friendAction(accountUid, action) }

    fun setBlocked(
        accountUid: String,
        currentlyBlocked: Boolean,
        onResult: (PubChatApi.SocialRelationship?, String?) -> Unit,
    ) = socialAction(onResult) { api.setBlocked(accountUid, currentlyBlocked) }

    private fun socialAction(
        onResult: (PubChatApi.SocialRelationship?, String?) -> Unit,
        action: () -> PubChatApi.SocialRelationship,
    ) {
        executor.execute {
            runCatching { authenticated(action) }
                .onSuccess { result -> main.post { onResult(result, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun loadPersonas(onResult: (PubChatApi.PersonaList?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.personas() } }
                .onSuccess { list -> main.post { onResult(list, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun createPersona(
        handle: String, displayName: String, onDone: (String?) -> Unit
    ) {
        executor.execute {
            runCatching { authenticated { api.createPersona(handle, displayName) } }
                .onSuccess { main.post { onDone(null) } }
                .onFailure { error -> main.post { onDone(humanMessage(error)) } }
        }
    }

    fun activatePersona(
        current: AppState.SignedIn, personaUid: String,
        onDone: (AppState.SignedIn?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching { authenticated { api.activatePersona(personaUid) } }
                .onSuccess { identity ->
                    main.post {
                        if (identity.accountUid == current.accountUid) {
                            onDone(identity.toSignedIn(current.spaces), null)
                        } else onDone(null, "Сервер вернул другой Account")
                    }
                }
                .onFailure { error -> main.post { onDone(null, humanMessage(error)) } }
        }
    }

    fun loadBirthdayRemindersPreference(onResult: (Boolean?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.birthdayReminderEnabled() } }
                .onSuccess { enabled -> main.post { onResult(enabled, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun setBirthdayRemindersPreference(enabled: Boolean, onResult: (Boolean?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.setBirthdayReminderEnabled(enabled) } }
                .onSuccess { updated -> main.post { onResult(updated, null) } }
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
        closeSpace()
        spaceSubscription = SpaceSubscription(spaceUid, onReady, onInitialMessages, onMessage, onConnection, onError)
        spaceRetries = 0
        connectSpace(spaceEpoch, initial = true)
    }

    private fun connectSpace(epoch: Long, initial: Boolean) {
        val target = spaceSubscription ?: return
        if (epoch != spaceEpoch || spaceConnecting) return
        spaceConnecting = true
        spaceStatus = if (initial && spaceRetries == 0) "connecting" else "reconnecting"
        target.onConnection(spaceStatus)
        executor.execute {
            runCatching {
                val space = if (initial) {
                    var result = authenticated { api.space(target.uid) }
                    if (!result.isActiveMember && result.joinPolicy == "open") {
                        result = authenticated { api.joinSpace(target.uid) }
                    }
                    if (!result.isActiveMember) throw IllegalStateException("Сначала нужно вступить в пространство")
                    result
                } else null
                space to authenticated { api.realtimeTicket(target.uid) }
            }.onSuccess { (space, ticket) ->
                main.post {
                    if (epoch != spaceEpoch || spaceSubscription !== target) return@post
                    spaceConnecting = false
                    if (space != null) {
                        spaceInitialized = true
                        target.onReady(space)
                    }
                    realtime?.close()
                    lateinit var client: SpaceRealtimeClient
                    client = SpaceRealtimeClient(
                        onFrame = { frame -> main.post {
                            if (epoch == spaceEpoch && realtime === client) {
                                handleSpaceFrame(frame, target.onInitialMessages, target.onMessage, target.onError)
                            }
                        } },
                        onState = { status -> main.post {
                            if (epoch != spaceEpoch || realtime !== client) return@post
                            spaceStatus = status
                            if (status == "connected") {
                                spaceRetries = 0
                                cancelSpaceRetry()
                            }
                            target.onConnection(status)
                            if (status == "disconnected") {
                                realtime = null
                                client.close()
                                scheduleSpaceRetry(epoch)
                            } else if (status == "restricted") {
                                realtime = null
                                client.close()
                                target.onError("Сервер ограничил доступ к пространству")
                            }
                        } },
                    )
                    realtime = client
                    client.connect(ticket)
                }
            }.onFailure { error -> main.post {
                if (epoch != spaceEpoch || spaceSubscription !== target) return@post
                spaceConnecting = false
                val status = (error as? PubChatApi.ApiFailure)?.code
                if (RealtimeRetryPolicy.terminalHttpStatus(status)) {
                    spaceStatus = "restricted"
                    target.onConnection("restricted")
                    target.onError(humanMessage(error))
                } else {
                    scheduleSpaceRetry(epoch)
                }
            } }
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
        onSendConfirmed: (String, String) -> Unit,
        onSendFailed: (String, String) -> Unit,
    ) {
        closeMessenger()
        messengerSubscription = MessengerSubscription(
            activePeerUid, dialogUids, currentAccountUid, onConversation, onMessage,
            onRead, onStatuses, onConnection, onError, onSendConfirmed, onSendFailed,
        )
        messengerRetries = 0
        connectMessengerSocket(messengerEpoch)
    }

    private fun connectMessengerSocket(epoch: Long) {
        val target = messengerSubscription ?: return
        if (epoch != messengerEpoch || messengerConnecting) return
        messengerConnecting = true
        messengerStatus = if (messengerRetries == 0) "connecting" else "reconnecting"
        target.onConnection(messengerStatus)
        executor.execute {
            runCatching { authenticated { api.messengerRealtimeTicket() } }
                .onSuccess { ticket -> main.post {
                    if (epoch != messengerEpoch || messengerSubscription !== target) return@post
                    messengerConnecting = false
                    messengerRealtime?.close()
                    lateinit var client: MessengerRealtimeClient
                    client = MessengerRealtimeClient(
                        onFrame = { frame -> main.post {
                            if (epoch != messengerEpoch || messengerRealtime !== client) return@post
                            when (frame.optString("type")) {
                                "realtime_ready" -> {
                                    if (target.dialogUids.isNotEmpty()) client.subscribeStatuses(target.dialogUids)
                                    target.activePeerUid?.let { peer -> client.requestConversation(peer) }
                                }
                                "conversation" -> {
                                    val peer = frame.optString("other_user_uid")
                                    if (peer.isNotBlank()) target.onConversation(peer, parseConversation(frame, target.currentAccountUid))
                                }
                                "private_message" -> {
                                    parseMessage(frame)?.let { message ->
                                        val peer = if (message.senderUid == target.currentAccountUid) message.receiverUid else message.senderUid
                                        if (!peer.isNullOrBlank()) {
                                            target.onMessage(peer, message)
                                            val frontId = frame.optString("frontId")
                                            if (message.senderUid == target.currentAccountUid && frontId.isNotBlank()) {
                                                target.onSendConfirmed(frontId, peer)
                                            }
                                        }
                                    }
                                }
                                "message_read" -> frame.optString("message_uid").takeIf { it.isNotBlank() }?.let(target.onRead)
                                "status_update" -> target.onStatuses(parseStatuses(frame.optJSONObject("statuses")))
                                "rate_limited" -> target.onError("Слишком много сообщений подряд")
                                "duplicate_ignored" -> frame.optString("frontId").takeIf { it.isNotBlank() }?.let {
                                    target.onSendFailed(it, "Повтор обнаружен сервером. Проверьте историю перед повторной отправкой.")
                                }
                                "error" -> {
                                    val reason = when (frame.optString("error_type")) {
                                        "dm_not_allowed" -> "Личные сообщения этому человеку недоступны"
                                        "conversation_requires_e2ee" -> "Для этого диалога требуется E2EE"
                                        else -> "Messenger действие не выполнено"
                                    }
                                    frame.optString("frontId").takeIf { it.isNotBlank() }?.let {
                                        target.onSendFailed(it, reason)
                                    } ?: target.onError(reason)
                                }
                            }
                        } },
                        onState = { status -> main.post {
                            if (epoch != messengerEpoch || messengerRealtime !== client) return@post
                            messengerStatus = status
                            if (status == "connected") {
                                messengerRetries = 0
                                cancelMessengerRetry()
                            }
                            target.onConnection(status)
                            if (status == "disconnected") {
                                messengerRealtime = null
                                client.close()
                                scheduleMessengerRetry(epoch)
                            } else if (status == "restricted") {
                                messengerRealtime = null
                                client.close()
                                target.onError("Сервер ограничил доступ к переписке")
                            }
                        } },
                    )
                    messengerRealtime = client
                    client.connect(ticket, target.activePeerUid)
                } }
                .onFailure { error -> main.post {
                    if (epoch != messengerEpoch || messengerSubscription !== target) return@post
                    messengerConnecting = false
                    val status = (error as? PubChatApi.ApiFailure)?.code
                    if (RealtimeRetryPolicy.terminalHttpStatus(status)) {
                        messengerStatus = "restricted"
                        target.onConnection("restricted")
                        target.onError(humanMessage(error))
                    } else scheduleMessengerRetry(epoch)
                } }
        }
    }

    private fun clientSubscribe(client: MessengerRealtimeClient?, uids: List<String>) {
        client?.subscribeStatuses(uids)
    }

    fun requestConversation(peerUid: String): Boolean {
        messengerSubscription?.activePeerUid = peerUid
        return messengerRealtime?.requestConversation(peerUid) == true
    }

    /** Every plaintext send rechecks the server policy. Reconnect, peer switch and policy changes fail closed. */
    fun sendPrivateMessage(
        peerUid: String,
        content: String,
        frontId: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        if (content.isBlank() || frontId.isBlank()) {
            onResult(false, "Введите сообщение и идентификатор отправки")
            return
        }
        val currentEpoch = messengerEpoch
        val currentSubscription = messengerSubscription
        executor.execute {
            runCatching { checkedPlaintextMessenger(peerUid) }
                .onSuccess {
                    main.post {
                        if (currentEpoch != messengerEpoch || messengerSubscription !== currentSubscription ||
                            currentSubscription?.activePeerUid != peerUid || messengerStatus != "connected"
                        ) {
                            onResult(false, "Диалог изменился или соединение прервалось. Повторите отправку.")
                        } else {
                            val queued = messengerRealtime?.sendMessage(peerUid, content, frontId) == true
                            onResult(queued, if (queued) null else "Соединение ещё не готово. Сообщение не отправлено.")
                        }
                    }
                }
                .onFailure { error -> main.post { onResult(false, messengerEncryptionError(error)) } }
        }
    }

    /** Recover only identifiers from validated, Keystore-wrapped durable E2EE state. */
    fun recoverEncryptedPendingIds(
        ownerUid: String, peerUid: String,
        onResult: (List<String>, String?) -> Unit,
    ) {
        executor.execute {
            runCatching {
                val local = cryptoDeviceStore.read(ownerUid)
                    ?: return@runCatching emptyList<String>()
                e2eeSessionStore.pendingBatchIdsForPeer(ownerUid, local.deviceUid, peerUid)
            }.onSuccess { ids -> main.post { onResult(ids, null) } }
                .onFailure { error -> main.post {
                    onResult(emptyList(), "E2EE: " + (error.message ?: "Не удалось восстановить отправки"))
                } }
        }
    }

    /**
     * Native E2EE send uses the same immutable ciphertext batch across retries.
     * Never falls back to plaintext when crypto, trust or transport fails.
     */
    fun sendEncryptedPrivateMessage(
        ownerUid: String,
        peerUid: String,
        content: String,
        frontId: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        if (frontId.isBlank()) {
            onResult(false, "Не указан идентификатор E2EE-отправки")
            return
        }
        val epoch = messengerEpoch
        val subscription = messengerSubscription
        executor.execute {
            runCatching {
                val policy = authenticated { api.messengerEncryption(peerUid) }
                ru.pubchat.app.core.network.requireE2eeDeliveryPolicy(policy)
                val local = requireNotNull(cryptoDeviceStore.read(ownerUid)) {
                    "Ключи устройства не подготовлены"
                }
                val loader = ru.pubchat.app.core.network.E2eeTrustedDirectoryLoader(
                    api, cryptoDeviceStore, peerDirectoryTrustStore)
                val verified = authenticated { loader.load(ownerUid, peerUid) }
                // A frontId is never re-encrypted: use an already committed batch
                // on retry, even after a process restart.
                val pendingExists = e2eeSessionStore.hasPendingBatch(
                    ownerUid, local.deviceUid, frontId)
                if (!pendingExists) {
                    require(content.isNotBlank()) { "Нет сохранённого E2EE-пакета для восстановления" }
                    e2eeSessionStore.bootstrapAndPrepareBatch(
                        ownerUid, local.deviceUid, local, verified, frontId,
                        content.trim().toByteArray(Charsets.UTF_8))
                } else {
                    val previous = e2eeSessionStore.pendingBatch(ownerUid, local.deviceUid, frontId)
                    require(previous.receiverUid == peerUid) { "Pending E2EE batch belongs to another dialog" }
                }
                authenticated {
                    ru.pubchat.app.core.network.E2eeBatchDelivery(api, e2eeSessionStore)
                        .submitPending(ownerUid, local.deviceUid, frontId, verified) {
                            loader.refreshForSend(verified)
                        }
                }
            }.onSuccess {
                main.post {
                    if (epoch == messengerEpoch && messengerSubscription === subscription &&
                        subscription?.activePeerUid == peerUid) onResult(true, null)
                }
            }.onFailure { error ->
                main.post {
                    if (epoch == messengerEpoch) onResult(false,
                        "E2EE: " + (error.message ?: "Отправка не подтверждена, повторите с тем же пакетом"))
                }
            }
        }
    }

    /**
     * Explicit crypto-device preparation, never auto-invoked during login.
     * Identity is persisted before the first request, so network retries reuse
     * exactly the same immutable device UID and signing/X25519 keys.
     */
    /**
     * Explicit inbound read integration. Failed/unsupported ciphertext remains
     * locked; a successfully decrypted message is returned only AFTER durable
     * ratchet commit, never from server-supplied plaintext metadata.
     */
    internal fun decryptMessengerHistory(
        ownerUid: String, peerUid: String,
        messages: List<PubChatApi.ChatMessage>,
        onResult: (Map<String, String>, String?) -> Unit,
    ) {
        executor.execute {
            runCatching {
                val candidates = messages.filter { it.contentType == "e2ee" &&
                    it.senderUid == peerUid }.take(100)
                if (candidates.isEmpty()) return@runCatching emptyMap<String, String>()
                val local = requireNotNull(cryptoDeviceStore.read(ownerUid)) {
                    "Криптографическое устройство не настроено"
                }
                val prior = peerDirectoryTrustStore.read(ownerUid, peerUid)
                val directoryResponse = authenticated { api.peerCryptoDirectory(peerUid) }
                val currentSeq = PeerDirectoryContinuity.checkpoint(
                    JSONObject(directoryResponse).getJSONObject("directory_checkpoint")).sequence
                val history = if (prior != null && currentSeq > prior.checkpoint.sequence)
                    authenticated { api.peerCryptoDirectoryHistory(peerUid, prior.checkpoint.sequence) }
                else null
                val trusted = PeerDirectoryContinuity.inspect(directoryResponse, history, peerUid, prior)
                require(!trusted.requiresAcknowledgement) {
                    "Ключи собеседника изменились — сначала подтвердите каталог устройств"
                }
                val envelopes = authenticated { api.e2eeMessageEnvelopes(candidates.map { it.uid }) }
                val decrypted = linkedMapOf<String, String>()
                for (message in candidates) {
                    try {
                        val envelope = E2eeInboundText.selectEnvelope(envelopes, message.uid, local.deviceUid)
                        require(envelope.senderDeviceUid in trusted.devices.keys)
                        val text = e2eeSessionStore.decryptInboundText(
                            ownerUid, local.deviceUid, local, trusted, envelope)
                        decrypted[message.uid] = text
                    } catch (_: Exception) {
                        // Unknown/replayed/out-of-order/corrupt envelopes stay locked.
                        // Do not expose server metadata or downgrade to plaintext.
                    }
                }
                peerDirectoryTrustStore.save(ownerUid, PeerDirectoryContinuity.trust(peerUid, trusted))
                decrypted
            }.onSuccess { result -> main.post { onResult(result, null) } }
                .onFailure { error -> main.post {
                    onResult(emptyMap(), "E2EE: " + (error.message ?: "расшифровка недоступна"))
                } }
        }
    }

    /** Inspect peer directory before future encrypted sends; never implies a verified safety number. */
    internal fun inspectPeerDirectory(
        ownerUid: String,
        peerUid: String,
        onResult: (PeerDirectoryInspection?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching {
                val previous = peerDirectoryTrustStore.read(ownerUid, peerUid)
                val response = authenticated { api.peerCryptoDirectory(peerUid) }
                val root = JSONObject(response)
                val currentSequence = PeerDirectoryContinuity.checkpoint(root.getJSONObject("directory_checkpoint")).sequence
                val history = if (previous != null && currentSequence > previous.checkpoint.sequence) {
                    authenticated { api.peerCryptoDirectoryHistory(peerUid, previous.checkpoint.sequence) }
                } else null
                val inspection = PeerDirectoryContinuity.inspect(response, history, peerUid, previous)
                if (!inspection.requiresAcknowledgement) {
                    peerDirectoryTrustStore.save(ownerUid, PeerDirectoryContinuity.trust(peerUid, inspection))
                }
                inspection
            }.onSuccess { inspection -> main.post { onResult(inspection, null) } }
                .onFailure { error -> main.post { onResult(null, "Каталог ключей не подтверждён: " + (error.message ?: "проверка не пройдена")) } }
        }
    }

    /** Call ONLY after explicit user acknowledgement; does not mark a safety number verified. */
    internal fun acknowledgePeerDirectoryChange(
        ownerUid: String,
        peerUid: String,
        displayed: PeerDirectoryInspection,
        onResult: (Boolean, String?) -> Unit,
    ) {
        executor.execute {
            runCatching {
                require(displayed.requiresAcknowledgement) { "No directory change to acknowledge" }
                val previous = requireNotNull(peerDirectoryTrustStore.read(ownerUid, peerUid)) { "Missing previous checkpoint" }
                val response = authenticated { api.peerCryptoDirectory(peerUid) }
                val history = authenticated { api.peerCryptoDirectoryHistory(peerUid, previous.checkpoint.sequence) }
                val current = PeerDirectoryContinuity.inspect(response, history, peerUid, previous)
                require(current.requiresAcknowledgement && current.checkpoint == displayed.checkpoint &&
                    current.devices.keys == displayed.devices.keys &&
                    current.devices.all { (id, item) ->
                        val shown = displayed.devices[id]
                        shown != null && shown.signingKey.contentEquals(item.signingKey) &&
                            shown.identityDhKey.contentEquals(item.identityDhKey)
                    }
                ) { "Каталог изменился повторно: перечитайте список устройств" }
                peerDirectoryTrustStore.save(ownerUid, PeerDirectoryContinuity.trust(peerUid, current))
            }.onSuccess { main.post { onResult(true, null) } }
                .onFailure { error -> main.post { onResult(false, error.message ?: "Подтверждение отклонено") } }
        }
    }

    fun prepareCryptoDevice(
        accountUid: String,
        onResult: (CryptoDeviceSnapshot?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching {
                val existing = cryptoDeviceStore.read(accountUid)
                val material = existing ?: CryptoDeviceMaterial.generate(accountUid).also(cryptoDeviceStore::save)
                // Initialize ONLY when explicitly provisioning a fresh crypto identity.
                // Never reset a lost ledger for an existing registered device.
                if (existing == null) e2eeSessionStore.initialize(accountUid, material.deviceUid)
                authenticated { api.registerCryptoDevice(material) }
            }
                .onSuccess { snapshot -> main.post { onResult(snapshot, null) } }
                .onFailure { error -> main.post {
                    onResult(null, error.message ?: "Не удалось зарегистрировать криптографическое устройство")
                } }
        }
    }

    fun localCryptoDeviceStatus(
        accountUid: String,
        onResult: (CryptoDeviceSnapshot?, Boolean, String?) -> Unit,
    ) {
        executor.execute {
            runCatching {
                val material = cryptoDeviceStore.read(accountUid)
                    ?: return@runCatching Pair<CryptoDeviceSnapshot?, Boolean>(null, false)
                Pair(authenticated { api.ownCryptoDevice(material) }, true)
            }
                .onSuccess { (remote, local) -> main.post { onResult(remote, local, null) } }
                .onFailure { error -> main.post {
                    // Do not confuse network failure with a missing or revoked remote device.
                    onResult(null, true, error.message ?: "Не удалось проверить устройство")
                } }
        }
    }

    fun revokeCryptoDevice(
        accountUid: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        executor.execute {
            runCatching {
                val material = cryptoDeviceStore.read(accountUid)
                    ?: error("Локальные ключи отсутствуют")
                authenticated { api.revokeCryptoDevice(material) }
                // Server-confirmed revocation is the required first step.
                cryptoDeviceStore.clearAfterServerRevocation(accountUid)
            }
                .onSuccess { main.post { onResult(true, null) } }
                .onFailure { error -> main.post {
                    onResult(false, error.message ?: "Не удалось отозвать устройство")
                } }
        }
    }

    fun loadMessengerEncryption(peerUid: String, onResult: (MessengerEncryptionStatus?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.messengerEncryption(peerUid) } }
                .onSuccess { status -> main.post { onResult(status, null) } }
                .onFailure { error -> main.post { onResult(null, messengerEncryptionError(error)) } }
        }
    }

    private fun checkedPlaintextMessenger(peerUid: String) {
        val policy = authenticated { api.messengerEncryption(peerUid) }
        requirePlaintextMessengerTransport(policy)
    }

    private fun messengerEncryptionError(error: Throwable): String = when (error) {
        is PubChatApi.ApiFailure -> when (error.code) {
            401 -> "Сессия истекла. Повторно войдите в PubChat."
            403, 404 -> "Диалог недоступен: блокировка или ограничение приватности."
            else -> "Не удалось проверить шифрование на сервере (HTTP ${error.code}). Отправка заблокирована."
        }
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: "Не удалось проверить политику шифрования. Отправка заблокирована."
    }

    fun markPrivateRead(messageUid: String): Boolean = messengerRealtime?.markRead(messageUid) == true

    fun editPrivateMessage(peerUid: String, messageUid: String, content: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        checkedPlaintextMessenger(peerUid)
        api.editPrivateMessage(messageUid, content)
    }

    fun deletePrivateMessage(messageUid: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        api.deletePrivateMessage(messageUid)
    }

    fun replyPrivateMessage(peerUid: String, messageUid: String, content: String, onDone: (PubChatApi.ChatMessage?, String?) -> Unit) {
        if (content.isBlank()) {
            onDone(null, "Введите текст ответа")
            return
        }
        executor.execute {
            runCatching { checkedPlaintextMessenger(peerUid); authenticated { api.replyPrivateMessage(messageUid, content) } }
                .onSuccess { created -> main.post { onDone(created, null) } }
                .onFailure { error -> main.post { onDone(null, humanMessage(error)) } }
        }
    }

    fun reactPrivateMessage(messageUid: String, emoji: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        api.reactPrivateMessage(messageUid, emoji)
    }

    fun forwardPrivateMessage(messageUid: String, targetPeerUid: String, onDone: (String?) -> Unit) = messengerAction(onDone) {
        checkedPlaintextMessenger(targetPeerUid)
        api.forwardPrivateMessage(messageUid, targetPeerUid)
    }

    private fun messengerAction(onDone: (String?) -> Unit, block: () -> Unit) {
        executor.execute {
            runCatching { authenticated { block() } }
                .onSuccess { main.post { onDone(null) } }
                .onFailure { error -> main.post { onDone(humanMessage(error)) } }
        }
    }

    fun replyRoomMessage(messageUid: String, content: String, onDone: (PubChatApi.ChatMessage?, String?) -> Unit) {
        if (content.isBlank()) {
            onDone(null, "Введите текст ответа")
            return
        }
        executor.execute {
            runCatching { authenticated { api.replySpaceMessage(messageUid, content) } }
                .onSuccess { saved -> main.post { onDone(saved, null) } }
                .onFailure { error -> main.post { onDone(null, humanMessage(error)) } }
        }
    }

    fun sendSpaceMessage(content: String, accountUid: String, displayName: String, replyToUid: String? = null): Boolean {
        if (content.isBlank()) return false
        return realtime?.sendMessage(content, accountUid, displayName, replyToUid) == true
    }

    fun saveProfile(current: AppState.SignedIn, name: String, bio: String, city: String, country: String, intent: String, onDone: (AppState.SignedIn?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.updateProfile(name, bio, city, country, intent) } }
                .onSuccess { identity -> main.post { onDone(identity.toSignedIn(current.spaces), null) } }
                .onFailure { error -> main.post { onDone(null, humanMessage(error)) } }
        }
    }

    fun loadSpacePanel(spaceUid: String, section: String, onResult: (List<JSONObject>, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.spacePanelItems(spaceUid, section) } }
                .onSuccess { items -> main.post { onResult(items, null) } }
                .onFailure { error -> main.post { onResult(emptyList(), humanMessage(error)) } }
        }
    }

    fun spaceAction(spaceUid: String, action: String, target: String = "", value: String = "", detail: String = "", onDone: (String?) -> Unit) = messengerAction(onDone) {
        when (action) {
            "leave" -> api.leaveSpace(spaceUid)
            "save" -> api.updateSpace(spaceUid, value, detail)
            "member" -> api.manageSpaceMember(spaceUid, target, value)
            "rule" -> api.addSpaceRule(spaceUid, value, detail)
            "delete-rule" -> api.deleteSpaceRule(spaceUid, target)
            "event" -> api.addSpaceEvent(spaceUid, value, detail, target)
            "cancel-event" -> api.cancelSpaceEvent(spaceUid, target)
            "report" -> api.updateSpaceReport(spaceUid, target, value)
            "warning", "restrict" -> api.moderateSpaceMember(spaceUid, target, action, detail)
            "reply" -> api.replySpaceMessage(target, value)
            else -> error("Неизвестное действие")
        }
    }

    fun saveSpaceCategories(
        spaceUid: String,
        categorySlugs: List<String>,
        onResult: (PubChatApi.SpaceSummary?, String?) -> Unit,
    ) {
        executor.execute {
            runCatching { authenticated { api.updateSpaceCategories(spaceUid, categorySlugs) } }
                .onSuccess { updated -> main.post { onResult(updated, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    fun refreshSpace(spaceUid: String, onResult: (PubChatApi.SpaceSummary?, String?) -> Unit) {
        executor.execute {
            runCatching { authenticated { api.space(spaceUid) } }
                .onSuccess { space -> main.post { onResult(space, null) } }
                .onFailure { error -> main.post { onResult(null, humanMessage(error)) } }
        }
    }

    /**
     * Save an authorized Space/legacy DM attachment to a user-selected SAF
     * document. Encrypted DM messages deliberately never expose raw attachments.
     */
    /**
     * Private, bounded, same-origin authenticated media preview. Caller takes
     * ownership of a successful temporary file and must delete it on dismiss.
     * Never use this for E2EE message envelopes or off-origin media.
     */
    fun previewAttachment(attachment: PubChatApi.Attachment,
                          onResult: (java.io.File?, String?) -> Unit) {
        executor.execute {
            var temp: java.io.File? = null
            runCatching {
                val mime = attachment.mimeType.orEmpty().lowercase()
                require(mime.startsWith("image/") || mime.startsWith("audio/") ||
                    mime.startsWith("video/")) { "Этот формат не поддерживает предпросмотр" }
                val suffix = when (mime) {
                    "image/jpeg" -> ".jpg"
                    "image/png" -> ".png"
                    "image/webp" -> ".webp"
                    "image/gif" -> ".gif"
                    "audio/mp4", "audio/x-m4a" -> ".m4a"
                    "audio/mpeg" -> ".mp3"
                    "audio/ogg" -> ".ogg"
                    "video/mp4" -> ".mp4"
                    "video/webm" -> ".webm"
                    else -> ".media"
                }
                temp = java.io.File.createTempFile("pubchat_preview_", suffix, appContext.cacheDir)
                val target = checkNotNull(temp)
                authenticated {
                    java.io.FileOutputStream(target, false).use { output ->
                        api.downloadAttachment(attachment.url, output)
                    }
                }
                target
            }.onSuccess { file ->
                main.post { onResult(file, null) }
            }.onFailure { error ->
                temp?.delete()
                main.post { onResult(null, humanMessage(error)) }
            }
        }
    }

    fun saveAttachment(attachment: PubChatApi.Attachment, destination: Uri,
                       onDone: (String?) -> Unit) {
        executor.execute {
            runCatching {
                authenticated {
                    val output = appContext.contentResolver.openOutputStream(destination, "wt")
                        ?: error("Не удалось открыть документ для сохранения")
                    output.use { api.downloadAttachment(attachment.url, it) }
                }
            }.onSuccess { main.post { onDone(null) } }
                .onFailure { failure ->
                    // CreateDocument produced a fresh URI; discard partial files.
                    runCatching { appContext.contentResolver.delete(destination, null, null) }
                    main.post { onDone(humanMessage(failure)) }
                }
        }
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


    /** Upload a locally recorded AAC voice note through the existing Space attachment protocol. */
    fun sendSpaceVoiceNote(file: java.io.File, accountUid: String, displayName: String,
                           onDone: (String?) -> Unit) {
        executor.execute {
            runCatching {
                require(file.isFile && file.length() in 1..2_097_152) {
                    "Голосовое сообщение пустое или превышает 2 МБ"
                }
                val bytes = file.readBytes()
                val dataUrl = "data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                check(realtime?.sendAttachment("", accountUid, displayName,
                    "Голосовое сообщение.m4a", "audio/mp4", bytes.size, dataUrl) == true) {
                    "Пространство не подключено"
                }
            }.onSuccess { main.post { onDone(null) } }
                .onFailure { error -> main.post { onDone(humanMessage(error)) } }
                .also { file.delete() }
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
                            if (callSocket !== socket) return@post
                            if (phase == "reconnecting") {
                                callEngine?.restartIce()
                                val recoveryEpoch = ++iceRecoveryEpoch
                                main.postDelayed({
                                    if (recoveryEpoch == iceRecoveryEpoch && callEngine?.callUid != null) {
                                        endCall()
                                        callStatusCallback?.invoke("error", null, "audio")
                                    }
                                }, 12_000L)
                            } else if (phase == "active" || phase == "error" || phase == "idle") {
                                ++iceRecoveryEpoch
                            }
                            if (phase == "error") {
                                endCall()
                                callStatusCallback?.invoke("error", null, "audio")
                            } else if (phase != "idle") {
                                callStatusCallback?.invoke(phase, callEngine?.peerUid, callEngine?.mode ?: "audio")
                            }
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
        ++iceRecoveryEpoch
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
                            if (frame.optBoolean("ice_restart")) {
                                ++iceRecoveryEpoch
                                callEngine?.answerRestart(frame.optJSONObject("sdp") ?: JSONObject())
                            }
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
        ++iceRecoveryEpoch
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
        ++iceRecoveryEpoch
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
        ++spaceEpoch
        spaceSubscription = null
        spaceConnecting = false
        spaceInitialized = false
        spaceStatus = "idle"
        cancelSpaceRetry()
        realtime?.close()
        realtime = null
    }

    fun closeMessenger() {
        ++messengerEpoch
        messengerSubscription = null
        messengerConnecting = false
        messengerStatus = "idle"
        cancelMessengerRetry()
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
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
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
        city = city, country = country,
    )

    private fun parseMessage(json: JSONObject): PubChatApi.ChatMessage? = parseChatMessage(json)

    private fun humanMessage(error: Throwable): String = when (error) {
        is PubChatApi.ApiFailure -> when (error.code) {
            401 -> "Неверные данные входа или сессия истекла"
            403 -> "Сервер отказал в доступе (HTTP 403). Проверьте статус аккаунта через веб-версию PubChat; если там вход работает — сообщите об ошибке Android-клиента."
            404 -> "Объект больше недоступен"
            409 -> "Действие конфликтует с текущим состоянием"
            422 -> "Проверьте заполненные данные"
            else -> "Сервер PubChat ответил с ошибкой ${error.code}"
        }
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Не удалось связаться с PubChat"
    }
}
