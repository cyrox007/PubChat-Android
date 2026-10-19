package ru.pubchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ru.pubchat.app.core.network.PubChatApi
import ru.pubchat.app.core.space.SpaceCreationDraft

/**
 * Creation is a real server write (POST /spaces/v1), not a fake catalog card.
 * Mirrors the Web controls and keeps server-owned privacy/permission checks.
 */
@Composable
internal fun CreateSpaceDialog(
    categories: List<PubChatApi.SpaceCategory>,
    categoryError: String?,
    onRetryCategories: () -> Unit,
    onCreate: (SpaceCreationDraft, (PubChatApi.SpaceSummary?, String?) -> Unit) -> Unit,
    onCreated: (PubChatApi.SpaceSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var purpose by remember { mutableStateOf("community") }
    var visibility by remember { mutableStateOf("public") }
    var joinPolicy by remember { mutableStateOf("open") }
    var memberLimit by remember { mutableStateOf("250") }
    var country by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var selectedCategories by remember { mutableStateOf<List<String>>(emptyList()) }
    var advanced by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }

    val draft = SpaceCreationDraft(
        name = name,
        description = description,
        purpose = purpose,
        visibility = visibility,
        joinPolicy = joinPolicy,
        memberLimit = memberLimit.toIntOrNull() ?: 0,
        country = country,
        region = region,
        tagsInput = tags,
        categorySlugs = selectedCategories,
    )
    val validationError = draft.validationError(categories.map { it.slug }.toSet())

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 10.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("Создать пространство", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("Место для общения, совместных интересов и событий.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(name, { name = it.take(80) },
                    label = { Text("Название *") }, modifier = Modifier.fillMaxWidth(),
                    enabled = !busy, singleLine = true)
                OutlinedTextField(description, { description = it.take(500) },
                    label = { Text("О чём это место") }, modifier = Modifier.fillMaxWidth(),
                    enabled = !busy, minLines = 2, maxLines = 4)

                Text("Формат", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    listOf(
                        "community" to "Сообщество",
                        "conversation" to "Разговоры",
                        "meet_people" to "Знакомства",
                        "games" to "Игры",
                        "local" to "Местное",
                    ).forEach { (code, label) ->
                        FilterChip(
                            selected = purpose == code,
                            onClick = { purpose = code },
                            label = { Text(label) },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).padding(end = 6.dp),
                        )
                    }
                }

                Text("Кто видит пространство", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("public" to "Все", "unlisted" to "По ссылке",
                        "private" to "Закрытое").forEach { (code, label) ->
                        FilterChip(
                            selected = visibility == code,
                            onClick = {
                                visibility = code
                                if (code == "private") joinPolicy = "invite"
                            },
                            label = { Text(label) }, enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).padding(end = 6.dp),
                        )
                    }
                }
                Text("Порядок вступления", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("open" to "Свободно", "request" to "По заявке",
                        "invite" to "По приглашению").forEach { (code, label) ->
                        FilterChip(
                            selected = joinPolicy == code,
                            onClick = { joinPolicy = code },
                            label = { Text(label) },
                            enabled = !busy && (visibility != "private" || code == "invite"),
                            modifier = Modifier.heightIn(min = 48.dp).padding(end = 6.dp),
                        )
                    }
                }
                if (visibility == "private") {
                    Text("Закрытые пространства доступны только по приглашению.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text("Категории · до трёх", style = MaterialTheme.typography.labelLarge)
                if (categoryError != null) {
                    Text("Справочник недоступен: $categoryError",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetryCategories, enabled = !busy) {
                        Text("Загрузить категории заново")
                    }
                } else if (categories.isEmpty()) {
                    Text("Активных категорий пока нет. Можно создать без них.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.forEach { category ->
                            FilterChip(
                                selected = category.slug in selectedCategories,
                                onClick = {
                                    selectedCategories = if (category.slug in selectedCategories)
                                        selectedCategories - category.slug
                                    else if (selectedCategories.size < 3)
                                        selectedCategories + category.slug
                                    else selectedCategories
                                },
                                label = { Text(category.name) },
                                enabled = !busy && (category.slug in selectedCategories ||
                                    selectedCategories.size < 3),
                                modifier = Modifier.heightIn(min = 48.dp).padding(end = 6.dp),
                            )
                        }
                    }
                }

                TextButton(onClick = { advanced = !advanced }, enabled = !busy) {
                    Text(if (advanced) "Скрыть дополнительные настройки" else "Дополнительные настройки")
                }
                if (advanced) {
                    OutlinedTextField(
                        memberLimit, { memberLimit = it.filter(Char::isDigit).take(5) },
                        label = { Text("Лимит участников (2–5000)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(country, { country = it.take(100) },
                        label = { Text("Страна") }, enabled = !busy,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(region, { region = it.take(100) },
                        label = { Text("Регион / город") }, enabled = !busy,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(tags, { tags = it.take(400) },
                        label = { Text("Теги через запятую") }, enabled = !busy,
                        supportingText = { Text("До 8 тегов, каждый до 40 символов") },
                        modifier = Modifier.fillMaxWidth())
                }

                // Show specific invalid input only after the user starts filling
                // the form; the button stays disabled until server rules pass.
                if (name.isNotBlank() && validationError != null) {
                    Text(validationError, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                submitError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy && validationError == null,
                        onClick = {
                            if (busy || validationError != null) return@Button
                            busy = true
                            submitError = null
                            onCreate(draft) { created, error ->
                                busy = false
                                if (created != null) onCreated(created)
                                else submitError = error ?: "Не удалось создать пространство."
                            }
                        },
                    ) {
                        if (busy) CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(if (busy) "Создаём…" else "Создать")
                    }
                }
            }
        }
    }
}
