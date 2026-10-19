package ru.pubchat.app

import ru.pubchat.app.core.calls.shouldShowCallDialog
import ru.pubchat.app.core.auth.AuthFormRules
import androidx.compose.foundation.layout.imePadding

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Switch
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
import ru.pubchat.app.ui.components.SocialAvailabilityControls
import ru.pubchat.app.ui.components.FriendshipHubContent
import ru.pubchat.app.ui.components.PubChatEmptyState
import ru.pubchat.app.ui.components.PubChatListSkeleton
import ru.pubchat.app.ui.components.RoomMessageCard
import ru.pubchat.app.ui.theme.pubChatBackdrop
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import ru.pubchat.app.ui.components.RoomControlPanel

enum class AppSection { SPACES, PEOPLE, FRIENDSHIPS, MESSENGER, PROFILE, PERSON, SPACE, MESSENGER_CHAT, NOTIFICATIONS }

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
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(colors.background, colors.primaryContainer, colors.background)
            )
        ), contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(colors.primary, androidx.compose.ui.graphics.Color(0xFF06C7EC))
                    )
                ), contentAlignment = Alignment.Center,
            ) {
                Text("P", fontSize = 50.sp, fontWeight = FontWeight.ExtraBold, color = androidx.compose.ui.graphics.Color.White)
            }
            Text("PubChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
            Text("Люди. Сообщества. Больше возможностей.", color = colors.onSurfaceVariant)
            CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
            Text("Восстанавливаем сеанс…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
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
    val canSubmit = !submitting && if (registration) {
        AuthFormRules.canRegister(handle, name, password)
    } else {
        AuthFormRules.canLogin(identifier, password)
    }
    val registrationHint = if (registration) {
        AuthFormRules.registrationHint(handle, name, password)
    } else null

    fun selectMode(nextRegistration: Boolean) {
        if (submitting || registration == nextRegistration) return
        registration = nextRegistration
        // Never keep a password entered in one auth flow when switching modes.
        password = ""
    }

    fun submit() {
        if (!canSubmit) return
        submitting = true
        if (registration) {
            onRegister(handle.trim(), name.trim(), email.trim(), password, intent) {
                submitting = false
            }
        } else {
            onLogin(identifier.trim(), password) {
                submitting = false
            }
        }
    }

    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding().padding(insets),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(60.dp).clip(RoundedCornerShape(18.dp))
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(MaterialTheme.colorScheme.primary, androidx.compose.ui.graphics.Color(0xFF06C7EC))
                            )),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("P", fontWeight = FontWeight.ExtraBold, fontSize = 36.sp,
                            color = androidx.compose.ui.graphics.Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("PubChat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Люди. Сообщества. Возможности.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    if (registration) "Создать аккаунт" else "С возвращением!",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (registration) "Присоединяйся к сообществу людей и возможностей."
                    else "Войди в свой аккаунт, чтобы продолжить общение.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    androidx.compose.material3.FilterChip(
                        selected = !registration,
                        onClick = { selectMode(false) },
                        label = {
                            Text("Вход", Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        },
                        enabled = !submitting,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                    androidx.compose.material3.FilterChip(
                        selected = registration,
                        onClick = { selectMode(true) },
                        label = {
                            Text("Регистрация", Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        },
                        enabled = !submitting,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            if (!registration) {
                item {
                    OutlinedTextField(
                        identifier, { identifier = it.take(255) }, Modifier.fillMaxWidth(),
                        label = { Text("Логин или email") }, singleLine = true,
                        enabled = !submitting,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        password, { password = it.take(128) }, Modifier.fillMaxWidth(),
                        label = { Text("Пароль") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        enabled = !submitting,
                    )
                }
            } else {
                item {
                    OutlinedTextField(
                        handle, { handle = it.take(32) }, Modifier.fillMaxWidth(),
                        label = { Text("Имя пользователя") },
                        supportingText = { Text("3–32 символа: латиница, цифры, . и _") },
                        singleLine = true, enabled = !submitting,
                        isError = handle.isNotEmpty() && !AuthFormRules.validHandle(handle),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        name, { name = it.take(80) }, Modifier.fillMaxWidth(),
                        label = { Text("Имя в профиле (необязательно)") },
                        singleLine = true, enabled = !submitting,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        email, { email = it.take(254) }, Modifier.fillMaxWidth(),
                        label = { Text("Email (необязательно)") },
                        singleLine = true, enabled = !submitting,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        password, { password = it.take(128) }, Modifier.fillMaxWidth(),
                        label = { Text("Пароль") },
                        supportingText = { Text("От 8 до 128 символов") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, enabled = !submitting,
                        isError = password.isNotEmpty() && password.length < 8,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Намерение общения", fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall)
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf("open" to "Общение", "meet" to "Знакомства",
                            "friends" to "Друзья").forEach { (value, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = intent == value,
                                onClick = { intent = value },
                                label = { Text(label) },
                                enabled = !submitting,
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    if (registrationHint != null) {
                        Text(registrationHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = ::submit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = canSubmit,
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (submitting) "Подключаем…" else if (registration) "Создать аккаунт" else "Войти")
                }
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
    var peopleIntent by remember { mutableStateOf("all") }
    var peopleAvailableNow by remember { mutableStateOf(false) }
    var myAvailability by remember { mutableStateOf<PubChatApi.SocialAvailability?>(null) }
    var availabilityBusy by remember { mutableStateOf(false) }
    var availabilityError by remember { mutableStateOf<String?>(null) }
    var friendshipTab by remember { mutableStateOf("incoming") }
    var friendshipRows by remember { mutableStateOf<List<PubChatApi.FriendshipEntry>>(emptyList()) }
    var friendshipTotal by remember { mutableStateOf(0) }
    var friendshipLoading by remember { mutableStateOf(false) }
    var friendshipLoadingMore by remember { mutableStateOf(false) }
    var friendshipBusyUid by remember { mutableStateOf<String?>(null) }
    var friendshipError by remember { mutableStateOf<String?>(null) }
    var friendshipGeneration by remember { mutableStateOf(0) }
    var personReturnSection by remember { mutableStateOf(AppSection.PEOPLE) }
    var person by remember { mutableStateOf<PubChatApi.PersonSummary?>(null) }
    var personRelationship by remember { mutableStateOf<PubChatApi.SocialRelationship?>(null) }
    var personRelationLoading by remember { mutableStateOf(false) }
    var personRelationBusy by remember { mutableStateOf(false) }
    var personRelationError by remember { mutableStateOf<String?>(null) }
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
    var birthdayRemindersEnabled by remember { mutableStateOf(false) }
    var birthdayRemindersBusy by remember { mutableStateOf(false) }
    var birthdayRemindersError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller, lifecycleOwner) {
        val onStatus: (String, String?, String) -> Unit = { phase, peer, mode ->
            callPhase = phase
            if (peer != null) callPeer = peer
            if (phase == "incoming" || phase == "outgoing") { callSpeaker = mode == "video"; callCameraEnabled = true; callMuted = false }
            callMode = mode
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    controller.setForeground(true)
                    controller.connectCalls(onStatus)
                }
                Lifecycle.Event.ON_STOP -> controller.setForeground(false)
                else -> Unit
            }
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
        controller.loadBirthdayRemindersPreference { enabled, error ->
            if (enabled != null) birthdayRemindersEnabled = enabled
            birthdayRemindersError = error
        }
        noticesLoading = true
        controller.loadNotifications { loaded, error ->
            notices = loaded
            noticesError = error
            noticesLoading = false
        }
    }

    fun reloadPersonRelationship(accountUid: String) {
        personRelationLoading = true
        personRelationError = null
        controller.loadRelationship(accountUid) { relation, error ->
            if (person?.accountUid == accountUid) {
                personRelationship = relation
                personRelationError = error
                personRelationLoading = false
            }
        }
    }

    fun applyPersonRelation(
        action: (String, PubChatApi.SocialRelationship, (PubChatApi.SocialRelationship?, String?) -> Unit) -> Unit,
    ) {
        val uid = person?.accountUid ?: return
        val relation = personRelationship ?: return
        if (personRelationBusy || personRelationLoading) return
        personRelationBusy = true
        personRelationError = null
        action(uid, relation) { updated, error ->
            if (person?.accountUid == uid) {
                personRelationBusy = false
                if (updated != null) personRelationship = updated
                else personRelationError = error ?: "Не удалось обновить связь"
            }
        }
    }

    fun loadFriendships(append: Boolean = false) {
        if (append && (friendshipLoading || friendshipLoadingMore)) return
        val selected = friendshipTab
        val generation = ++friendshipGeneration
        if (append) friendshipLoadingMore = true else {
            friendshipLoading = true
            friendshipRows = emptyList()
            friendshipTotal = 0
            friendshipError = null
        }
        val offset = if (append) friendshipRows.size else 0
        controller.loadFriendships(selected, offset) { page, error ->
            if (generation == friendshipGeneration && selected == friendshipTab) {
                friendshipLoading = false
                friendshipLoadingMore = false
                if (page != null) {
                    friendshipRows = if (append) {
                        val known = friendshipRows.map { it.accountUid }.toSet()
                        friendshipRows + page.rows.filterNot { it.accountUid in known }
                    } else page.rows
                    friendshipTotal = page.total
                    friendshipError = null
                } else friendshipError = error ?: "Не удалось загрузить список"
            }
        }
    }

    fun friendshipAction(accountUid: String, action: String) {
        if (friendshipBusyUid != null) return
        friendshipBusyUid = accountUid
        friendshipError = null
        controller.friendAction(accountUid, action) { relationship, error ->
            friendshipBusyUid = null
            if (relationship != null && error == null) loadFriendships()
            else friendshipError = error ?: "Не удалось обработать заявку"
        }
    }

    fun setBirthdayReminders(enabled: Boolean) {
        if (birthdayRemindersBusy) return
        birthdayRemindersBusy = true
        birthdayRemindersError = null
        controller.setBirthdayRemindersPreference(enabled) { saved, error ->
            birthdayRemindersBusy = false
            if (saved != null) {
                birthdayRemindersEnabled = saved
                loadNotices()
            } else birthdayRemindersError = error ?: "Не удалось обновить настройку"
        }
    }

    fun loadPeople() {
        peopleLoading = true
        peopleError = null
        controller.loadPeople(search, peopleIntent.takeUnless { it == "all" }, peopleAvailableNow) { result, error ->
            // A transient refresh failure must not erase the last visible directory.
            if (error == null) people = result
            peopleError = error
            peopleLoading = false
        }
    }

    fun loadAvailability() {
        controller.loadSocialAvailability { loaded, error ->
            myAvailability = loaded
            availabilityError = error
        }
    }

    fun setAvailability(intent: String, duration: Int, note: String) {
        if (availabilityBusy) return
        availabilityBusy = true
        availabilityError = null
        controller.updateSocialAvailability(intent, duration, note) { loaded, error ->
            availabilityBusy = false
            if (error != null) availabilityError = error
            else { myAvailability = loaded; loadPeople() }
        }
    }

    fun clearAvailability() {
        if (availabilityBusy) return
        availabilityBusy = true
        controller.clearSocialAvailability { error ->
            availabilityBusy = false
            if (error != null) availabilityError = error
            else { myAvailability = null; loadPeople() }
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
            // Keep existing conversations on a recoverable network failure.
            if (error == null) dialogs = if (synthetic != null && loaded.none { it.partnerUid == synthetic.partnerUid }) listOf(synthetic) + loaded else loaded
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
                if (section == AppSection.SPACE) IconButton(onClick = { controller.closeSpace(); section = AppSection.SPACES }, modifier = Modifier.size(48.dp).semantics { contentDescription = "К списку пространств" }) { Text("‹", fontSize = 30.sp) }
                Column(Modifier.weight(1f).let { if (section == AppSection.SPACE) it.clickable { showRoomPanel = true } else it }) {
                    Text(
                        when (section) {
                            AppSection.SPACES -> "Пространства"
                            AppSection.PEOPLE -> "Люди"
                            AppSection.FRIENDSHIPS -> "Друзья и заявки"
                            AppSection.MESSENGER -> "Чаты"
                            AppSection.NOTIFICATIONS -> "Активность"
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
                    TextButton(onClick = { showRoomPanel = true }, enabled = activeSpace != null,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.height(48.dp).semantics { contentDescription = "Информация о пространстве" }) {
                        Text("Инфо", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (section == AppSection.PERSON || section == AppSection.MESSENGER_CHAT || section == AppSection.FRIENDSHIPS) {
                    TextButton(onClick = {
                        when (section) {
                            AppSection.SPACE -> { controller.closeSpace(); section = AppSection.SPACES }
                            AppSection.PERSON -> { section = personReturnSection; if (section == AppSection.FRIENDSHIPS) loadFriendships() }
                            AppSection.FRIENDSHIPS -> section = AppSection.PEOPLE
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
                    NavigationBarItem(selected = section == AppSection.PEOPLE || section == AppSection.FRIENDSHIPS,
                        onClick = { controller.closeMessenger(); section = AppSection.PEOPLE; loadPeople(); loadAvailability() },
                        icon = { Icon(painterResource(R.drawable.ic_people), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text("Люди", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                    NavigationBarItem(selected = section == AppSection.MESSENGER,
                        onClick = { section = AppSection.MESSENGER; loadDialogsAndConnect() },
                        icon = { Icon(painterResource(R.drawable.ic_messages), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text("Чаты", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                    NavigationBarItem(selected = section == AppSection.NOTIFICATIONS,
                        onClick = { controller.closeMessenger(); section = AppSection.NOTIFICATIONS; loadNotices() },
                        icon = { Icon(painterResource(R.drawable.ic_notifications), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text("Активность", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                    NavigationBarItem(selected = section == AppSection.PROFILE,
                        onClick = { controller.closeMessenger(); section = AppSection.PROFILE },
                        icon = { Icon(painterResource(R.drawable.ic_profile), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text("Профиль", fontSize = 10.sp, maxLines = 1, softWrap = false) })
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).pubChatBackdrop()) {
            Crossfade(targetState = section, animationSpec = tween(180), label = "Разделы PubChat") { visibleSection ->
              when (visibleSection) {
                AppSection.SPACES -> SpacesContent(state, controller, onState, ::openSpace)
                AppSection.PEOPLE -> PeopleContent(
                    people, peopleLoading, peopleError, search, { search = it },
                    peopleIntent, peopleAvailableNow,
                    onIntentChange = { peopleIntent = it; loadPeople() },
                    onAvailableChange = { peopleAvailableNow = it; loadPeople() },
                    availability = myAvailability,
                    availabilityBusy = availabilityBusy,
                    availabilityError = availabilityError,
                    onSaveAvailability = ::setAvailability,
                    onClearAvailability = ::clearAvailability,
                    onSearch = ::loadPeople,
                    onFriendships = { section = AppSection.FRIENDSHIPS; loadFriendships() },
                ) { selected ->
                    personReturnSection = AppSection.PEOPLE
                    personRelationship = null
                    personRelationError = null
                    controller.loadProfile(selected.accountUid) { loaded, error ->
                        if (loaded != null) {
                            person = loaded
                            section = AppSection.PERSON
                            reloadPersonRelationship(loaded.accountUid)
                        } else peopleError = error
                    }
                }
                AppSection.FRIENDSHIPS -> FriendshipHubContent(
                    tab = friendshipTab,
                    rows = friendshipRows,
                    total = friendshipTotal,
                    loading = friendshipLoading,
                    loadingMore = friendshipLoadingMore,
                    error = friendshipError,
                    busyUid = friendshipBusyUid,
                    onTab = { selected ->
                        if (selected != friendshipTab) {
                            friendshipTab = selected
                            loadFriendships()
                        }
                    },
                    onReload = { loadFriendships() },
                    onMore = { loadFriendships(append = true) },
                    onOpen = { row ->
                        personReturnSection = AppSection.FRIENDSHIPS
                        personRelationship = null
                        personRelationError = null
                        controller.loadProfile(row.accountUid) { loaded, error ->
                            if (loaded != null) {
                                person = loaded
                                section = AppSection.PERSON
                                reloadPersonRelationship(loaded.accountUid)
                            } else friendshipError = error
                        }
                    },
                    onAction = ::friendshipAction,
                )
                AppSection.MESSENGER -> MessengerListContent(dialogs, dialogsLoading, messengerError, onlineStatuses, ::openDialog) { loadDialogsAndConnect() }
                AppSection.NOTIFICATIONS -> NotificationContent(
                    notices = notices,
                    loading = noticesLoading,
                    error = noticesError,
                    onReload = ::loadNotices,
                    onOpen = ::openNotice,
                    onReadAll = {
                        controller.markAllNotificationsRead { error ->
                            if (error == null) notices = notices.map { it.copy(read = true) }
                            else noticesError = error
                        }
                    },
                    birthdayEnabled = birthdayRemindersEnabled,
                    birthdayBusy = birthdayRemindersBusy,
                    birthdayError = birthdayRemindersError,
                    onBirthdayToggle = ::setBirthdayReminders,
                )
                AppSection.PROFILE -> ru.pubchat.app.ui.components.ProfilePanel(state, controller, onState)
                AppSection.PERSON -> PersonContent(
                    person = person,
                    relationship = personRelationship,
                    loading = personRelationLoading,
                    busy = personRelationBusy,
                    error = personRelationError,
                    onReload = { person?.accountUid?.let(::reloadPersonRelationship) },
                    onFollow = {
                        applyPersonRelation { uid, relation, done ->
                            controller.setFollowing(uid, relation.following, done)
                        }
                    },
                    onFriendAction = { action ->
                        applyPersonRelation { uid, _, done -> controller.friendAction(uid, action, done) }
                    },
                    onBlock = {
                        applyPersonRelation { uid, relation, done ->
                            controller.setBlocked(uid, relation.blockedByMe, done)
                        }
                    },
                    onMessage = { target ->
                        val synthetic = PubChatApi.DialogSummary(target.accountUid, target.displayName, target.handle, target.avatar, null, null, 0)
                        loadDialogsAndConnect(target.accountUid, synthetic)
                    },
                )
                AppSection.SPACE -> SpaceChatContent(activeSpace, messages, state.accountUid, connection, chatError, draft, { draft = it },
                    reply = roomReply, sending = roomReplySending,
                    onReply = { roomReply = it }, onCancelReply = { roomReply = null },
                    onAttach = { uri -> controller.sendSpaceAttachment(uri, state.accountUid, state.displayName) { error -> chatError = error } },
                    onSend = send@{
                        if (roomReplySending || draft.isBlank()) return@send
                        val source = roomReply
                        if (source != null) {
                            val submitted = draft
                            val spaceUid = activeSpace?.uid
                            roomReplySending = true
                            controller.replyRoomMessage(source.uid, submitted) { saved, error ->
                                roomReplySending = false
                                if (error != null || saved == null) {
                                    chatError = error ?: "Сервер не подтвердил ответ"
                                } else {
                                    if (spaceUid == activeSpace?.uid) {
                                        messages = mergeMessage(messages, saved)
                                        if (draft == submitted && roomReply?.uid == source.uid) {
                                            draft = ""
                                            roomReply = null
                                        }
                                    }
                                    chatError = null
                                }
                            }
                        } else if (controller.sendSpaceMessage(draft, state.accountUid, state.displayName)) {
                            draft = ""; chatError = null
                        } else chatError = "Соединение ещё не готово"
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
}

@Composable
private fun NavButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick) { Text(label) } else TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun SpacesContent(state: AppState.SignedIn, controller: AppController, onState: (AppState) -> Unit, onOpen: (PubChatApi.SpaceSummary) -> Unit) {
    var categories by remember { mutableStateOf<List<PubChatApi.SpaceCategory>>(emptyList()) }
    var categoryError by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var filteredSpaces by remember { mutableStateOf<List<PubChatApi.SpaceSummary>?>(null) }
    var filterBusy by remember { mutableStateOf(false) }
    var filterError by remember { mutableStateOf<String?>(null) }

    fun fetchCategories() {
        controller.loadSpaceCategories { loaded, error ->
            if (error == null) categories = loaded
            categoryError = error
        }
    }
    fun fetchFiltered(slug: String) {
        filterBusy = true
        filterError = null
        filteredSpaces = emptyList()
        controller.loadSpacesForCategory(slug) { result, error ->
            if (selectedCategory == slug) {
                filterBusy = false
                if (result != null) filteredSpaces = result else filterError = error ?: "Не удалось загрузить категорию"
            }
        }
    }
    fun refreshCatalog() {
        if (selectedCategory != null) fetchFiltered(selectedCategory!!)
        else controller.reload(state, onState)
    }
    LaunchedEffect(Unit) { fetchCategories() }
    val visibleSpaces = if (selectedCategory == null) state.spaces else filteredSpaces.orEmpty()
    val visibleError = if (selectedCategory == null) state.error else filterError
    val visibleLoading = if (selectedCategory == null) state.loading else filterBusy

    Column(Modifier.fillMaxSize()) {
        if (visibleLoading && visibleSpaces.isEmpty()) {
            Column(Modifier.padding(16.dp)) {
                Text("Открывай своё", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Пространства по интересам", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PubChatListSkeleton()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Открывай своё", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Найди пространство для общения и новых идей",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("В каталоге: ${visibleSpaces.size}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box {
                                OutlinedButton(
                                    onClick = { categoryMenuOpen = true },
                                    enabled = !visibleLoading && categories.isNotEmpty(),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text(categories.find { it.slug == selectedCategory }?.name ?: "Все категории",
                                        maxLines = 1)
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = categoryMenuOpen,
                                    onDismissRequest = { categoryMenuOpen = false },
                                ) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("Все категории") },
                                        onClick = {
                                            categoryMenuOpen = false
                                            selectedCategory = null
                                            filteredSpaces = null
                                            filterError = null
                                            controller.reload(state, onState)
                                        },
                                    )
                                    categories.forEach { item ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            text = { Text(item.name) },
                                            onClick = {
                                                categoryMenuOpen = false
                                                if (selectedCategory != item.slug) {
                                                    selectedCategory = item.slug
                                                    fetchFiltered(item.slug)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            if (selectedCategory != null) {
                                TextButton(onClick = {
                                    selectedCategory = null
                                    filteredSpaces = null
                                    filterError = null
                                    controller.reload(state, onState)
                                }) { Text("Сбросить") }
                            }
                        }
                        categoryError?.let {
                            Text("Категории недоступны: $it", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { fetchCategories() }) { Text("Повторить категории") }
                        }
                        if (visibleLoading) {
                            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                if (!visibleError.isNullOrBlank()) item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (visibleSpaces.isEmpty()) "Каталог недоступен" else "Не удалось обновить пространства",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.SemiBold)
                            Text(visibleError, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (visibleSpaces.isNotEmpty()) {
                                Text("Показаны ранее загруженные пространства.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                            OutlinedButton(onClick = { refreshCatalog() }, enabled = !visibleLoading) {
                                Text("Повторить")
                            }
                        }
                    }
                }
                if (visibleSpaces.isEmpty() && !visibleLoading && visibleError.isNullOrBlank()) item {
                    PubChatEmptyState(
                        title = if (selectedCategory != null) "В категории пока нет пространств" else "Пока нет пространств",
                        explanation = if (selectedCategory != null) "Выберите другую категорию или сбросьте фильтр."
                            else "Здесь появятся сообщества по вашим интересам.",
                        actionLabel = if (selectedCategory != null) "Все категории" else "Обновить",
                        onAction = {
                            if (selectedCategory != null) {
                                selectedCategory = null
                                filteredSpaces = null
                                controller.reload(state, onState)
                            } else refreshCatalog()
                        },
                    )
                }
                items(visibleSpaces, key = PubChatApi.SpaceSummary::uid) { space ->
                    Card(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Открыть пространство ${space.name}") { onOpen(space) },
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
                    ) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.Top) {
                            Box(
                                Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(
                                    androidx.compose.ui.graphics.Brush.linearGradient(listOf(
                                        MaterialTheme.colorScheme.primary,
                                        androidx.compose.ui.graphics.Color(0xFF06C7EC),
                                    ))
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(painterResource(R.drawable.ic_spaces), contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(28.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(space.name, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                Text(if (space.isActiveMember) "Вы участник" else "Не участник",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium)
                                space.purpose?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                if (space.categorySlugs.isNotEmpty()) {
                                    Text(space.categorySlugs.joinToString(" · ") { slug ->
                                        categories.find { it.slug == slug }?.name ?: slug
                                    }, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 2)
                                }
                                space.description?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text(if (space.isActiveMember) "Открыть разговор →" else "Перейти к пространству →",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { refreshCatalog() },
                        modifier = Modifier.fillMaxWidth(), enabled = !visibleLoading) {
                        Text(if (visibleLoading) "Обновляем…" else "Обновить каталог")
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleContent(
    people: List<PubChatApi.PersonSummary>,
    loading: Boolean,
    error: String?,
    query: String,
    onQuery: (String) -> Unit,
    intent: String,
    availableNow: Boolean,
    onIntentChange: (String) -> Unit,
    onAvailableChange: (Boolean) -> Unit,
    availability: PubChatApi.SocialAvailability?,
    availabilityBusy: Boolean,
    availabilityError: String?,
    onSaveAvailability: (String, Int, String) -> Unit,
    onClearAvailability: () -> Unit,
    onSearch: () -> Unit,
    onFriendships: () -> Unit,
    onOpen: (PubChatApi.PersonSummary) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedButton(onClick = onFriendships, modifier = Modifier.fillMaxWidth()) { Text("Друзья и заявки") }
        SocialAvailabilityControls(
            intent = intent,
            availableNow = availableNow,
            onIntentChange = onIntentChange,
            onAvailableChange = onAvailableChange,
            availability = availability,
            busy = availabilityBusy,
            error = availabilityError,
            onSave = onSaveAvailability,
            onClear = onClearAvailability,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, Modifier.weight(1f), label = { Text("Имя или @handle") }, singleLine = true)
            TextButton(onClick = onSearch) { Text("Найти") }
        }
        if (loading && people.isNotEmpty()) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { message ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (people.isEmpty()) "Каталог людей недоступен" else "Не удалось обновить список",
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (people.isNotEmpty()) Text("Показаны предыдущие результаты поиска.",
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onSearch, enabled = !loading) { Text("Повторить") }
                }
            }
        }
        if (loading && people.isEmpty()) PubChatListSkeleton()
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (people.isEmpty() && error == null && !loading) item {
                PubChatEmptyState(title = "Никого не найдено",
                    explanation = "Попробуйте изменить фильтры или имя в поиске.",
                    actionLabel = "Обновить", onAction = onSearch)
            }
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
                        person.liveIntent?.let {
                            Text("● Сейчас: ${intentLabel(it)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        } ?: person.socialIntent?.let { Text(intentLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        person.liveNote?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessengerListContent(dialogs: List<PubChatApi.DialogSummary>, loading: Boolean, error: String?, online: Map<String, Boolean>, onOpen: (PubChatApi.DialogSummary) -> Unit, onReload: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (loading && dialogs.isNotEmpty()) {
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        error?.let { message ->
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (dialogs.isEmpty()) "Не удалось загрузить чаты" else "Не удалось обновить чаты",
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (dialogs.isNotEmpty()) Text("Сохранён текущий список диалогов.",
                        color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onReload, enabled = !loading) { Text("Повторить") }
                }
            }
        }
        if (loading && dialogs.isEmpty()) PubChatListSkeleton()
        else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dialogs.isEmpty() && error == null && !loading) item {
                PubChatEmptyState(
                    title = "Здесь пока нет сообщений",
                    explanation = "Найдите интересного человека, чтобы начать общение.",
                )
            }
            items(dialogs, key = PubChatApi.DialogSummary::partnerUid) { dialog ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpen(dialog) },
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp, pressedElevation = 1.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
                ) {
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
private fun PersonContent(
    person: PubChatApi.PersonSummary?,
    relationship: PubChatApi.SocialRelationship?,
    loading: Boolean,
    busy: Boolean,
    error: String?,
    onReload: () -> Unit,
    onFollow: () -> Unit,
    onFriendAction: (String) -> Unit,
    onBlock: () -> Unit,
    onMessage: (PubChatApi.PersonSummary) -> Unit,
) {
    if (person == null) return
    var confirmBlock by remember(person.accountUid) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ru.pubchat.app.ui.components.UserAvatar(person.displayName, person.avatar, 80.dp)
        Text(person.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        person.handle?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        person.bio?.let { Text(it) }
        val location = listOfNotNull(person.city, person.country).joinToString(", ")
        if (location.isNotBlank()) Text(location)
        person.socialIntent?.let { Text(intentLabel(it), color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        Text("Социальные связи", style = MaterialTheme.typography.titleMedium)
        if (loading) CircularProgressIndicator()
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onReload, enabled = !busy) { Text("Повторить") }
        }
        if (relationship != null && !loading) {
            if (relationship.blockedByMe) {
                Text("Вы заблокировали этот аккаунт. Общение ограничено.")
                OutlinedButton(onClick = onBlock, enabled = !busy) { Text("Разблокировать") }
            } else if (relationship.blockedMe) {
                Text("Социальные действия недоступны из-за настроек доступа.")
            } else {
                if (relationship.friends) Text("✓ Вы друзья", color = MaterialTheme.colorScheme.primary)
                if (relationship.followedBy) Text("Подписан(а) на вас")
                if (relationship.friendRequest == "incoming") Text("Вам предложили дружбу")
                if (relationship.friendRequest == "outgoing") Text("Запрос на дружбу отправлен")
                OutlinedButton(onClick = onFollow, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (relationship.following) "Отписаться" else "Подписаться")
                }
                OutlinedButton(
                    onClick = { onFriendAction(relationship.friendAction) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(relationship.friendActionTitle) }
                if (relationship.friendRequest == "incoming") {
                    OutlinedButton(
                        onClick = { onFriendAction("reject") },
                        enabled = !busy,
                    ) { Text("Отклонить запрос") }
                }
                TextButton(onClick = { confirmBlock = true }, enabled = !busy) {
                    Text("Заблокировать", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { onMessage(person) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) { Text("Написать сообщение") }
            }
        }
        if (busy) CircularProgressIndicator()
    }
    if (confirmBlock) AlertDialog(
        onDismissRequest = { confirmBlock = false },
        title = { Text("Заблокировать аккаунт?") },
        text = { Text("Общение, подписки и заявки в друзья станут недоступны.") },
        confirmButton = {
            TextButton(onClick = { confirmBlock = false; onBlock() }) {
                Text("Заблокировать", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmBlock = false }) { Text("Отмена") }
        },
    )
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
        if (connection != "connected") {
            Text(connectionLabel(connection),
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        if (reply != null) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Ответ · ${reply.senderName ?: "Участник"}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                    Text(reply.content.ifBlank { reply.attachmentName ?: "Вложение" },
                        maxLines = 1, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onCancelReply, enabled = !sending,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("Отмена") }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = { picker.launch("*/*") },
                enabled = connection == "connected" && reply == null && !sending,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Прикрепить файл" }) {
                Text("＋", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
            }
            Box(Modifier.weight(1f)) {
                Composer(draft, onDraft, connection == "connected" && !sending, onSend, padded = false)
            }
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
    Row(Modifier.fillMaxWidth().then(if (padded) Modifier.padding(horizontal = 8.dp, vertical = 8.dp) else Modifier),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .heightIn(min = 48.dp, max = 136.dp).padding(horizontal = 14.dp, vertical = 13.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            decorationBox = { field ->
                Box {
                    if (draft.isEmpty()) Text(if (enabled) "Сообщение…" else "Соединение…",
                        fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    field()
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (enabled && draft.isNotBlank()) onSend() }),
            maxLines = 5,
            enabled = enabled,
        )
        IconButton(onClick = onSend, enabled = enabled && draft.isNotBlank(), modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
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
    birthdayEnabled: Boolean,
    birthdayBusy: Boolean,
    birthdayError: String?,
    onBirthdayToggle: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Дни рождения друзей", style = MaterialTheme.typography.titleSmall)
                Text("Внутренние уведомления. Только друзья с разрешённым напоминанием; год рождения скрыт.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = birthdayEnabled, onCheckedChange = onBirthdayToggle, enabled = !birthdayBusy)
        }
        birthdayError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
    "reconnecting" -> "Восстанавливаем связь…"
    "restricted" -> "Доступ ограничен"
    else -> "Не подключено"
}

private fun intentLabel(value: String) = when (value) {
    "meet" -> "Знакомства"
    "games" -> "Игры"
    "friends" -> "Друзья"
    "quiet" -> "Спокойное общение"
    else -> "Открыт к общению"
}
