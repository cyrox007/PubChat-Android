package ru.pubchat.app.core.network

import org.json.JSONArray
import org.json.JSONObject
import ru.pubchat.app.BuildConfig
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

class PubChatApi(
    private val deviceUid: () -> String,
    private val accessToken: () -> String?,
    private val refreshCookie: () -> String?,
) {
    data class LoginResult(
        val accessToken: String,
        val refreshCookie: String?,
        val displayName: String,
        val handle: String,
    )

    data class SpaceSummary(
        val uid: String,
        val name: String,
        val description: String?,
        val purpose: String?,
    )

    data class ApiFailure(val code: Int, val message: String) : Exception(message)

    fun login(identifier: String, password: String): LoginResult {
        val body = JSONObject()
            .put("identifier", identifier.trim())
            .put("password", password)
        val response = request("POST", "/identity/v2/login", body = body)
        val json = JSONObject(response.body)
        val user = json.optJSONObject("user")
        val persona = json.optJSONObject("persona")
        return LoginResult(
            accessToken = json.getString("access_token"),
            refreshCookie = response.refreshCookie,
            displayName = user?.optString("display_name")?.takeIf { it.isNotBlank() }
                ?: persona?.optString("display_name")?.takeIf { it.isNotBlank() }
                ?: identifier.trim(),
            handle = user?.optString("username")?.takeIf { it.isNotBlank() }
                ?: persona?.optString("handle")?.takeIf { it.isNotBlank() }
                ?: identifier.trim(),
        )
    }

    fun spaces(): List<SpaceSummary> {
        val response = request("GET", "/spaces/v1?limit=50")
        val items = JSONObject(response.body).optJSONArray("spaces") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val space = items.optJSONObject(index) ?: continue
                val uid = space.optString("uid")
                val name = space.optString("name")
                if (uid.isBlank() || name.isBlank()) continue
                add(
                    SpaceSummary(
                        uid = uid,
                        name = name,
                        description = space.optString("description").takeIf { it.isNotBlank() && it != "null" },
                        purpose = space.optString("purpose").takeIf { it.isNotBlank() && it != "null" },
                    )
                )
            }
        }
    }

    fun refresh(): String {
        val response = request("POST", "/identity/v2/refresh", includeAccessToken = false)
        return JSONObject(response.body).getString("access_token")
    }

    fun logout() {
        request("POST", "/identity/v2/logout")
    }

    private data class HttpResult(val body: String, val refreshCookie: String?)

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        includeAccessToken: Boolean = true,
    ): HttpResult {
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-PubChat-Device-ID", deviceUid())
            setRequestProperty("User-Agent", "PubChat-Android/${BuildConfig.VERSION_NAME}")
            if (includeAccessToken) {
                accessToken()?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }
            refreshCookie()?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Cookie", it)
            }
            if (body != null) {
                doOutput = true
                outputStream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(body.toString()) }
            }
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    val root = JSONObject(text)
                    val raw = root.opt("detail")
                    when (raw) {
                        is JSONObject -> raw.optString("error_type").ifBlank { raw.toString() }
                        else -> raw?.toString().orEmpty()
                    }
                }.getOrDefault("")
                throw ApiFailure(code, detail.ifBlank { "HTTP $code" })
            }
            HttpResult(text, extractRefreshCookie(connection.headerFields["Set-Cookie"]))
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRefreshCookie(headers: List<String>?): String? = headers
        ?.asSequence()
        ?.mapNotNull { header ->
            header.split(';').firstOrNull()?.trim()?.takeIf { it.startsWith("refresh_token=") }
        }
        ?.firstOrNull()
}
