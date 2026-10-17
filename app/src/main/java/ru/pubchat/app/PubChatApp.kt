package ru.pubchat.app

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

@Composable
fun PubChatApp(controller: AppController) {
    var state by remember { mutableStateOf<AppState>(AppState.Booting) }

    LaunchedEffect(controller) {
        controller.bootstrap { state = it }
    }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    when (val current = state) {
        AppState.Booting -> LoadingScreen()
        is AppState.SignedOut -> LoginScreen(
            error = current.error,
            onLogin = { identifier, password -> controller.login(identifier, password) { state = it } },
        )
        is AppState.SignedIn -> SpacesScreen(
            state = current,
            onReload = { controller.reload(current) { state = it } },
            onLogout = { controller.logout { state = it } },
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun LoginScreen(
    error: String?,
    onLogin: (String, String) -> Unit,
) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("PubChat", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("Живые пространства и разговоры", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Логин, email или телефон") },
                singleLine = true,
                enabled = !submitting,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Пароль") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !submitting,
            )
            if (!error.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    submitting = true
                    onLogin(identifier, password)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = identifier.isNotBlank() && password.isNotBlank() && !submitting,
            ) {
                Text(if (submitting) "Входим…" else "Войти")
            }
            TextButton(onClick = {}, enabled = false, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Регистрация появится в следующем мобильном срезе")
            }
        }
    }
}

@Composable
private fun SpacesScreen(
    state: AppState.SignedIn,
    onReload: () -> Unit,
    onLogout: () -> Unit,
) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Пространства", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.displayName.ifBlank { state.handle.ifBlank { "PubChat" } },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onLogout) { Text("Выйти") }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.error.isNullOrBlank()) {
                Text(
                    state.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.loading) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            if (state.spaces.isEmpty() && !state.loading) {
                EmptySpaces(onReload)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.spaces, key = PubChatApi.SpaceSummary::uid) { space ->
                        SpaceCard(space)
                    }
                    item {
                        OutlinedButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
                            Text("Обновить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpaceCard(space: PubChatApi.SpaceSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(space.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            space.purpose?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            space.description?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptySpaces(onReload: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Пространств пока нет", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onReload) { Text("Проверить снова") }
        }
    }
}
