package ru.pubchat.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import ru.pubchat.app.core.network.PubChatApi
import ru.pubchat.app.core.session.SessionStore
import java.util.concurrent.Executors

sealed interface AppState {
    data object Booting : AppState
    data class SignedOut(val error: String? = null) : AppState
    data class SignedIn(
        val displayName: String,
        val handle: String,
        val spaces: List<PubChatApi.SpaceSummary>,
        val loading: Boolean = false,
        val error: String? = null,
    ) : AppState
}

class AppController(context: Context) {
    private val session = SessionStore(context.applicationContext)
    private val api = PubChatApi(
        deviceUid = { session.deviceUid },
        accessToken = { session.accessToken() },
        refreshCookie = { session.refreshCookie() },
    )
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun bootstrap(onState: (AppState) -> Unit) {
        if (session.accessToken().isNullOrBlank()) {
            onState(AppState.SignedOut())
            return
        }
        executor.execute {
            runCatching { loadSpacesWithRefresh() }
                .onSuccess { spaces -> main.post { onState(AppState.SignedIn("PubChat", "", spaces)) } }
                .onFailure {
                    session.clearSession()
                    main.post { onState(AppState.SignedOut()) }
                }
        }
    }

    fun login(identifier: String, password: String, onState: (AppState) -> Unit) {
        if (identifier.isBlank() || password.isBlank()) {
            onState(AppState.SignedOut("Введите логин и пароль"))
            return
        }
        executor.execute {
            runCatching {
                val login = api.login(identifier, password)
                session.saveSession(login.accessToken, login.refreshCookie)
                login to api.spaces()
            }.onSuccess { (login, spaces) ->
                main.post {
                    onState(
                        AppState.SignedIn(
                            displayName = login.displayName,
                            handle = login.handle,
                            spaces = spaces,
                        )
                    )
                }
            }.onFailure { error ->
                session.clearSession()
                main.post { onState(AppState.SignedOut(humanMessage(error))) }
            }
        }
    }

    fun reload(current: AppState.SignedIn, onState: (AppState) -> Unit) {
        onState(current.copy(loading = true, error = null))
        executor.execute {
            runCatching { loadSpacesWithRefresh() }
                .onSuccess { spaces -> main.post { onState(current.copy(spaces = spaces, loading = false)) } }
                .onFailure { error ->
                    main.post { onState(current.copy(loading = false, error = humanMessage(error))) }
                }
        }
    }

    fun logout(onState: (AppState) -> Unit) {
        executor.execute {
            runCatching { api.logout() }
            session.clearSession()
            main.post { onState(AppState.SignedOut()) }
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun loadSpacesWithRefresh(): List<PubChatApi.SpaceSummary> {
        return try {
            api.spaces()
        } catch (error: PubChatApi.ApiFailure) {
            if (error.code != 401 || session.refreshCookie().isNullOrBlank()) throw error
            val refreshed = api.refresh()
            session.saveSession(
                refreshed.accessToken,
                refreshed.refreshCookie ?: session.refreshCookie(),
            )
            api.spaces()
        }
    }

    private fun humanMessage(error: Throwable): String = when (error) {
        is PubChatApi.ApiFailure -> when (error.code) {
            401 -> "Неверные данные входа или сессия истекла"
            403 -> "Доступ ограничен"
            else -> "Сервер PubChat ответил с ошибкой ${error.code}"
        }
        else -> "Не удалось связаться с PubChat"
    }
}
