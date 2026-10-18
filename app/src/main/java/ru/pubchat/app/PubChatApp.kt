package ru.pubchat.app

import ru.pubchat.app.core.calls.shouldShowCallDialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.widthIn
import ru.pubchat.app.core.network.PubChatApi
import ru.pubchat.app.ui.components.RoomMessageCard
import ru.pubchat.app.ui.components.RoomControlPanel

enum class AppSection { SPACES, PEOPLE, MESSENGER, PROFILE, PERSON, SPACE, MESSENGER_CHAT, NOTIFICATIONS }

@Composable
fun PubChatApp(controller: AppController, deepLink: PubChatDeepLink? = null, onDeepLinkConsumed: () -> Unit = {}, incomingAction: IncomingCallAction? = null, onCallActionConsumed: () -> Unit = {}) {
    var state by remember { mutableStateOf<AppState>(AppState.Booting) }
    LaunchedEffect(controller) { controller.bootstrap { state = it } }
    DisposableEffect(controller) { onDispose { controller.close() } }

    when (val current = state) {
        AppState.Booting -> LoadingScreen()
        is AppState.SignedOut -> AuthScreen(
            error = current.error,
            onLogin = { identifier, password, done -> controller.login(identifier, password) { state = it; done() } },
            onRegister = { handle, name, email, password, intent, done -> controller.register(handle, name, email, password, intent) { state = it; done() } },
        )
        is AppState.SignedIn -> AuthenticatedShell(current, controller, deepLink, onDeepLinkConsumed, incomingAction, onCallActionConsumed) { state = it }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun AuthScreen(
    error: String?,
    onLogin: (String, String, () -> Unit) -> Unit,
    onRegister: (String, String, String, String, String, () -> Unit) -> Unit,
) {
    var registration by remember { mutableStateOf(false) }
    var identifier by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var intent by remember { mutableStateOf("open") }
    var submitting by remember { mutableStateOf(false) }
    // Completion is signaled on every request, including repeated identical HTTP 403 errors.

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            item {
                Text("PubChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Живые пространства и разговоры", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (registration) OutlinedButton(onClick = { registration = false }) { Text("Вход") } else Button(onClick = {}) { Text("Вход") }
                    if (registration) Button(onClick = {}) { Text("Создать образ") } else OutlinedButton(onClick = { registration = true; submitting = false }) { Text("Создать образ") }
                }
                Spacer(Modifier.height(20.dp))
            }
            if (!registration) {
                item {
                    OutlinedTextField(identifier, { identifier = it }, Modifier.fillMaxWidth(), label = { Text("Логин, email или телефон") }, singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            } else {
                item {
                    OutlinedTextField(handle, { handle = it }, Modifier.fillMaxWidth(), label = { Text("@handle") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Имя образа") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email (необязательно)") }, singleLine = true)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Пароль · минимум 8 символов") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Spacer(Modifier.height(12.dp))
                    Text("Намерение общения", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("open" to "Общение", "meet" to "Знакомства", "friends" to "Друзья").forEach { option ->
                            if (intent == option.first) Button(onClick = { intent = option.first }) { Text(option.second) }
                            else OutlinedButton(onClick = { intent = option.first }) { Text(option.second) }
                        }
                    }
                }
            }
            item {
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        submitting = true
                        if (registration) onRegister(handle, name, email, password, intent) { submitting = false }
                        else onLogin(identifier, password) { submitting = false }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitting && password.isNotBlank() && if (registration) handle.isNotBlank() else identifier.isNotBlank(),
                ) { Text(if (submitting) "Подключаем…" else if (registration) "Создать образ" else "Войти") }
            }
        }
    }
}

@Composable
private fun AuthenticatedShell(state: AppState.SignedIn, controller: AppController, deepLink: PubChatDeepLink?, onDeepLinkConsumed: () -> Unit, incomingAction: IncomingCallAction?, onCallActionConsumed: () -> Unit, onState: (AppState) -> Unit) {
    var section by remember { mutableStateOf(AppSection.SPACES) }
    var people by remember { mutableStateOf<List<PubChatApi.PersonSummary>>(emptyList()) }
    var peopleLoading by remember { mutableStateOf(false) }
    var peopleError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var person by remember { mutableStateOf<PubChatApi.PersonSummary?>(null) }
    var activeSpace by remember { mutableStateOf<PubChatApi.SpaceSummary?>(null) }
    var showRoomPanel by remember { mutableStateOf(false) }
    var roomReply by remember { mutableStateOf<PubChatApi.ChatMessage?>(null) }
    var roomReplySending by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf<List<PubChatApi.ChatMessage>>(emptyList()) }
    var connection by remember { mutableStateOf("idle") }
    var chatError by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }

    var dialogs by remember { mutableStateOf<List<PubChatApi.DialogSummary>>(emptyList()) }
    var dialogsLoading by remember { mutableStateOf(false) }
    var messengerError by remember { mutableStateOf<String?>(null) }
    var messengerConnection by remember { mutableStateOf("idle") }
    var activeDialog by remember { mutableStateOf<PubChatApi.DialogSummary?>(null) }
    var conversations by remember { mutableStateOf<Map<String, List<PubChatApi.ChatMessage>>>(emptyMap()) }
    var onlineStatuses by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var dmDraft by remember { mutableStateOf("") }
    var selectedMessageUid by remember { mutableStateOf<String?>(null) }
    var actionMode by remember { mutableStateOf<String?>(null) }
    var actionDraft by remember { mutableStateOf("") }
    var callPhase by remember { mutableStateOf("idle") }
    var callPeer by remember { mutableStateOf<String?>(null) }
    var callMode by remember { mutableStateOf("audio") }
    var callMuted by remember { mutableStateOf(false) }
    var callSpeaker by remember { mutableStateOf(false) }
    var callCameraEnabled by remember { mutableStateOf(true) }
    val callActivity = LocalContext.current as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    var notices by remember { mutableStateOf<List<PubChatApi.Notice>>(emptyList()) }
    var noticesLoading by remember { mutableStateOf(false) }
    var noticesError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller, lifecycleOwner) {
        val onStatus: (String, String?, String) -> Unit = { phase, peer, mode ->
            callPhase = phase
            if (peer != null) callPeer = peer
            if (phase == "incoming" || phase == "outgoing") { callSpeaker = mode == "video"; callCameraEnabled = true; callMuted = false }
            callMode = mode
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) controller.connectCalls(onStatus)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        controller.connectCalls(onStatus)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.disconnectCalls()
            controller.closeSpace()
            controller.closeMessenger()
        }
    }

    fun loadNotices() {
        noticesLoading = true
        controller.loadNotifications { loaded, error ->
            notices = loaded
            noticesError = error
            noticesLoading = false
        }
    }

    fun loadPeople() {
        peopleLoading = true
        controller.loadPeople(search) { result, error ->
            people = result
            peopleError = error
            peopleLoading = false
        }
    }

    fun openSpace(space: PubChatApi.SpaceSummary) {
        roomReply = null
        showRoomPanel = false
        controller.closeMessenger()
        section = AppSection.SPACE
        activeSpace = space
        messages = emptyList()
        chatError = null
        connection = "connecting"
        controller.openSpace(
            space.uid,
            onReady = { activeSpace = it },
            onInitialMessages = { messages = it },
            onMessage = { incoming -> messages = mergeMessage(messages, incoming) },
            onConnection = { connection = it },
            onError = { chatError = it },
        )
    }

    fun connectMessenger(peerUid: String? = activeDialog?.partnerUid) {
        controller.connectMessenger(
            activePeerUid = peerUid,
            dialogUids = dialogs.map { it.partnerUid },
            currentAccountUid = state.accountUid,
            onConversation = { uid, incoming ->
                conversations = conversations + (uid to incoming)
                incoming.filter { it.senderUid != state.accountUid && !it.isRead }.forEach { controller.markPrivateRead(it.uid) }
            },
            onMessage = { uid, incoming ->
                conversations = conversations + (uid to mergeMessage(conversations[uid].orEmpty(), incoming))
                if (activeDialog?.partnerUid == uid && incoming.senderUid != state.accountUid) controller.markPrivateRead(incoming.uid)
                dialogs = dialogs.map { dialog ->
                    if (dialog.partnerUid == uid) dialog.copy(lastMessage = incoming.content, unreadCount = if (activeDialog?.partnerUid == uid) 0 else dialog.unreadCount + 1) else dialog
                }
            },
            onRead = { messageUid ->
                conversations = conversations.mapValues { (_, list) -> list.map { if (it.uid == messageUid) it.copy(isRead = true) else it } }
            },
            onStatuses = { onlineStatuses = onlineStatuses + it },
            onConnection = { messengerConnection = it },
            onError = { messengerError = it },
        )
    }

    fun loadDialogsAndConnect(openPeer: String? = null, synthetic: PubChatApi.DialogSummary? = null) {
        dialogsLoading = true
        messengerError = null
        controller.loadDialogs { loaded, error ->
            dialogsLoading = false
            messengerError = error
            dialogs = if (synthetic != null && loaded.none { it.partnerUid == synthetic.partnerUid }) listOf(synthetic) + loaded else loaded
            val target = openPeer?.let { uid -> dialogs.find { it.partnerUid == uid } ?: synthetic }
            activeDialog = target
            connectMessenger(target?.partnerUid)
            if (target != null) section = AppSection.MESSENGER_CHAT
        }
    }

    fun openNotice(notice: PubChatApi.Notice) {
        controller.markNotificationRead(notice.uid) { error ->
            if (error == null) notices = notices.map { if (it.uid == notice.uid) it.copy(read = true) else it }
            else noticesError = error
        }
        val target = notice.targetUid ?: return
        when (notice.surface) {
            "space", "room" -> state.spaces.find { it.uid == target }?.let { openSpace(it) }
            "messenger", "dm" -> loadDialogsAndConnect(target)
        }
    }

    fun openDialog(dialog: PubChatApi.DialogSummary) {
        activeDialog = dialog.copy(unreadCount = 0)
        dialogs = dialogs.map { if (it.partnerUid == dialog.partnerUid) it.copy(unreadCount = 0) else it }
        selectedMessageUid = null
        actionMode = null
        dmDraft = ""
        section = AppSection.MESSENGER_CHAT
        if (messengerConnection == "connected") controller.requestConversation(dialog.partnerUid) else connectMessenger(dialog.partnerUid)
    }

    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            when (deepLink.kind) {
                "spaces" -> openSpace(state.spaces.find { it.uid == deepLink.uid } ?: PubChatApi.SpaceSummary(
                    deepLink.uid, "Пространство", null, null, null, null, null, null
                ))
                "messenger" -> {
                    section = AppSection.MESSENGER
                    loadDialogsAndConnect(deepLink.uid)
                }
            }
            onDeepLinkConsumed()
        }
    }

    fun hasCallPermissions(video: Boolean): Boolean {
        val activity = callActivity ?: return false
        val permissions = if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            else arrayOf(Manifest.permission.RECORD_AUDIO)
        val missing = permissions.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            activity.requestPermissions(missing.toTypedArray(), 4021)
            return false
        }
        return true
    }

    LaunchedEffect(incomingAction, callPhase, callPeer) {
        val pending = incomingAction ?: return@LaunchedEffect
        if (callPhase != "incoming") return@LaunchedEffect
        // The app has fetched a fresh, authenticated offer via its calls API.
        // A stale notification for another call is never acted on.
        if (!controller.isPendingCall(pending.callUid)) {
            onCallActionConsumed()
            return@LaunchedEffect
        }
        when (pending.kind) {
            "decline" -> { controller.endCall(decline = true); onCallActionConsumed() }
            "answer" -> {
                if (hasCallPermissions(callMode == "video")) {
                    controller.acceptCall()
                    onCallActionConsumed()
                } else {
                    // No microphone/camera activation from background.
                    // Leave UI visible for the user to approve permissions.
                    onCallActionConsumed()
                }
            }
        }
    }

    if (shouldShowCallDialog(callPhase, callPeer)) {
        AlertDialog(
            onDismissRequest = { if (callPhase == "error" || callPhase == "disconnected") { controller.endCall(); callPhase = "idle" } },
            title = { Text(if (callMode == "video") "Видеозвонок PubChat" else "Голосовой звонок PubChat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Участник: ${callPeer ?: "PubChat"}")
                    Text(when (callPhase) {
                        "incoming" -> "Входящий вызов"
                        "outgoing" -> "Вызываем…"
                        "connecting" -> "Соединяем…"
                        "reconnecting" -> "Восстанавливаем соединение…"
                        "active" -> "На связи · WebRTC DTLS-SRTP"
                        "error", "disconnected" -> "Не удалось установить соединение"
                        else -> "Подключение…"
                    })
                    Text("Медиа шифруется транспортом WebRTC. Проверенного E2EE звонка пока нет.",
                        style = MaterialTheme.typography.bodySmall)
                    if (callMode == "video" && callPhase in listOf("connecting", "video_track", "active")) {
                        val eglContext = controller.callVideoContext()
                        if (eglContext != null) {
                            val androidContext = LocalContext.current
                            val renderer = remember(eglContext) {
                                org.webrtc.SurfaceViewRenderer(androidContext).apply {
                                    init(eglContext, null)
                                    setMirror(false)
                                }
                            }
                            DisposableEffect(renderer) {
                                controller.attachRemoteVideo(renderer)
                                onDispose {
                                    controller.detachRemoteVideo(renderer)
                                    renderer.release()
                                }
                            }
                            AndroidView(factory = { renderer }, modifier = Modifier.fillMaxWidth().height(200.dp))
                        }
                    }
                    if (callPhase == "active" || callPhase == "video_track") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { callMuted = !callMuted; controller.setCallMuted(callMuted) }) {
                                Text(if (callMuted) "Включить микрофон" else "Выключить микрофон")
                            }
                            TextButton(onClick = { callSpeaker = !callSpeaker; controller.setCallSpeaker(callSpeaker) }) {
                                Text(if (callSpeaker) "Разговорный" else "Динамик")
                            }
                            if (callMode == "video") TextButton(onClick = { callCameraEnabled = !callCameraEnabled; controller.setCallCamera(callCameraEnabled) }) {
                                Text(if (callCameraEnabled) "Выключить камеру" else "Включить камеру")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (callPhase == "incoming") {
                    TextButton(onClick = { if (hasCallPermissions(callMode == "video")) controller.acceptCall() }) {
                        Text("Ответить")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.endCall(decline = callPhase == "incoming"); callPhase = "idle" }) {
                    Text(if (callPhase == "incoming") "Отклонить" else "Завершить")
                }
            },
        )
    }

    BackHandler(enabled = section == AppSection.SPACE && !showRoomPanel) {
        controller.closeSpace(); section = AppSection.SPACES
    }
    if (showRoomPanel) activeSpace?.let { space ->
        RoomControlPanel(space, state.accountUid, controller,
            onClose = { showRoomPanel = false },
            onChanged = { activeSpace = it },
            onLeft = { showRoomPanel = false; controller.closeSpace(); activeSpace = null; section = AppSection.SPACES; controller.reload(state, onState) })
    }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (section == AppSection.SPACE) IconButton(onClick = { controller.closeSpace(); section = AppSection.SPACES }, modifier = Modifier.size(40.dp).semantics { contentDescription = "К списку пространств" }) { Text("‹", fontSize = 30.sp) }
                Column(Modifier.weight(1f).let { if (section == AppSection.SPACE) it.clickable { showRoomPanel = true } else it }) {
                    Text(
                        when (section) {
                            AppSection.SPACES -> "Пространства"
                            AppSection.PEOPLE -> "Люди"
                            AppSection.MESSENGER -> "Сообщения"
                            AppSection.NOTIFICATIONS -> "Уведомления"
                            AppSection.PROFILE -> "Профиль"
                            AppSection.PERSON -> person?.displayName ?: "Образ"
                            AppSection.SPACE -> activeSpace?.name ?: "Пространство"
                            AppSection.MESSENGER_CHAT -> activeDialog?.displayName ?: "Разговор"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    when (section) {
                        AppSection.SPACE -> Text("● ${connectionLabel(connection)}", fontSize = 10.sp, color = if (connection == "connected") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        AppSection.MESSENGER, AppSection.MESSENGER_CHAT -> Text(connectionLabel(messengerConnection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text("@${state.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (section == AppSection.SPACE) {
                    TextButton(onClick = { showRoomPanel = true }, enabled = activeSpace != null, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(38.dp)) { Text("О комнате", fontSize = 12.sp) }
                }
                if (section == AppSection.PERSON || section == AppSection.MESSENGER_CHAT) {
                    TextButton(onClick = {
                        when (section) {
                            AppSection.SPACE -> { controller.closeSpace(); section = AppSection.SPACES }
                            AppSection.PERSON -> section = AppSection.PEOPLE
                            AppSection.MESSENGER_CHAT -> { activeDialog = null; section = AppSection.MESSENGER }
                            else -> Unit
                        }
                    }) { Text(if (section == AppSection.SPACE) "← Пространства" else "Назад") }
                } else if (section == AppSection.PROFILE) {
                    TextButton(onClick = { controller.logout(onState) }) { Text("Выйти") }
                }
            }
        },
        bottomBar = {
            if (section !in listOf(AppSection.SPACE, AppSection.PERSON, AppSection.MESSENGER_CHAT)) {
                NavigationBar( containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationBarItem(selected = section == AppSection.SPACES,
                        onClick = { controller.closeMessenger(); section = AppSection.SPACES },
                        icon = { Icon(painterResource(R.drawable.ic_spaces), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text("Пространства", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                    NavigationBarItem(selected = section == AppSection.PEOPLE,
                        onClick = { controller.closeMessenger(); section = AppSection.PEOPLE; loadPeople() },
                        icon = { Icon(painterResource(R.drawable.ic_people), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text("Люди", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                    NavigationBarItem(selected = section == AppSection.MESSENGER,
                        onClick = { section = AppSection.MESSENGER; loadDialogsAndConnect() },
                        icon = { Icon(painterResource(R.drawable.ic_messages), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text("Сообщения", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                    NavigationBarItem(selected = section == AppSection.NOTIFICATIONS,
                        onClick = { controller.closeMessenger(); section = AppSection.NOTIFICATIONS; loadNotices() },
                        icon = { Icon(painterResource(R.drawable.ic_notifications), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text("Уведомления", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                    NavigationBarItem(selected = section == AppSection.PROFILE,
                        onClick = { controller.closeMessenger(); section = AppSection.PROFILE },
                        icon = { Icon(painterResource(R.drawable.ic_profile), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text("Профиль", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (section) {
                AppSection.SPACES -> SpacesContent(state, controller, onState, ::openSpace)
                AppSection.PEOPLE -> PeopleContent(people, peopleLoading, peopleError, search, { search = it }, ::loadPeople) { selected ->
                    controller.loadProfile(selected.accountUid) { loaded, error ->
                        if (loaded != null) { person = loaded; section = AppSection.PERSON } else peopleError = error
                    }
                }
                AppSection.MESSENGER -> MessengerListContent(dialogs, dialogsLoading, messengerError, onlineStatuses, ::openDialog) { loadDialogsAndConnect() }
                AppSection.NOTIFICATIONS -> NotificationContent(
                    notices, noticesLoading, noticesError, ::loadNotices, ::openNotice
                ) {
                    controller.markAllNotificationsRead { error ->
                        if (error == null) notices = notices.map { it.copy(read = true) }
                        else noticesError = error
                    }
                }
                AppSection.PROFILE -> ru.pubchat.app.ui.components.ProfilePanel(state, controller, onState)
                AppSection.PERSON -> PersonContent(person) { target ->
                    val synthetic = PubChatApi.DialogSummary(target.accountUid, target.displayName, target.handle, target.avatar, null, null, 0)
                    loadDialogsAndConnect(target.accountUid, synthetic)
                }
                AppSection.SPACE -> SpaceChatContent(activeSpace, messages, state.accountUid, connection, chatError, draft, { draft = it },
                    reply = roomReply, sending = roomReplySending,
                    onReply = { roomReply = it }, onCancelReply = { roomReply = null },
                    onAttach = { uri -> controller.sendSpaceAttachment(uri, state.accountUid, state.displayName) { error -> chatError = error } },
                    onSend = send@{
                        if (roomReplySending) return@send
                        if (controller.sendSpaceMessage(draft, state.accountUid, state.displayName, roomReply?.uid)) {
                            draft = ""; roomReply = null; chatError = null
                        }
                        else chatError = "Соединение ещё не готово"
                    })
                AppSection.MESSENGER_CHAT -> MessengerChatContent(
                    onCall = { video ->
                        val peer = activeDialog?.partnerUid
                        if (peer != null && hasCallPermissions(video)) controller.startCall(peer, video)
                    },
                    dialog = activeDialog,
                    messages = conversations[activeDialog?.partnerUid].orEmpty(),
                    currentAccountUid = state.accountUid,
                    connection = messengerConnection,
                    error = messengerError,
                    draft = dmDraft,
                    onDraft = { dmDraft = it },
                    selectedUid = selectedMessageUid,
                    onSelect = { selectedMessageUid = if (selectedMessageUid == it) null else it; actionMode = null; actionDraft = "" },
                    actionMode = actionMode,
                    actionDraft = actionDraft,
                    onActionMode = { actionMode = it; actionDraft = conversations[activeDialog?.partnerUid].orEmpty().find { msg -> msg.uid == selectedMessageUid }?.content.orEmpty() },
                    onActionDraft = { actionDraft = it },
                    onSend = {
                        val peer = activeDialog?.partnerUid
                        if (peer != null && controller.sendPrivateMessage(peer, dmDraft)) dmDraft = "" else messengerError = "Личные сообщения ещё переподключаются"
                    },
                    onAction = { action ->
                        val uid = selectedMessageUid ?: return@MessengerChatContent
                        val done: (String?) -> Unit = { error -> messengerError = error; if (error == null) { actionMode = null; actionDraft = ""; selectedMessageUid = null } }
                        when (action) {
                            "reply" -> {
                                val peerUid = activeDialog?.partnerUid
                                val sentText = actionDraft
                                messengerError = null
                                controller.replyPrivateMessage(uid, sentText) { created, error ->
                                    messengerError = error
                                    if (created != null && peerUid != null) {
                                        conversations = conversations + (peerUid to mergeMessage(conversations[peerUid].orEmpty(), created))
                                        dialogs = dialogs.map { dialog ->
                                            if (dialog.partnerUid == peerUid) dialog.copy(lastMessage = created.content) else dialog
                                        }
                                        if (selectedMessageUid == uid && actionDraft == sentText) {
                                            actionMode = null
                                            actionDraft = ""
                                            selectedMessageUid = null
                                        }
                                    }
                                }
                            }
                            "edit" -> controller.editPrivateMessage(uid, actionDraft, done)
                            "delete" -> controller.deletePrivateMessage(uid, done)
                            "react" -> controller.reactPrivateMessage(uid, "👍", done)
                            "forward" -> dialogs.firstOrNull { it.partnerUid != activeDialog?.partnerUid }?.let { controller.forwardPrivateMessage(uid, it.partnerUid, done) }
                                ?: run { messengerError = "Нет другого диалога для пересылки" }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NavButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun SpacesContent(state: AppState.SignedIn, controller: AppController, onState: (AppState) -> Unit, onOpen: (PubChatApi.SpaceSummary) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (!state.error.isNullOrBlank()) Text(state.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (state.loading) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.spaces, key = PubChatApi.SpaceSummary::uid) { space ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(space) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(space.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        space.purpose?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        space.description?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Spacer(Modifier.height(6.dp))
                        Text(if (space.isActiveMember) "Открыть разговор" else "Вступить и открыть", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item { OutlinedButton(onClick = { controller.reload(state, onState) }, Modifier.fillMaxWidth()) { Text("Обновить") } }
        }
    }
}

@Composable
private fun PeopleContent(people: List<PubChatApi.PersonSummary>, loading: Boolean, error: String?, query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, onOpen: (PubChatApi.PersonSummary) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, Modifier.weight(1f), label = { Text("Имя или @handle") }, singleLine = true)
            TextButton(onClick = onSearch) { Text("Найти") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(people, key = PubChatApi.PersonSummary::accountUid) { person ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(person) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ru.pubchat.app.ui.components.UserAvatar(person.displayName, person.avatar)
                            Column(Modifier.weight(1f)) {
                        Text(person.displayName, fontWeight = FontWeight.SemiBold)
                        person.handle?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        person.bio?.let { Text(it, Modifier.padding(top = 8.dp)) }
                        person.socialIntent?.let { Text(intentLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessengerListContent(dialogs: List<PubChatApi.DialogSummary>, loading: Boolean, error: String?, online: Map<String, Boolean>, onOpen: (PubChatApi.DialogSummary) -> Unit, onReload: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (loading) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dialogs.isEmpty()) item { Text("Личные разговоры появятся здесь после первого сообщения.", Modifier.padding(12.dp)) }
            items(dialogs, key = PubChatApi.DialogSummary::partnerUid) { dialog ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(dialog) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ru.pubchat.app.ui.components.UserAvatar(dialog.displayName, dialog.avatar)
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(dialog.displayName, fontWeight = FontWeight.SemiBold)
                                if (online[dialog.partnerUid] == true) Text("●", color = MaterialTheme.colorScheme.primary)
                            }
                            dialog.handle?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(dialog.lastMessage ?: "Начните разговор", maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (dialog.unreadCount > 0) Text(dialog.unreadCount.coerceAtMost(99).toString(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item { OutlinedButton(onClick = onReload, Modifier.fillMaxWidth()) { Text("Обновить") } }
        }
    }
}

@Composable
private fun ProfileContent(state: AppState.SignedIn) {
    val theme = ru.pubchat.app.ui.theme.LocalThemeControl.current
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("@${state.handle}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.bio?.let { Text(it) }
        state.socialIntent?.let { Text("Намерение: ${intentLabel(it)}", color = MaterialTheme.colorScheme.primary) }
        Text("Устройство Android зарегистрировано отдельной сессией PubChat.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = theme.toggle) { Text(if (theme.dark) "Включить светлую тему" else "Включить тёмную тему") }
    }
}

@Composable
private fun PersonContent(person: PubChatApi.PersonSummary?, onMessage: (PubChatApi.PersonSummary) -> Unit) {
    if (person == null) return
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ru.pubchat.app.ui.components.UserAvatar(person.displayName, person.avatar, 80.dp)
        Text(person.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        person.handle?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        person.bio?.let { Text(it) }
        val location = listOfNotNull(person.city, person.country).joinToString(", ")
        if (location.isNotBlank()) Text(location)
        person.socialIntent?.let { Text(intentLabel(it), color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onMessage(person) }, Modifier.fillMaxWidth()) { Text("Написать сообщение") }
    }
}

@Composable
private fun SpaceChatContent(space: PubChatApi.SpaceSummary?, messages: List<PubChatApi.ChatMessage>, currentAccountUid: String, connection: String, error: String?, draft: String, onDraft: (String) -> Unit,
    reply: PubChatApi.ChatMessage?, sending: Boolean, onReply: (PubChatApi.ChatMessage) -> Unit, onCancelReply: () -> Unit,
    onAttach: (android.net.Uri) -> Unit, onSend: () -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var initialScroll by remember(space?.uid) { mutableStateOf(false) }
    LaunchedEffect(space?.uid, messages.lastOrNull()?.uid) {
        if (messages.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (!initialScroll || lastVisible >= messages.size - 3) {
                listState.scrollToItem(messages.lastIndex); initialScroll = true
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        space?.description?.let { Text(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (messages.isEmpty()) item { Text(if (connection == "connected") "Здесь пока тихо. Начните разговор." else "Подключаем разговор…") }
            items(messages, key = PubChatApi.ChatMessage::uid) { message ->
                val source = messages.find { it.uid == message.replyToUid }
                RoomMessageCard(message, message.senderUid == currentAccountUid, source,
                    onReplyJump = source?.let { { scope.launch { listState.animateScrollToItem(messages.indexOf(it)) }; Unit } },
                    onReply = if (message.contentType != "deleted") { { onReply(message) } } else null)
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onAttach(uri)
        }
        if (reply != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("↪ Ответ: ${reply.senderName ?: "Участник"}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                Text(reply.content.ifBlank { reply.attachmentName ?: "Вложение" }, maxLines = 2, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onCancelReply, enabled = !sending) { Text("Отмена") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = { picker.launch("*/*") }, enabled = connection == "connected" && reply == null && !sending, modifier = Modifier.size(40.dp).semantics { contentDescription = "Прикрепить файл" }) { Text("＋", fontSize = 23.sp) }
            Box(Modifier.weight(1f)) { Composer(draft, onDraft, connection == "connected" && !sending, onSend, padded = false) }
        }
    }
}

@Composable
private fun MessengerChatContent(
    onCall: (Boolean) -> Unit,
    dialog: PubChatApi.DialogSummary?,
    messages: List<PubChatApi.ChatMessage>,
    currentAccountUid: String,
    connection: String,
    error: String?,
    draft: String,
    onDraft: (String) -> Unit,
    selectedUid: String?,
    onSelect: (String) -> Unit,
    actionMode: String?,
    actionDraft: String,
    onActionMode: (String) -> Unit,
    onActionDraft: (String) -> Unit,
    onSend: () -> Unit,
    onAction: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            dialog?.handle?.let { Text("@$it", Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = { onCall(false) }, enabled = dialog != null && connection == "connected") { Text("☎") }
            TextButton(onClick = { onCall(true) }, enabled = dialog != null && connection == "connected") { Text("Видео") }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) item { Text(if (connection == "connected") "Можно начать с простого «привет»." else "Загружаем личный разговор…") }
            items(messages, key = PubChatApi.ChatMessage::uid) { message ->
                Column {
                    RoomMessageCard(message, message.senderUid == currentAccountUid, messages.find { it.uid == message.replyToUid }, onSelect = { onSelect(message.uid) })
                    if (selectedUid == message.uid) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onActionMode("reply") }) { Text("Ответ") }
                            TextButton(onClick = { onAction("react") }) { Text("👍") }
                            if (message.senderUid == currentAccountUid) TextButton(onClick = { onActionMode("edit") }) { Text("Изменить") }
                            if (message.senderUid == currentAccountUid) TextButton(onClick = { onAction("delete") }) { Text("Удалить") }
                            TextButton(onClick = { onAction("forward") }) { Text("Переслать") }
                        }
                        if (actionMode == "reply" || actionMode == "edit") {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(actionDraft, onActionDraft, Modifier.weight(1f), label = { Text(if (actionMode == "edit") "Новый текст" else "Ответ") })
                                TextButton(onClick = { onAction(actionMode) }, enabled = actionDraft.isNotBlank()) { Text("Готово") }
                            }
                        }
                    }
                }
            }
        }
        Composer(draft, onDraft, connection == "connected", onSend)
    }
}

@Composable
private fun MessageCard(message: PubChatApi.ChatMessage, own: Boolean, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (own) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!own) Text(message.senderName ?: "Участник PubChat",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                if (message.replyToUid != null) Text("↪ Ответ на сообщение",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(if (message.contentType == "deleted") "Сообщение удалено"
                    else message.attachmentName?.let { "📎 $it" } ?: message.content,
                    style = MaterialTheme.typography.bodyMedium)
                message.reactions?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
                if (own) Text(if (message.isRead) "✓✓ Прочитано" else "✓ Отправлено",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Composer(draft: String, onDraft: (String) -> Unit, enabled: Boolean, onSend: () -> Unit, padded: Boolean = true) {
    Row(Modifier.fillMaxWidth().then(if (padded) Modifier.padding(horizontal = 8.dp, vertical = 5.dp) else Modifier),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp)).heightIn(min = 40.dp, max = 120.dp).padding(horizontal = 12.dp, vertical = 9.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
            decorationBox = { field -> Box { if (draft.isEmpty()) Text(if (enabled) "Сообщение…" else "Соединение…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); field() } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled && draft.isNotBlank()) onSend() }),
            maxLines = 5,
            enabled = enabled,
        )
        IconButton(onClick = onSend, enabled = enabled && draft.isNotBlank(), modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
            .background(if (enabled && draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "Отправить сообщение" }) { Icon(painterResource(R.drawable.ic_send), contentDescription = null, modifier = Modifier.size(22.dp), tint = if (enabled && draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun NotificationContent(
    notices: List<PubChatApi.Notice>,
    loading: Boolean,
    error: String?,
    onReload: () -> Unit,
    onOpen: (PubChatApi.Notice) -> Unit,
    onReadAll: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Требует внимания", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onReadAll, enabled = notices.any { !it.read }) { Text("Прочитать всё") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) CircularProgressIndicator()
        val pending = notices.filterNot { it.read }
        val archived = notices.filter { it.read }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (pending.isEmpty()) item { Text("Нет новых уведомлений") }
            items(pending, key = { "new:${it.uid}" }) { notice ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(notice) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(notice.title, fontWeight = FontWeight.Bold)
                        Text(notice.body)
                        Text("Открыть", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (archived.isNotEmpty()) {
                item { Text("Прочитанные", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                items(archived, key = { "read:${it.uid}" }) { notice ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(notice) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(notice.title)
                            Text(notice.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { TextButton(onClick = onReload) { Text("Обновить") } }
        }
    }
}

private fun mergeMessage(messages: List<PubChatApi.ChatMessage>, incoming: PubChatApi.ChatMessage): List<PubChatApi.ChatMessage> {
    val index = messages.indexOfFirst { it.uid == incoming.uid }
    return if (index >= 0) messages.toMutableList().also { it[index] = incoming } else messages + incoming
}

private fun connectionLabel(value: String) = when (value) {
    "connecting" -> "Подключаемся"
    "authenticating" -> "Проверяем сессию"
    "connected" -> "В эфире"
    "disconnected" -> "Связь потеряна"
    else -> "Не подключено"
}

private fun intentLabel(value: String) = when (value) {
    "meet" -> "Знакомства"
    "games" -> "Игры"
    "friends" -> "Друзья"
    "quiet" -> "Спокойное общение"
    else -> "Открыт к общению"
}
