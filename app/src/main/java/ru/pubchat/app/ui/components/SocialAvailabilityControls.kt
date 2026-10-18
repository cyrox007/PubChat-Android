package ru.pubchat.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.pubchat.app.core.network.PubChatApi

@Composable
fun SocialAvailabilityControls(
    intent: String,
    availableNow: Boolean,
    onIntentChange: (String) -> Unit,
    onAvailableChange: (Boolean) -> Unit,
    availability: PubChatApi.SocialAvailability?,
    busy: Boolean,
    error: String?,
    onSave: (String, Int, String) -> Unit,
    onClear: () -> Unit,
) {
    var editor by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf("open") }
    var duration by remember { mutableStateOf(120) }
    var note by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Намерение общения", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for ((code, label) in listOf(
                "all" to "Все", "open" to "Общение", "meet" to "Знакомства",
                "friends" to "Друзья", "games" to "Игры", "quiet" to "Спокойно"
            )) {
                if (intent == code) Button(onClick = { onIntentChange(code) }) { Text(label) }
                else OutlinedButton(onClick = { onIntentChange(code) }) { Text(label) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Открыты прямо сейчас")
            Switch(checked = availableNow, onCheckedChange = onAvailableChange)
        }
        if (availability == null) {
            TextButton(onClick = {
                chosen = "open"
                duration = 120
                note = ""
                editor = true
            }) { Text("Включить временный статус") }
        } else {
            Text("● Сейчас: ${availability.intent}", color = MaterialTheme.colorScheme.primary)
            availability.note?.let { Text(it) }
            Text("Истекает: ${availability.expiresAt}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    chosen = availability.intent
                    note = availability.note ?: ""
                    editor = true
                }) { Text("Изменить") }
                TextButton(onClick = onClear, enabled = !busy) { Text("Завершить") }
            }
        }
        if (busy) Text("Сохраняем статус…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }

    if (editor) AlertDialog(
        onDismissRequest = { if (!busy) editor = false },
        title = { Text("Открыт сейчас") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Чем хотите заняться?")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for ((code, label) in listOf("open" to "Общение", "meet" to "Знакомства", "games" to "Игры")) {
                        TextButton(onClick = { chosen = code }) {
                            Text(if (chosen == code) "✓ $label" else label)
                        }
                    }
                }
                Text("На какое время?")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for ((minutes, label) in listOf(30 to "30 мин", 120 to "2 часа", 480 to "8 часов")) {
                        TextButton(onClick = { duration = minutes }) {
                            Text(if (duration == minutes) "✓ $label" else label)
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = { Text("О чём хочется поговорить (необязательно)") },
                    singleLine = false,
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(chosen, duration, note)
                editor = false
            }, enabled = !busy) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = { editor = false }, enabled = !busy) { Text("Отмена") }
        },
    )
}
