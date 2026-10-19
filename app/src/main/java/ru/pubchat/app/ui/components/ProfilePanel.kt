package ru.pubchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import ru.pubchat.app.AppController
import ru.pubchat.app.AppState
import ru.pubchat.app.ui.theme.LocalThemeControl

@Composable
internal fun ProfilePanel(state: AppState.SignedIn, controller: AppController, onState: (AppState) -> Unit) {
    val theme = LocalThemeControl.current
    var editing by remember { mutableStateOf(false) }
    var managingPersonas by remember { mutableStateOf(false) }
    var managingDMConsent by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Text(state.displayName.take(1).uppercase(), fontSize = 28.sp, color = MaterialTheme.colorScheme.primary)
                        publicMediaUrl(state.avatar)?.let { AsyncImage(it, "Ваш аватар", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(state.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("@${state.handle}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        Text(intentTitle(state.socialIntent), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                }
                Button(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("Редактировать профиль") }
                OutlinedButton(onClick = { managingPersonas = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Мои Persona")
                }
            }
        }
        Text("О себе", style = MaterialTheme.typography.titleSmall)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.bio?.takeIf { it.isNotBlank() } ?: "Добавьте несколько слов о себе", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                val location = listOfNotNull(state.city?.takeIf { it.isNotBlank() }, state.country?.takeIf { it.isNotBlank() }).joinToString(", ")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(if (location.isBlank()) "Местоположение не указано" else location, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Оформление", style = MaterialTheme.typography.titleSmall)
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Тёмная тема", fontSize = 14.sp)
                    Text(if (theme.dark) "Включена" else "Выключена", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = theme.dark, onCheckedChange = { theme.toggle() })
            }
        }
    }
    if (editing) ProfileEditor(state, controller, onSaved = { onState(it); editing = false }, onDismiss = { editing = false })
    if (managingPersonas) PersonaManagerDialog(
        state = state, controller = controller,
        onSaved = onState, onDismiss = { managingPersonas = false },
    )
}

@Composable
private fun PersonaManagerDialog(
    state: AppState.SignedIn,
    controller: AppController,
    onSaved: (AppState.SignedIn) -> Unit,
    onDismiss: () -> Unit,
) {
    var personas by remember { mutableStateOf<List<ru.pubchat.app.core.network.PubChatApi.PersonaSummary>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var handle by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    fun reload() {
        loading = true
        error = null
        controller.loadPersonas { result, failure ->
            if (result != null) {
                personas = result.personas
                selected = result.activePersonaUid
            } else error = failure ?: "Не удалось загрузить образы"
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Мои Persona", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Образы переключаются отдельно на каждом устройстве. " +
                        "Блокировки, переписки и репутация остаются общими для Account.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                personas.forEach { persona ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(persona.displayName, fontWeight = FontWeight.SemiBold)
                            Text("@${persona.handle}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (persona.isPrimary) "Основной образ" else "Дополнительный — приватный",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (persona.uid == selected) Text("Активен")
                        else OutlinedButton(
                            onClick = {
                                busy = true
                                error = null
                                controller.activatePersona(state, persona.uid) { updated, failure ->
                                    busy = false
                                    if (updated != null) {
                                        selected = persona.uid
                                        onSaved(updated)
                                    } else error = failure ?: "Не удалось переключить образ"
                                }
                            },
                            enabled = !loading && !busy,
                        ) { Text("Выбрать") }
                    }
                    HorizontalDivider()
                }
                if (!loading && personas.size < 3) {
                    Text("Создать приватный образ", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        handle, { if (it.length <= 32) handle = it },
                        label = { Text("Handle") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        displayName, { if (it.length <= 80) displayName = it },
                        label = { Text("Отображаемое имя") }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            busy = true
                            error = null
                            controller.createPersona(handle.trim(), displayName.trim()) { failure ->
                                busy = false
                                if (failure != null) error = failure
                                else { handle = ""; displayName = ""; reload() }
                            }
                        },
                        enabled = !busy && handle.length in 3..32 &&
                            handle.matches(Regex("^[A-Za-z0-9_.]+$")) && displayName.isNotBlank(),
                    ) { Text("Создать") }
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") }
            }
        }
    }
}

private fun intentTitle(value: String?) = when(value) {
    "meet" -> "Знакомства"; "games" -> "Игры"; "friends" -> "Друзья"; "quiet" -> "Спокойное общение"; else -> "Открыт к общению"
}

@Composable
private fun ProfileEditor(state: AppState.SignedIn, controller: AppController, onSaved: (AppState.SignedIn) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(state.displayName) }
    var bio by remember { mutableStateOf(state.bio.orEmpty()) }
    var city by remember { mutableStateOf(state.city.orEmpty()) }
    var country by remember { mutableStateOf(state.country.orEmpty()) }
    var intent by remember { mutableStateOf(state.socialIntent ?: "open") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Редактировать профиль", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { if(it.length <= 80) name = it }, label = { Text("Имя") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(bio, { if(it.length <= 500) bio = it }, label = { Text("О себе") }, enabled = !saving, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(city, { if(it.length <= 100) city = it }, label = { Text("Город") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(country, { if(it.length <= 100) country = it }, label = { Text("Страна") }, enabled = !saving, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Интересует", style = MaterialTheme.typography.labelLarge)
                listOf("open", "friends", "meet", "games", "quiet").chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { option ->
                        FilterChip(selected = intent == option, onClick = { intent = option }, enabled = !saving, label = { Text(intentTitle(option), fontSize = 11.sp) })
                    } }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Отмена") }
                    Button(onClick = {
                        saving = true; error = null
                        controller.saveProfile(state, name, bio, city, country, intent) { updated, failure ->
                            saving = false; error = failure
                            if(updated != null) onSaved(updated)
                        }
                    }, enabled = !saving && name.isNotBlank()) { Text(if(saving) "Сохранение…" else "Сохранить") }
                }
            }
        }
    }
}