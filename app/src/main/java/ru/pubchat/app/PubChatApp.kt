package ru.pubchat.app

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import ru.pubchat.app.core.network.PubChatApi

enum class AppSection { SPACES, PEOPLE, PROFILE, PERSON, SPACE }

@Composable
fun PubChatApp(controller: AppController) {
    var state by remember { mutableStateOf<AppState>(AppState.Booting) }

    LaunchedEffect(controller) { controller.bootstrap { state = it } }
    DisposableEffect(controller) { onDispose { controller.close() } }

    when (val current = state) {
        AppState.Booting -> LoadingScreen()
        is AppState.SignedOut -> AuthScreen(
            error = current.error,
            onLogin = { identifier, password -> controller.login(identifier, password) { state = it } },
            onRegister = { handle, name, email, password, intent ->
                controller.register(handle, name, email, password, intent) { state = it }
            },
        )
        is AppState.SignedIn -> AuthenticatedShell(
            state = current,
            controller = controller,
            onState = { state = it },
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun AuthScreen(
    error: String?,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String, String, String) -> Unit,
) {
    var registration by remember { mutableStateOf(false) }
    var identifier by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var intent by remember { mutableStateOf("open") }
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(error) { if (!error.isNullOrBlank()) submitting = false }

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
                    if (registration) OutlinedButton(onClick = { registration = false }) { Text("Вход") }
                    else Button(onClick = {}) { Text("Вход") }
                    if (registration) Button(onClick = {}) { Text("Создать образ") }
                    else OutlinedButton(onClick = { registration = true; submitting = false }) { Text("Создать образ") }
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
                        if (registration) onRegister(handle, name, email, password, intent) else onLogin(identifier, password)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitting && password.isNotBlank() && if (registration) handle.isNotBlank() else identifier.isNotBlank(),
                ) { Text(if (submitting) "Подключаем…" else if (registration) "Создать образ" else "Войти") }
            }
        }
    }
}

@Composable
private fun AuthenticatedShell(
    state: AppState.SignedIn,
    controller: AppController,
    onState: (AppState) -> Unit,
) {
    var section by remember { mutableStateOf(AppSection.SPACES) }
    var people by remember { mutableStateOf<List<PubChatApi.PersonSummary>>(emptyList()) }
    var peopleLoading by remember { mutableStateOf(false) }
    var peopleError by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var person by remember { mutableStateOf<PubChatApi.PersonSummary?>(null) }
    var activeSpace by remember { mutableStateOf<PubChatApi.SpaceSummary?>(null) }
    var messages by remember { mutableStateOf<List<PubChatApi.ChatMessage>>(emptyList()) }
    var connection by remember { mutableStateOf("idle") }
    var chatError by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { controller.closeSpace() } }

    fun loadPeople() {
        peopleLoading = true
        controller.loadPeople(search) { result, error ->
            people = result
            peopleError = error
            peopleLoading = false
        }
    }

    fun openSpace(space: PubChatApi.SpaceSummary) {
        section = AppSection.SPACE
        activeSpace = space
        messages = emptyList()
        chatError = null
        connection = "connecting"
        controller.openSpace(
            space.uid,
            onReady = { activeSpace = it },
            onInitialMessages = { messages = it },
            onMessage = { incoming ->
                val index = messages.indexOfFirst { it.uid == incoming.uid }
                messages = if (index >= 0) messages.toMutableList().also { it[index] = incoming } else messages + incoming
            },
            onConnection = { connection = it },
            onError = { chatError = it },
        )
    }

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (section) {
                            AppSection.SPACES -> "Пространства"
                            AppSection.PEOPLE -> "Люди"
                            AppSection.PROFILE -> "Мой образ"
                            AppSection.PERSON -> person?.displayName ?: "Образ"
                            AppSection.SPACE -> activeSpace?.name ?: "Пространство"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (section == AppSection.SPACE) Text(connectionLabel(connection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text("@${state.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (section == AppSection.SPACE || section == AppSection.PERSON) {
                    TextButton(onClick = {
                        if (section == AppSection.SPACE) controller.closeSpace()
                        section = if (section == AppSection.SPACE) AppSection.SPACES else AppSection.PEOPLE
                    }) { Text("Назад") }
                } else TextButton(onClick = { controller.logout(onState) }) { Text("Выйти") }
            }
        },
        bottomBar = {
            if (section != AppSection.SPACE && section != AppSection.PERSON) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NavButton("Пространства", section == AppSection.SPACES) { section = AppSection.SPACES }
                    NavButton("Люди", section == AppSection.PEOPLE) { section = AppSection.PEOPLE; loadPeople() }
                    NavButton("Профиль", section == AppSection.PROFILE) { section = AppSection.PROFILE }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (section) {
                AppSection.SPACES -> SpacesContent(state, controller, onState, ::openSpace)
                AppSection.PEOPLE -> PeopleContent(people, peopleLoading, peopleError, search, { search = it }, ::loadPeople) { selected ->
                    controller.loadProfile(selected.accountUid) { loaded, error ->
                        if (loaded != null) {
                            person = loaded
                            section = AppSection.PERSON
                        } else peopleError = error
                    }
                }
                AppSection.PROFILE -> ProfileContent(state)
                AppSection.PERSON -> PersonContent(person)
                AppSection.SPACE -> SpaceChatContent(activeSpace, messages, connection, chatError, draft, { draft = it }) {
                    if (controller.sendSpaceMessage(draft, state.accountUid, state.displayName)) draft = ""
                    else chatError = "Соединение ещё не готово"
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
private fun SpacesContent(
    state: AppState.SignedIn,
    controller: AppController,
    onState: (AppState) -> Unit,
    onOpen: (PubChatApi.SpaceSummary) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        if (!state.error.isNullOrBlank()) Text(state.error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (state.loading) Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.spaces, key = PubChatApi.SpaceSummary::uid) { space ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(space) }) {
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
private fun PeopleContent(
    people: List<PubChatApi.PersonSummary>,
    loading: Boolean,
    error: String?,
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onOpen: (PubChatApi.PersonSummary) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onQuery, Modifier.weight(1f), label = { Text("Имя или @handle") }, singleLine = true)
            TextButton(onClick = onSearch) { Text("Найти") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            items(people, key = PubChatApi.PersonSummary::accountUid) { person ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(person) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(person.displayName, fontWeight = FontWeight.SemiBold)
                        person.handle?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        person.bio?.let { Text(it, Modifier.padding(top = 4.dp)) }
                        person.socialIntent?.let { Text(intentLabel(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileContent(state: AppState.SignedIn) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("@${state.handle}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.bio?.let { Text(it) }
        state.socialIntent?.let { Text("Намерение: ${intentLabel(it)}", color = MaterialTheme.colorScheme.primary) }
        Text("Устройство Android зарегистрировано отдельной сессией PubChat.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PersonContent(person: PubChatApi.PersonSummary?) {
    if (person == null) return
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(person.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        person.handle?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        person.bio?.let { Text(it) }
        val location = listOfNotNull(person.city, person.country).joinToString(", ")
        if (location.isNotBlank()) Text(location)
        person.socialIntent?.let { Text(intentLabel(it), color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun SpaceChatContent(
    space: PubChatApi.SpaceSummary?,
    messages: List<PubChatApi.ChatMessage>,
    connection: String,
    error: String?,
    draft: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        space?.description?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) item { Text(if (connection == "connected") "Здесь пока тихо. Начните разговор." else "Подключаем разговор…") }
            items(messages, key = PubChatApi.ChatMessage::uid) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(message.senderName ?: "Участник PubChat", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (message.contentType == "deleted") "Сообщение удалено" else message.content)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, onDraft, Modifier.weight(1f), placeholder = { Text("Сообщение") }, maxLines = 4, enabled = connection == "connected")
            Spacer(Modifier.padding(4.dp))
            Button(onClick = onSend, enabled = connection == "connected" && draft.isNotBlank()) { Text("Отпр.") }
        }
    }
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