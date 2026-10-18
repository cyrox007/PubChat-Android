package ru.pubchat.app.core.network

import org.json.JSONArray
import org.json.JSONObject
import ru.pubchat.app.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

class PubChatApi(
    private val deviceUid: () -> String,
    private val accessToken: () -> String?,
    private val refreshCookie: () -> String?,
) {
    data class Identity(
        val accountUid: String,
        val accessToken: String?,
        val refreshCookie: String?,
        val displayName: String,
        val handle: String,
        val bio: String?,
        val avatar: String?,
        val socialIntent: String?,
        val city: String? = null,
        val country: String? = null,
    )

    data class RefreshResult(val accessToken: String, val refreshCookie: String?)

    data class SpaceSummary(
        val uid: String,
        val name: String,
        val description: String?,
        val purpose: String?,
        val joinPolicy: String?,
        val membershipStatus: String?,
        val membershipRole: String?,
        val ownerUid: String?,
        val canManage: Boolean = false,
        val memberCount: Int = 0,
        val memberLimit: Int = 250,
    ) {
        val isActiveMember: Boolean
            get() = membershipStatus == "active" || membershipRole == "owner"
    }

    data class PersonSummary(
        val accountUid: String,
        val displayName: String,
        val handle: String?,
        val bio: String?,
        val city: String?,
        val country: String?,
        val avatar: String?,
        val socialIntent: String?,
    )

    data class ChatMessage(
        val uid: String,
        val content: String,
        val senderUid: String?,
        val senderName: String?,
        val createdAt: String?,
        val contentType: String,
        val receiverUid: String? = null,
        val isRead: Boolean = false,
        val reactions: String? = null,
        val replyToUid: String? = null,
        val attachmentName: String? = null,
        val senderAvatar: String? = null,
        val replyContent: String? = null,
        val replySenderName: String? = null,
        val attachments: List<Attachment> = emptyList(),
    )

    data class Attachment(val url: String, val name: String, val mimeType: String?)

    data class DialogSummary(
        val partnerUid: String,
        val displayName: String,
        val handle: String?,
        val avatar: String?,
        val lastMessage: String?,
        val lastMessageTime: String?,
        val unreadCount: Int,
    )

    data class Notice(val uid: String, val title: String, val body: String, val read: Boolean, val targetUid: String?, val surface: String?)

    data class RealtimeTicket(
        val ticket: String,
        val websocketPath: String,
        val heartbeatSeconds: Int,
    )

    data class ApiFailure(val code: Int, override val message: String) : Exception(message)

    fun login(identifier: String, password: String): Identity {
        val body = JSONObject().put("identifier", identifier.trim()).put("password", password)
        val response = request("POST", "/identity/v2/login", body = body, includeAccessToken = false, includeRefreshCookie = false)
        return parseIdentity(JSONObject(response.body), response.refreshCookie)
    }

    fun register(
        handle: String,
        displayName: String,
        email: String?,
        password: String,
        socialIntent: String,
    ): Identity {
        val body = JSONObject()
            .put("handle", handle.trim())
            .put("display_name", displayName.trim().ifBlank { handle.trim() })
            .put("password", password)
            .put("social_intent", socialIntent)
        email?.trim()?.takeIf { it.isNotBlank() }?.let { body.put("email", it) }
        val response = request("POST", "/identity/v2/register", body = body, includeAccessToken = false, includeRefreshCookie = false)
        return parseIdentity(JSONObject(response.body), response.refreshCookie)
    }

    fun me(): Identity {
        val response = request("GET", "/identity/v2/me")
        return parseIdentity(JSONObject(response.body), refreshCookie())
    }

    fun updateProfile(name: String, bio: String, city: String, country: String, intent: String): Identity {
        request("PATCH", "/identity/v2/persona", JSONObject().put("display_name", name.trim()).put("bio", bio.trim())
            .put("city", city.trim()).put("country", country.trim()).put("social_intent", intent))
        return me()
    }

    fun spaces(): List<SpaceSummary> = parseSpaces(
        JSONObject(request("GET", "/spaces/v1?limit=50").body).optJSONArray("spaces") ?: JSONArray()
    )

    fun space(uid: String): SpaceSummary {
        val json = JSONObject(request("GET", "/spaces/v1/$uid").body).getJSONObject("space")
        return parseSpace(json)
    }

    fun joinSpace(uid: String): SpaceSummary {
        val json = JSONObject(request("POST", "/spaces/v1/$uid/join").body).getJSONObject("space")
        return parseSpace(json)
    }

    fun leaveSpace(uid: String) { request("DELETE", "/spaces/v1/$uid/membership") }

    fun spacePanelItems(uid: String, section: String): List<JSONObject> {
        val (path, key) = when (section) {
            "people" -> "/spaces/v1/$uid/members?status=active&limit=100" to "members"
            "requests" -> "/spaces/v1/$uid/members?status=pending&limit=100" to "members"
            "rules" -> "/spaces/v1/$uid/rules" to "rules"
            "events" -> "/spaces/v1/$uid/events?limit=100" to "events"
            "reports" -> "/moderation/v1/spaces/$uid/reports?limit=100" to "reports"
            else -> return emptyList()
        }
        val array = JSONObject(request("GET", path).body).optJSONArray(key) ?: JSONArray()
        return buildList { for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add) }
    }

    fun updateSpace(uid: String, name: String, description: String) {
        request("PATCH", "/spaces/v1/$uid", JSONObject().put("name", name.trim()).put("description", description.trim()))
    }

    fun manageSpaceMember(uid: String, memberUid: String, action: String) {
        if (action in setOf("member", "moderator")) request("PATCH", "/spaces/v1/$uid/members/$memberUid", JSONObject().put("role", action))
        else request("PATCH", "/spaces/v1/$uid/members/$memberUid/membership", JSONObject().put("action", action))
    }

    fun addSpaceRule(uid: String, title: String, body: String) {
        request("POST", "/spaces/v1/$uid/rules", JSONObject().put("title", title.trim()).put("body", body.trim()).put("position", 0))
    }

    fun deleteSpaceRule(uid: String, ruleUid: String) { request("DELETE", "/spaces/v1/$uid/rules/$ruleUid") }

    fun addSpaceEvent(uid: String, title: String, description: String, startsAt: String) {
        request("POST", "/spaces/v1/$uid/events", JSONObject().put("title", title.trim()).put("description", description.trim()).put("starts_at", startsAt))
    }

    fun cancelSpaceEvent(uid: String, eventUid: String) {
        request("PATCH", "/spaces/v1/$uid/events/$eventUid", JSONObject().put("status", "cancelled"))
    }

    fun updateSpaceReport(uid: String, reportUid: String, status: String) {
        request("PATCH", "/moderation/v1/spaces/$uid/reports/$reportUid", JSONObject().put("status", status))
    }

    fun moderateSpaceMember(uid: String, memberUid: String, action: String, reason: String) {
        val body = JSONObject().put("target_account_uid", memberUid).put("action_type", action).put("reason", reason.trim())
        if (action == "restrict") body.put("duration_minutes", 1440)
        request("POST", "/moderation/v1/spaces/$uid/actions", body)
    }

    fun replySpaceMessage(messageUid: String, content: String): ChatMessage {
        val response = JSONObject(request("POST", "/messages/v2/room/$messageUid/reply",
            JSONObject().put("content", content.trim())).body)
        val saved = response.optJSONObject("message")
            ?: error("Сервер не подтвердил ответ в комнате")
        return parseChatMessage(saved)
            ?: error("Не удалось прочитать ответ сервера")
    }

    fun people(query: String = ""): List<PersonSummary> {
        val encoded = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val suffix = if (encoded.isBlank()) "" else "&q=$encoded"
        val root = JSONObject(request("GET", "/social/v1/discover?limit=50$suffix").body)
        val items = root.optJSONArray("people") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val accountUid = item.optString("account_uid")
                if (accountUid.isBlank()) continue
                add(parsePerson(item, accountUid))
            }
        }
    }

    fun publicProfile(accountUid: String): PersonSummary {
        val item = JSONObject(request("GET", "/identity/v2/profiles/$accountUid").body).getJSONObject("profile")
        return parsePerson(item, accountUid)
    }

    fun dialogs(): List<DialogSummary> {
        val root = JSONObject(request("GET", "/messenger/dialogs").body)
        val items = root.optJSONArray("dialogs") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val partnerUid = item.optString("partner_id")
                if (partnerUid.isBlank()) continue
                val partner = item.optJSONObject("partner")
                val username = partner?.optNullableString("username")
                add(
                    DialogSummary(
                        partnerUid = partnerUid,
                        displayName = partner?.optNullableString("display_name") ?: username ?: "Участник PubChat",
                        handle = username,
                        avatar = partner?.optNullableString("avatar"),
                        lastMessage = item.optNullableString("last_message"),
                        lastMessageTime = item.optNullableString("last_message_time"),
                        unreadCount = item.optInt("unread_count", 0),
                    )
                )
            }
        }
    }

    fun realtimeTicket(spaceUid: String): RealtimeTicket = realtimeTicket("room", spaceUid)

    fun messengerRealtimeTicket(): RealtimeTicket = realtimeTicket("messenger", null)

    fun callIceConfig(): JSONObject = JSONObject(request("GET", "/calls/v1/ice-config").body)

    fun pendingIncomingCall(): JSONObject? = JSONObject(request("GET", "/calls/v1/pending-offer").body).optJSONObject("offer")

    fun editPrivateMessage(messageUid: String, content: String) {
        request("PATCH", "/messages/v2/messenger/$messageUid", JSONObject().put("content", content.trim()))
    }

    fun deletePrivateMessage(messageUid: String) {
        request("DELETE", "/messages/v2/messenger/$messageUid")
    }

    fun replyPrivateMessage(messageUid: String, content: String): ChatMessage {
        val response = JSONObject(request(
            "POST", "/messages/v2/messenger/$messageUid/reply",
            JSONObject().put("content", content.trim()),
        ).body)
        val created = response.optJSONObject("message")
            ?: error("Сервер не вернул созданное сообщение")
        return parseChatMessage(created)
            ?: error("Не удалось прочитать созданное сообщение")
    }

    fun reactPrivateMessage(messageUid: String, emoji: String) {
        request("POST", "/messages/v2/messenger/$messageUid/reactions", JSONObject().put("emoji", emoji))
    }

    fun forwardPrivateMessage(messageUid: String, targetPartnerUid: String) {
        request(
            "POST",
            "/messages/v2/messenger/$messageUid/forward",
            JSONObject().put("target_surface", "messenger").put("target_uid", targetPartnerUid),
        )
    }


    fun registerNativePush(installationUid: String, token: String) {
        val body = JSONObject()
            .put("installation_uid", installationUid)
            .put("platform", "android")
            .put("provider", "fcm")
            .put("token", token)
            .put("device_label", "Android")
            .put("app_version", BuildConfig.VERSION_NAME)
        request("POST", "/notifications/v1/native-push/devices", body)
    }

    fun enableNativePushMessages() {
        request("PATCH", "/notifications/v1/message-preferences",
            JSONObject().put("native_push_messenger", true))
    }

    fun removeNativePush(installationUid: String) {
        request("POST", "/notifications/v1/native-push/devices/remove",
            JSONObject().put("installation_uid", installationUid))
    }

    fun notifications(): List<Notice> {
        val root = JSONObject(request("GET", "/notifications/v1?limit=50").body)
        val items = root.optJSONArray("notifications") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val entry = items.optJSONObject(index) ?: continue
                val uid = entry.optString("uid")
                if (uid.isBlank()) continue
                val payload = entry.optJSONObject("payload")
                add(Notice(
                    uid = uid,
                    title = entry.optString("title").ifBlank { "Уведомление PubChat" },
                    body = entry.optString("body").ifBlank { entry.optString("message") },
                    read = entry.optBoolean("is_read", entry.optBoolean("read", false)),
                    targetUid = payload?.optNullableString("space_uid") ?: payload?.optNullableString("peer_uid") ?: entry.optNullableString("target_uid"),
                    surface = payload?.optNullableString("surface") ?: entry.optNullableString("surface"),
                ))
            }
        }
    }

    fun markNotificationRead(uid: String) {
        request("PATCH", "/notifications/v1/$uid/read")
    }

    fun markAllNotificationsRead() {
        request("POST", "/notifications/v1/read-all")
    }

    fun refresh(): RefreshResult {
        val response = request("POST", "/identity/v2/refresh", includeAccessToken = false)
        return RefreshResult(JSONObject(response.body).getString("access_token"), response.refreshCookie)
    }

    fun logout() {
        request("POST", "/identity/v2/logout")
    }

    private fun realtimeTicket(target: String, spaceUid: String?): RealtimeTicket {
        val body = JSONObject().put("target", target)
        spaceUid?.let { body.put("room_uid", it) }
        val json = JSONObject(request("POST", "/realtime/v2/tickets", body = body).body)
        return RealtimeTicket(
            ticket = json.getString("ticket"),
            websocketPath = json.getString("websocket_path"),
            heartbeatSeconds = json.optInt("heartbeat_seconds", 25),
        )
    }

    private fun parseIdentity(root: JSONObject, cookie: String?): Identity {
        val account = root.optJSONObject("account")
        val user = root.optJSONObject("user")
        val persona = root.optJSONObject("persona")
        return Identity(
            accountUid = account?.optString("uid")?.takeIf { it.isNotBlank() }
                ?: user?.optString("uid")?.takeIf { it.isNotBlank() }.orEmpty(),
            accessToken = root.optNullableString("access_token"),
            refreshCookie = cookie,
            displayName = user?.optString("display_name")?.takeIf { it.isNotBlank() }
                ?: persona?.optString("display_name")?.takeIf { it.isNotBlank() }
                ?: "PubChat",
            handle = user?.optString("username")?.takeIf { it.isNotBlank() }
                ?: persona?.optString("handle")?.takeIf { it.isNotBlank() }
                ?: "",
            bio = persona?.optNullableString("bio") ?: user?.optNullableString("bio"),
            avatar = persona?.optNullableString("avatar") ?: user?.optNullableString("avatar"),
            socialIntent = persona?.optNullableString("social_intent") ?: user?.optNullableString("social_intent"),
            city = persona?.optNullableString("city"),
            country = persona?.optNullableString("country"),
        )
    }

    private fun parsePerson(item: JSONObject, fallbackUid: String): PersonSummary = PersonSummary(
        accountUid = item.optString("account_uid").ifBlank { item.optString("uid").ifBlank { fallbackUid } },
        displayName = item.optString("display_name").ifBlank { item.optString("handle").ifBlank { "Участник PubChat" } },
        handle = item.optNullableString("handle"),
        bio = item.optNullableString("bio"),
        city = item.optNullableString("city"),
        country = item.optNullableString("country"),
        avatar = item.optNullableString("avatar"),
        socialIntent = item.optNullableString("social_intent"),
    )

    private fun parseSpaces(items: JSONArray): List<SpaceSummary> = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("uid").isBlank()) continue
            add(parseSpace(item))
        }
    }

    private fun parseSpace(space: JSONObject): SpaceSummary {
        val membership = space.optJSONObject("viewer_membership")
        return SpaceSummary(
            uid = space.optString("uid"),
            name = space.optString("name").ifBlank { "Пространство" },
            description = space.optNullableString("description"),
            purpose = space.optNullableString("purpose"),
            joinPolicy = space.optNullableString("join_policy"),
            membershipStatus = membership?.optNullableString("status"),
            membershipRole = membership?.optNullableString("role"),
            ownerUid = space.optNullableString("owner_uid"),
            canManage = space.optBoolean("can_manage", false),
            memberCount = space.optInt("member_count", 0),
            memberLimit = space.optInt("member_limit", 250),
        )
    }

    private data class HttpResult(val body: String, val refreshCookie: String?)

    private fun csrfProof(): String {
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        val connection = (URL("$base/csrf/get").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            setRequestProperty("X-PubChat-Device-ID", deviceUid())
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) throw ApiFailure(code, "Не удалось получить защитный токен")
            val token = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                .getString("csrf_token")
            val cookies = connection.headerFields.entries
                .firstOrNull { (key, _) -> key?.equals("Set-Cookie", ignoreCase = true) == true }
                ?.value.orEmpty()
            require(cookies.any { it.substringBefore(';').trim() == "XSRF-TOKEN=$token" }) {
                "Сервер не установил CSRF cookie"
            }
            token
        } finally {
            connection.disconnect()
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null, includeAccessToken: Boolean = true, includeRefreshCookie: Boolean = true): HttpResult {
        val base = BuildConfig.API_BASE_URL.trimEnd('/')
        val csrf = if (method.uppercase() !in setOf("GET", "HEAD", "OPTIONS")) csrfProof() else null
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-PubChat-Device-ID", deviceUid())
            setRequestProperty("User-Agent", "PubChat-Android/${BuildConfig.VERSION_NAME}")
            if (includeAccessToken) accessToken()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Authorization", "Bearer $it") }
            val cookies = buildList {
                if (includeRefreshCookie) refreshCookie()?.takeIf { it.isNotBlank() }?.let { add(it) }
                csrf?.let { add("XSRF-TOKEN=$it") }
            }
            if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies.joinToString("; "))
            csrf?.let { setRequestProperty("X-CSRF-Token", it) }
            if (body != null) {
                doOutput = true
                outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching {
                    val raw = JSONObject(text).opt("detail")
                    when (raw) {
                        is JSONObject -> raw.optString("error_type").ifBlank { raw.toString() }
                        else -> raw?.toString().orEmpty()
                    }
                }.getOrDefault("")
                throw ApiFailure(code, detail.ifBlank { "HTTP $code" })
            }
            val cookies = connection.headerFields.entries.firstOrNull { (name, _) -> name?.equals("Set-Cookie", true) == true }?.value
            HttpResult(text, extractRefreshCookie(cookies))
        } finally {
            connection.disconnect()
        }
    }

    private fun extractRefreshCookie(headers: List<String>?): String? = headers
        ?.asSequence()
        ?.mapNotNull { it.split(';').firstOrNull()?.trim()?.takeIf { cookie -> cookie.startsWith("refresh_token=") } }
        ?.firstOrNull()

    private fun JSONObject.optNullableString(key: String): String? = optString(key)
        .takeIf { it.isNotBlank() && it != "null" }
}
