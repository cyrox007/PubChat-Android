package ru.pubchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.pubchat.app.AppController
import ru.pubchat.app.core.network.PubChatApi
import ru.pubchat.app.core.network.SpaceInvitation

/** Account-scoped, API-backed inbox; does not claim membership before server confirmation. */
@Composable
fun SpaceInvitationsDialog(
    controller: AppController,
    onDismiss: () -> Unit,
    onAccepted: (PubChatApi.SpaceSummary) -> Unit,
) {
    var rows by remember { mutableStateOf<List<SpaceInvitation>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var busyUid by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableStateOf(0) }

    fun load(append: Boolean = false) {
        if (loading || loadingMore || busyUid != null) return
        val current = ++generation
        val offset = if (append) rows.size else 0
        if (append) loadingMore = true else loading = true
        if (!append) error = null
        controller.loadSpaceInvitations(offset) { page, failure ->
            if (current == generation) {
                loading = false
                loadingMore = false
                if (page != null) {
                    rows = if (append) {
                        val known = rows.map { it.uid }.toSet()
                        rows + page.rows.filterNot { it.uid in known }
                    } else page.rows
                    total = page.total
                    error = null
                } else error = failure ?: "Не удалось получить приглашения"
            }
        }
    }

    fun respond(invitation: SpaceInvitation, action: String) {
        if (busyUid != null || loading || loadingMore) return
        busyUid = invitation.uid
        error = null
        notice = null
        // Prevent any earlier inbox page from overwriting a server-confirmed action.
        generation++
        controller.respondSpaceInvitation(invitation.uid, invitation.spaceUid, action) { result, failure ->
            busyUid = null
            if (result != null) {
                if (result.status == "accepted" && result.space != null) {
                    onAccepted(result.space)
                } else {
                    // The list may have shifted; start again at offset=0 after decline.
                    rows = rows.filterNot { it.uid == result.uid }
                    notice = "Приглашение отклонено"
                    load()
                }
            } else error = failure ?: "Не удалось обработать приглашение"
        }
    }

    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = { if (busyUid == null) onDismiss() },
        title = { Text("Приглашения в пространства") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Принимайте приглашение только после проверки сервером доступа и свободных мест.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                error?.let { failure ->
                    Text(failure, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { load() }, enabled = !loading && !loadingMore && busyUid == null) {
                        Text("Повторить")
                    }
                }
                if (loading && rows.isEmpty()) {
                    CircularProgressIndicator(Modifier.padding(12.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 490.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (rows.isEmpty() && !loading && error == null) item {
                            Text("Новых приглашений нет. Здесь появятся доступные предложения вступить в сообщество.")
                        }
                        items(rows, key = SpaceInvitation::uid) { invitation ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(invitation.spaceName, fontWeight = FontWeight.SemiBold)
                                    Text("${invitation.inviterName} приглашает вас",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    invitation.spaceDescription?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    Text(
                                        "Истекает: ${invitation.expiresAt.replace('T', ' ').take(16)} (время сервера)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { respond(invitation, "accept") },
                                            enabled = busyUid == null && !loading && !loadingMore,
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                        ) { Text("Принять") }
                                        OutlinedButton(
                                            onClick = { respond(invitation, "decline") },
                                            enabled = busyUid == null && !loading && !loadingMore,
                                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                        ) { Text("Отклонить") }
                                    }
                                    if (busyUid == invitation.uid) CircularProgressIndicator()
                                }
                            }
                        }
                        if (rows.size < total && !loading) item {
                            TextButton(
                                onClick = { load(append = true) },
                                enabled = !loadingMore && busyUid == null && error == null,
                            ) { Text(if (loadingMore) "Загружаем…" else "Показать ещё") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = busyUid == null) { Text("Закрыть") }
        },
    )
}
