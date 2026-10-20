package ru.pubchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import ru.pubchat.app.AppController
import ru.pubchat.app.core.crypto.CryptoDeviceSnapshot

/** Explicit preparatory step, not E2EE send readiness or peer device verification. */
@Composable
internal fun CryptoDeviceSetupDialog(
    accountUid: String,
    controller: AppController,
    onDismiss: () -> Unit,
) {
    var remote by remember(accountUid) { mutableStateOf<CryptoDeviceSnapshot?>(null) }
    var local by remember(accountUid) { mutableStateOf(false) }
    var loading by remember(accountUid) { mutableStateOf(true) }
    var busy by remember(accountUid) { mutableStateOf(false) }
    var failure by remember(accountUid) { mutableStateOf<String?>(null) }
    var message by remember(accountUid) { mutableStateOf<String?>(null) }
    var generation by remember(accountUid) { mutableStateOf(0) }

    fun reload() {
        val attempt = ++generation
        loading = true
        failure = null
        controller.localCryptoDeviceStatus(accountUid) { result, exists, error ->
            if (attempt == generation) {
                remote = result
                local = exists
                failure = error
                loading = false
            }
        }
    }

    LaunchedEffect(accountUid) { reload() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Ключи устройства") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Этот этап создаёт криптографический идентификатор устройства. " +
                        "Защищённые сообщения пока нельзя отправлять или расшифровывать в Android. " +
                        "Регистрация ключей не включает E2EE автоматически.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (loading || busy) CircularProgressIndicator()
                val registered = remote?.revoked == false
                Text(
                    when {
                        loading -> "Проверяем устройство на сервере…"
                        failure != null -> "Статус сервера неизвестен — действия приостановлены"
                        registered -> "Устройство зарегистрировано, но проверка собеседника и E2EE ещё недоступны"
                        remote?.revoked == true -> "Криптографическое устройство отозвано сервером"
                        local -> "Локальные ключи созданы. Требуется повторная регистрация"
                        else -> "На этом устройстве ключи ещё не созданы"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (registered) {
                    Text("Отпечаток ключей устройства (не safety number):",
                        style = MaterialTheme.typography.labelMedium)
                    Text(remote!!.fingerprint, style = MaterialTheme.typography.bodySmall)
                    Text("Доступно одноразовых prekey: ${remote?.availablePrekeys ?: "неизвестно"}",
                        style = MaterialTheme.typography.bodySmall)
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!loading && !busy && failure == null && !registered && remote?.revoked != true) {
                    Button(
                        onClick = {
                            busy = true
                            message = null
                            controller.prepareCryptoDevice(accountUid) { result, error ->
                                busy = false
                                if (result != null) {
                                    remote = result
                                    local = true
                                    message = "Публичные ключи зарегистрированы и сверены с сервером."
                                } else failure = error ?: "Не удалось зарегистрировать ключи"
                            }
                        }, modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (local) "Повторить регистрацию" else "Создать и зарегистрировать ключи") }
                }
                if (!loading && !busy && registered) {
                    OutlinedButton(
                        onClick = {
                            busy = true
                            failure = null
                            message = null
                            controller.revokeCryptoDevice(accountUid) { done, error ->
                                busy = false
                                if (done) {
                                    remote = null
                                    local = false
                                    message = "Устройство отозвано сервером; локальные ключи удалены."
                                } else failure = error ?: "Отзыв устройства не подтверждён"
                            }
                        }, modifier = Modifier.fillMaxWidth(),
                    ) { Text("Отозвать криптографическое устройство") }
                }
                OutlinedButton(onClick = { reload() }, enabled = !busy && !loading) {
                    Text("Обновить состояние")
                }
                Text(
                    "Нельзя восстанавливать ключи из резервной копии: приватный материал защищён Android Keystore " +
                        "и исключён из переноса. После потери ключей понадобится отдельная процедура отзыва.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Закрыть") }
        },
    )
}
