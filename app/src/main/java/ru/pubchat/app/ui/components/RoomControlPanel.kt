package ru.pubchat.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject
import ru.pubchat.app.AppController
import ru.pubchat.app.core.network.PubChatApi

private fun JSONObject.label(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

@Composable
internal fun RoomControlPanel(
    space: PubChatApi.SpaceSummary, currentUid: String, controller: AppController,
    onClose: () -> Unit, onChanged: (PubChatApi.SpaceSummary) -> Unit, onLeft: () -> Unit,
) {
    val owner = space.membershipRole == "owner" || space.ownerUid == currentUid
    val manager = space.canManage || owner || (space.membershipStatus == "active" && space.membershipRole == "moderator")
    var tab by remember(space.uid) { mutableStateOf("overview") }
    var data by remember(space.uid) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var name by remember(space.name) { mutableStateOf(space.name) }
    var description by remember(space.description) { mutableStateOf(space.description.orEmpty()) }
    var ruleTitle by remember { mutableStateOf("") }
    var ruleBody by remember { mutableStateOf("") }
    var eventTitle by remember { mutableStateOf("") }
    var eventDescription by remember { mutableStateOf("") }
    var eventDate by remember { mutableStateOf("") }
    var moderationTarget by remember { mutableStateOf<String?>(null) }
    var moderationAction by remember { mutableStateOf("warning") }
    var reason by remember { mutableStateOf("") }
    var confirmText by remember { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun reload() {
        if (tab == "overview") return
        val expected = tab
        loading = true; error = null
        controller.loadSpacePanel(space.uid, expected) { items, failure ->
            if (tab == expected) { data = items; error = failure; loading = false }
        }
    }
    fun action(kind: String, target: String = "", value: String = "", detail: String = "", done: () -> Unit = {}) {
        busy = true; error = null; notice = null
        controller.spaceAction(space.uid, kind, target, value, detail) { failure ->
            busy = false
            if (failure != null) error = failure
            else {
                notice = "Изменения сохранены"
                controller.refreshSpace(space.uid) { updated, _ -> if (updated != null) onChanged(updated) }
                done(); reload()
            }
        }
    }
    fun confirm(text: String, block: () -> Unit) { confirmText = text; confirmAction = block }
    LaunchedEffect(tab, space.uid) { data = emptyList(); reload() }

    if (confirmAction != null) AlertDialog(onDismissRequest = { confirmAction = null },
        title = { Text("Подтвердите действие") }, text = { Text(confirmText) },
        confirmButton = { TextButton(onClick = { val block = confirmAction; confirmAction = null; block?.invoke() }) { Text("Подтвердить") } },
        dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Отмена") } })
    if (moderationTarget != null) AlertDialog(onDismissRequest = { moderationTarget = null },
        title = { Text(if (moderationAction == "warning") "Предупреждение" else "Ограничение на 24 часа") },
        text = { OutlinedTextField(reason, { reason = it.take(2000) }, label = { Text("Причина решения") }, minLines = 3) },
        confirmButton = { TextButton(enabled = reason.trim().length >= 3 && !busy, onClick = {
            val target = moderationTarget ?: return@TextButton
            action(moderationAction, target, detail = reason) { moderationTarget = null; reason = "" }
        }) { Text("Применить") } }, dismissButton = { TextButton(onClick = { moderationTarget = null }) { Text("Отмена") } })

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("О пространстве", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(space.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onClose) { Text("К разговору") }
                }
                val tabs = listOf("overview" to "Обзор", "people" to "Люди") +
                    (if (manager) listOf("requests" to "Заявки") else emptyList()) +
                    listOf("rules" to "Правила", "events" to "События") +
                    (if (manager) listOf("reports" to "Модерация") else emptyList())
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    tabs.forEach { (key, title) ->
                        if (key == tab) FilledTonalButton(onClick = { tab = key }, modifier = Modifier.padding(3.dp)) { Text(title) }
                        else TextButton(onClick = { tab = key }) { Text(title) }
                    }
                }
                error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.primary) }
                if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (tab == "overview") {
                        item {
                            Text(space.description ?: "Описание пока не добавлено")
                            Text("Участники: ${space.memberCount} / ${space.memberLimit}", Modifier.padding(vertical = 8.dp))
                            Text("Ваша роль: ${when { owner -> "Создатель"; manager -> "Модератор"; else -> "Участник" }}")
                        }
                        if (manager) item {
                            Text("Управление пространством", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Название") })
                            OutlinedTextField(description, { description = it.take(500) }, Modifier.fillMaxWidth(), label = { Text("Описание") }, minLines = 3)
                            Button(enabled = !busy && name.trim().length >= 3, onClick = { action("save", value = name, detail = description) }) { Text("Сохранить") }
                        }
                        item {
                            if (!owner) OutlinedButton(enabled = !busy, onClick = {
                                confirm("Покинуть ${space.name}? Чтобы вернуться, нужно будет вступить снова.") { action("leave", done = onLeft) }
                            }) { Text("Покинуть пространство", color = MaterialTheme.colorScheme.error) }
                            else Text("Вы создатель пространства. Возврат к списку не удаляет ваше участие.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        if (tab == "rules" && manager) item {
                            OutlinedTextField(ruleTitle, { ruleTitle = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Заголовок правила") })
                            OutlinedTextField(ruleBody, { ruleBody = it.take(4000) }, Modifier.fillMaxWidth(), label = { Text("Текст правила") }, minLines = 2)
                            Button(enabled = !busy && ruleTitle.isNotBlank() && ruleBody.isNotBlank(), onClick = {
                                action("rule", value = ruleTitle, detail = ruleBody) { ruleTitle = ""; ruleBody = "" }
                            }) { Text("Добавить правило") }
                        }
                        if (tab == "events" && manager) item {
                            OutlinedTextField(eventTitle, { eventTitle = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Название события") })
                            OutlinedTextField(eventDate, { eventDate = it }, Modifier.fillMaxWidth(), label = { Text("Начало: дд.мм.гггг чч:мм") }, supportingText = { Text("Часовой пояс: ${java.time.ZoneId.systemDefault()}") })
                            OutlinedTextField(eventDescription, { eventDescription = it.take(4000) }, Modifier.fillMaxWidth(), label = { Text("Описание события") })
                            Button(enabled = !busy && eventTitle.isNotBlank() && eventDate.isNotBlank(), onClick = {
                                val date = runCatching { java.time.LocalDateTime.parse(eventDate, java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT))
                                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toString() }.getOrNull()
                                if (date == null) error = "Проверьте дату: например, 25.10.2026 19:30"
                                else action("event", date, eventTitle, eventDescription) { eventTitle = ""; eventDate = ""; eventDescription = "" }
                            }) { Text("Создать событие") }
                        }
                        if (!loading && data.isEmpty()) item { Text(when(tab) { "requests" -> "Новых заявок нет"; "reports" -> "Жалоб нет"; "rules" -> "Правила ещё не добавлены"; "events" -> "Событий пока нет"; else -> "Участников пока нет" }) }
                        items(data, key = { it.label("uid") ?: it.label("account_uid") ?: it.toString() }) { item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    when (tab) {
                                        "people", "requests" -> {
                                            val uid = item.label("account_uid").orEmpty()
                                            val persona = item.optJSONObject("persona")
                                            val display = persona?.label("display_name") ?: persona?.label("handle") ?: "Участник"
                                            val role = item.label("role")
                                            Text(display, fontWeight = FontWeight.SemiBold)
                                            Text(when(role) { "owner" -> "Создатель"; "moderator" -> "Модератор"; else -> "Участник" }, style = MaterialTheme.typography.bodySmall)
                                            if (manager && tab == "requests") Row {
                                                TextButton(enabled = !busy, onClick = { action("member", uid, "approve") }) { Text("Принять") }
                                                TextButton(enabled = !busy, onClick = { confirm("Отклонить заявку $display?") { action("member", uid, "reject") } }) { Text("Отклонить") }
                                            }
                                            if (manager && uid.isNotBlank() && uid != currentUid && role != "owner" && (owner || role != "moderator")) {
                                                if (owner) TextButton(enabled = !busy, onClick = {
                                                    confirm("Изменить роль $display?") { action("member", uid, if (role == "moderator") "member" else "moderator") }
                                                }) { Text(if (role == "moderator") "Снять модератора" else "Назначить модератором") }
                                                TextButton(enabled = !busy, onClick = { confirm("Удалить $display из пространства?") { action("member", uid, "remove") } }) { Text("Удалить из пространства") }
                                                Row {
                                                    TextButton(onClick = { moderationTarget = uid; moderationAction = "warning"; reason = "" }) { Text("Предупредить") }
                                                    TextButton(onClick = { moderationTarget = uid; moderationAction = "restrict"; reason = "" }) { Text("Ограничить") }
                                                }
                                            }
                                        }
                                        "rules" -> {
                                            Text(item.label("title").orEmpty(), fontWeight = FontWeight.SemiBold)
                                            Text(item.label("body").orEmpty())
                                            if (manager) TextButton(enabled = !busy, onClick = { confirm("Удалить это правило?") { action("delete-rule", item.label("uid").orEmpty()) } }) { Text("Удалить правило") }
                                        }
                                        "events" -> {
                                            Text(item.label("title").orEmpty(), fontWeight = FontWeight.SemiBold)
                                            item.label("description")?.let { Text(it) }
                                            Text(item.label("starts_at").orEmpty(), style = MaterialTheme.typography.bodySmall)
                                            if (item.label("status") == "cancelled") Text("Отменено")
                                            else if (manager) TextButton(enabled = !busy, onClick = { confirm("Отменить событие?") { action("cancel-event", item.label("uid").orEmpty()) } }) { Text("Отменить событие") }
                                        }
                                        "reports" -> {
                                            Text(item.label("category") ?: "Жалоба", fontWeight = FontWeight.SemiBold)
                                            Text(item.label("description") ?: "Без описания")
                                            val status = item.label("status")
                                            Text(when(status) { "open" -> "Открыта"; "reviewing" -> "В работе"; "resolved" -> "Решена"; "dismissed" -> "Закрыта"; else -> status.orEmpty() })
                                            if (status == "open") TextButton(enabled = !busy, onClick = { action("report", item.label("uid").orEmpty(), "reviewing") }) { Text("Взять в работу") }
                                            if (status in setOf("open", "reviewing")) {
                                                TextButton(enabled = !busy, onClick = { confirm("Закрыть жалобу без действия?") { action("report", item.label("uid").orEmpty(), "dismissed") } }) { Text("Закрыть без действия") }
                                                item.optJSONObject("target")?.label("account_uid")?.let { target ->
                                                    TextButton(onClick = { moderationTarget = target; moderationAction = "warning"; reason = "" }) { Text("Предупредить участника") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item { TextButton(onClick = { reload() }, enabled = !loading) { Text("Обновить") } }
                    }
                }
            }
        }
    }
}
