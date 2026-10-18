package ru.pubchat.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.pubchat.app.core.network.PubChatApi

@Composable
fun FriendshipHubContent(
    tab: String,
    rows: List<PubChatApi.FriendshipEntry>,
    total: Int,
    loading: Boolean,
    loadingMore: Boolean,
    error: String?,
    busyUid: String?,
    onTab: (String) -> Unit,
    onReload: () -> Unit,
    onMore: () -> Unit,
    onOpen: (PubChatApi.FriendshipEntry) -> Unit,
    onAction: (String, String) -> Unit,
) {
    var pendingRemove by remember { mutableStateOf<PubChatApi.FriendshipEntry?>(null) }

    pendingRemove?.let { person ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Удалить из друзей?") },
            text = { Text("Можно будет снова предложить дружбу позднее.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    onAction(person.accountUid, "remove")
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Отмена") } },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("incoming" to "Входящие", "outgoing" to "Исходящие", "friends" to "Друзья").forEach { (key, label) ->
                if (tab == key) {
                    Button(onClick = { onTab(key) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(label, maxLines = 1)
                    }
                } else {
                    OutlinedButton(onClick = { onTab(key) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
        error?.let {
            Text(it, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onReload) { Text("Повторить") }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (rows.isEmpty() && error == null) item {
                    Text(
                        when (tab) {
                            "incoming" -> "Новых заявок в друзья пока нет."
                            "outgoing" -> "Вы пока не отправляли заявок."
                            else -> "Друзья появятся после взаимного согласия."
                        },
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(rows, key = PubChatApi.FriendshipEntry::accountUid) { person ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { onOpen(person) }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                UserAvatar(person.displayName, person.avatar)
                                Column(Modifier.weight(1f)) {
                                    Text(person.displayName, fontWeight = FontWeight.SemiBold)
                                    person.handle?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                Text("›", color = MaterialTheme.colorScheme.primary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (tab) {
                                    "incoming" -> {
                                        Button(onClick = { onAction(person.accountUid, "accept") }, enabled = busyUid == null) {
                                            Text("Принять")
                                        }
                                        OutlinedButton(onClick = { onAction(person.accountUid, "reject") }, enabled = busyUid == null) {
                                            Text("Отклонить")
                                        }
                                    }
                                    "outgoing" -> OutlinedButton(onClick = { onAction(person.accountUid, "remove") }, enabled = busyUid == null) {
                                        Text("Отменить запрос")
                                    }
                                    else -> OutlinedButton(onClick = { pendingRemove = person }, enabled = busyUid == null) {
                                        Text("Удалить из друзей")
                                    }
                                }
                                if (busyUid == person.accountUid) CircularProgressIndicator()
                            }
                        }
                    }
                }
                if (rows.size < total) item {
                    TextButton(onClick = onMore, enabled = !loadingMore && busyUid == null) {
                        Text(if (loadingMore) "Загружаем…" else "Показать ещё")
                    }
                }
            }
        }
    }
}
