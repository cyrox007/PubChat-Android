package ru.pubchat.app.core.network

import org.json.JSONArray
import org.json.JSONObject
import ru.pubchat.app.BuildConfig
import ru.pubchat.app.core.crypto.CryptoDeviceMaterial
import ru.pubchat.app.core.crypto.CryptoDeviceSnapshot
import ru.pubchat.app.core.crypto.parseCryptoDeviceResponse
import ru.pubchat.app.core.crypto.parseOwnCryptoDeviceList
import ru.pubchat.app.core.crypto.parseCryptoRevocation
import ru.pubchat.app.core.space.SpaceCreationDraft
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

    enum class DMConsentPolicy(val code: String, val label: String, val description: String) {
        EVERYONE("everyone", "Все участники",
            "Написать могут участники платформы с учётом блокировок и серверных ограничений."),
        SHARED_SPACES("shared_spaces", "Из общих пространств",
            "Начать разговор могут участники общих пространств, когда сервер разрешает доступ."),
        MUTUAL("mutual", "Только взаимные контакты",
            "Право начать разговор зависит от взаимной связи, подтверждённой сервером."),
        NOBODY("nobody", "Никто", "Запретить новые обращения.");

        companion object {
            fun fromCode(code: String): DMConsentPolicy? = values().firstOrNull { it.code == code }
        }
    }

    data class DMConsentSnapshot(
        val accountUid: String,
        val personaUid: String,
        val primaryPersona: Boolean,
        val policy: DMConsentPolicy,
    )

    data class PersonaSummary(
        val uid: String, val handle: String,
        val displayName: String, val isPrimary: Boolean,
    )
    data class PersonaList(val personas: List<PersonaSummary>, val activePersonaUid: String?)

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
        val categorySlugs: List<String> = emptyList(),
    ) {
        val isActiveMember: Boolean
            get() = membershipStatus == "active" || membershipRole == "owner"
    }

    data class SpaceCategory(val slug: String, val name: String, val icon: String)

    data class PersonSummary(
        val accountUid: String,
        val displayName: String,
        val handle: String?,
        val bio: String?,
        val city: String?,
        val country: String?,
        val avatar: String?,
        val socialIntent: String?,
        val liveIntent: String? = null,
        val liveNote: String? = null,
    )

    data class FriendshipEntry(
        val accountUid: String,
        val displayName: String,
        val handle: String?,
        val avatar: String?,
    )
    data class FriendshipPage(val rows: List<FriendshipEntry>, val total: Int)

    data class SocialRelationship(
        val accountUid: String,
        val following: Boolean,
        val followedBy: Boolean,
        val friends: Boolean,
        val friendRequest: String?,
        val blockedByMe: Boolean,
        val blockedMe: Boolean,
    ) {
        val canInteract: Boolean get() = !blockedByMe && !blockedMe
        val friendAction: String get() = when {
            friends || friendRequest == "outgoing" -> "remove"
            friendRequest == "incoming" -> "accept"
            else -> "request"
        }
        val friendActionTitle: String get() = when {
            friends -> "Удалить из друзей"
            friendRequest == "incoming" -> "Принять запрос"
            friendRequest == "outgoing" -> "Отменить запрос"
            else -> "Предложить дружбу"
        }
    }

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

    fun myDMConsent(): DMConsentSnapshot =
        parseDMConsentResponse(request("GET", "/identity/v2/me").body)

    fun updateDMConsent(policy: DMConsentPolicy): DMConsentSnapshot {
        val payload = dmConsentPayload(policy)
        val result = parseDMConsentResponse(request("PATCH", "/identity/v2/privacy", payload).body)
        check(result.policy == policy) { "Сервер не подтвердил изменение приватности" }
        return result
    }

    fun updateProfile(name: String, bio: String, city: String, country: String, intent: String): Identity {
        request("PATCH", "/identity/v2/persona", JSONObject().put("display_name", name.trim()).put("bio", bio.trim())
            .put("city", city.trim()).put("country", country.trim()).put("social_intent", intent))
        return me()
    }

    fun spaces(category: String? = null): List<SpaceSummary> {
        require(category == null || Regex("^[a-z][a-z0-9-]{0,39}$").matches(category)) {
            "Недопустимая категория пространства"
        }
        val route = "/spaces/v1?limit=50" + (category?.let { "&category=$it" } ?: "")
        return parseSpaces(JSONObject(request("GET", route).body).optJSONArray("spaces") ?: JSONArray())
    }

    fun createSpace(draft: SpaceCreationDraft): SpaceSummary {
        draft.validationError()?.let { throw IllegalArgumentException(it) }
        val body = JSONObject()
            .put("name", draft.name.trim().replace(Regex("\\s+"), " "))
            .put("description", draft.description.trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("purpose", draft.purpose)
            .put("visibility", draft.visibility)
            .put("join_policy", draft.joinPolicy)
            .put("member_limit", draft.memberLimit)
            .put("country", draft.country.trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("region", draft.region.trim().takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("tags", JSONArray(draft.tags))
            .put("category_slugs", JSONArray(draft.categorySlugs))
        val response = JSONObject(request("POST", "/spaces/v1", body).body)
        return parseSpace(response.getJSONObject("space"))
    }

    /** The server returns only invitations addressed to this authenticated Account. */
    fun spaceInvitations(limit: Int = 25, offset: Int = 0): SpaceInvitationPage {
        require(limit in 1..100 && offset >= 0) { "Некорректная страница приглашений" }
        val body = request("GET", "/spaces/v1/invitations?limit=$limit&offset=$offset").body
        return parseSpaceInvitationPage(body, limit, offset)
    }

    fun respondSpaceInvitation(uid: String, expectedSpaceUid: String, action: String): SpaceInvitationDecision {
        require(java.util.UUID.fromString(uid).toString() == uid.lowercase()) { "Некорректное приглашение" }
        val body = request("PATCH", "/spaces/v1/invitations/$uid", invitationActionPayload(action)).body
        return parseSpaceInvitationDecision(body, uid, action, expectedSpaceUid, ::parseSpace)
    }

    fun spaceCategories(): List<SpaceCategory> {
        val items = JSONObject(request("GET", "/spaces/v1/categories").body)
            .optJSONArray("categories") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val slug = item.optString("slug")
                if (!Regex("^[a-z][a-z0-9-]{0,39}$").matches(slug)) continue
                add(SpaceCategory(slug, item.optString("name"), item.optString("icon", "tag")))
            }
        }
    }

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

    fun updateSpaceCategories(uid: String, categorySlugs: List<String>): SpaceSummary {
        validateSpaceCategorySlugs(categorySlugs)
        val body = JSONObject().put("category_slugs", JSONArray(categorySlugs))
        val response = JSONObject(request("PATCH", "/spaces/v1/$uid", body).body)
        return parseSpace(response.getJSONObject("space"))
    }

    internal fun validateSpaceCategorySlugs(slugs: List<String>) {
        require(slugs.size <= 3 && slugs.distinct().size == slugs.size) {
            "Для пространства можно выбрать не более трёх разных категорий"
        }
        require(slugs.all { Regex("^[a-z][a-z0-9-]{1,39}$").matches(it) }) {
            "Недопустимый идентификатор категории"
        }
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

    data class SocialAvailability(val intent: String, val note: String?, val expiresAt: String)

    fun mySocialAvailability(): SocialAvailability? =
        parseSocialAvailability(JSONObject(request("GET", "/social/v1/availability/me").body).optJSONObject("availability"))

    fun setSocialAvailability(intent: String, durationMinutes: Int, note: String): SocialAvailability? {
        require(intent in setOf("open", "meet", "games")) { "Недопустимое намерение" }
        require(durationMinutes in setOf(30, 120, 480)) { "Недопустимая длительность" }
        val payload = JSONObject().put("intent", intent).put("duration_minutes", durationMinutes)
            .put("note", note.take(120).trim())
        return parseSocialAvailability(JSONObject(request("PUT", "/social/v1/availability/me", payload).body).optJSONObject("availability"))
    }

    fun clearSocialAvailability() {
        request("DELETE", "/social/v1/availability/me")
    }

    fun people(query: String = "", intent: String? = null, availableNow: Boolean = false): List<PersonSummary> {

        val encoded = java.net.URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val suffix = buildString {
            if (encoded.isNotBlank()) append("&q=$encoded")
            if (intent in setOf("open", "meet", "games", "friends", "quiet")) append("&social_intent=$intent")
            if (availableNow) append("&available_now=true")
        }
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

    fun friendshipPage(tab: String, offset: Int = 0): FriendshipPage {
        require(tab in setOf("incoming", "outgoing", "friends")) { "Неизвестный список дружбы" }
        require(offset >= 0) { "Некорректное смещение" }
        val path = if (tab == "friends") "/social/v1/friends?limit=50&offset=$offset"
            else "/social/v1/friend-requests?direction=$tab&limit=50&offset=$offset"
        val key = if (tab == "friends") "friends" else "requests"
        return parseFriendshipPage(request("GET", path).body, key)
    }

    fun relationship(accountUid: String): SocialRelationship =
        parseRelationship(request("GET", "/social/v1/relationships/$accountUid").body)

    fun setFollowing(accountUid: String, currentlyFollowing: Boolean): SocialRelationship =
        parseRelationship(request(
            if (currentlyFollowing) "DELETE" else "POST",
            "/social/v1/follows/$accountUid"
        ).body)

    fun friendAction(accountUid: String, action: String): SocialRelationship =
        parseRelationship(request(
            "PATCH", "/social/v1/friends/$accountUid", JSONObject().put("action", action)
        ).body)

    fun setBlocked(accountUid: String, currentlyBlocked: Boolean): SocialRelationship =
        parseRelationship(request(
            if (currentlyBlocked) "DELETE" else "POST",
            "/social/v1/blocks/$accountUid"
        ).body)

    private fun parseRelationship(body: String): SocialRelationship {
        val relation = JSONObject(body).getJSONObject("relationship")
        return SocialRelationship(
            accountUid = relation.getString("account_uid"),
            following = relation.getBoolean("following"),
            followedBy = relation.getBoolean("followed_by"),
            friends = relation.getBoolean("friends"),
            friendRequest = relation.optNullableString("friend_request"),
            blockedByMe = relation.getBoolean("blocked_by_me"),
            blockedMe = relation.getBoolean("blocked_me"),
        )
    }

    fun personas(): PersonaList {
        val root = JSONObject(request("GET", "/identity/v2/personas").body)
        val items = root.optJSONArray("personas") ?: JSONArray()
        val result = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(PersonaSummary(
                    uid = item.getString("uid"),
                    handle = item.getString("handle"),
                    displayName = item.getString("display_name"),
                    isPrimary = item.optBoolean("is_primary", false),
                ))
            }
        }
        return PersonaList(result, root.optNullableString("active_persona_uid"))
    }

    fun createPersona(handle: String, displayName: String) {
        request("POST", "/identity/v2/personas",
            JSONObject().put("handle", handle).put("display_name", displayName))
    }

    fun activatePersona(uid: String): Identity =
        parseIdentity(JSONObject(request("POST", "/identity/v2/personas/$uid/activate").body), null)

    /** Separate from login device-id; Account-scoped public crypto identity only. */
    internal fun registerCryptoDevice(material: CryptoDeviceMaterial): CryptoDeviceSnapshot {
        val body = request("POST", "/crypto/v1/devices", material.registrationPayload()).body
        return parseCryptoDeviceResponse(body, material).also {
            check(!it.revoked) { "Сервер вернул отозванное криптографическое устройство" }
        }
    }

    internal fun ownCryptoDevice(material: CryptoDeviceMaterial): CryptoDeviceSnapshot? {
        val body = request("GET", "/crypto/v1/devices").body
        return parseOwnCryptoDeviceList(body, material)
    }

    /** Directory reads are untrusted until locally checked against a remembered checkpoint. */
    internal fun peerCryptoDirectory(accountUid: String): String {
        val canonical = java.util.UUID.fromString(accountUid).toString()
        require(canonical == accountUid)
        return request("GET", "/crypto/v1/accounts/$canonical/devices").body
    }

    /** Fetch only bounded events strictly after the previously trusted sequence. */
    internal fun peerCryptoDirectoryHistory(accountUid: String, afterSequence: Long): String {
        val canonical = java.util.UUID.fromString(accountUid).toString()
        require(canonical == accountUid && afterSequence >= 1)
        return request("GET", "/crypto/v1/accounts/$canonical/directory-history?after_sequence=$afterSequence&limit=200").body
    }

    internal fun revokeCryptoDevice(material: CryptoDeviceMaterial) {
        val body = request("DELETE", "/crypto/v1/devices/${material.deviceUid}").body
        parseCryptoRevocation(body, material.deviceUid)
    }

    fun messengerEncryption(peerUid: String): MessengerEncryptionStatus {
        require(peerUid.matches(Regex("^[0-9a-fA-F-]{36}$"))) { "Некорректный собеседник" }
        val response = request("GET", "/messenger/conversations/$peerUid/encryption").body
        return parseMessengerEncryptionStatus(response)
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
            JSONObject().put("native_push_messenger", true).put("native_push_space", true))
    }

    fun removeNativePush(installationUid: String) {
        request("POST", "/notifications/v1/native-push/devices/remove",
            JSONObject().put("installation_uid", installationUid))
    }

    fun birthdayReminderEnabled(): Boolean {
        val root = JSONObject(request("GET", "/notifications/v1/message-preferences").body)
        return root.optJSONObject("preferences")?.optBoolean("friend_birthday_reminders", false) ?: false
    }

    fun setBirthdayReminderEnabled(enabled: Boolean): Boolean {
        val body = JSONObject().put("friend_birthday_reminders", enabled)
        val root = JSONObject(request("PATCH", "/notifications/v1/message-preferences", body).body)
        return root.optJSONObject("preferences")?.optBoolean("friend_birthday_reminders", false) ?: false
    }

    fun notifications(): List<Notice> {
        // First-party server synchronizes deduplicated birthday + activity
        // reminders. This is in-app delivery, not a background FCM message.
        request("POST", "/notifications/v1/sync")
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
        liveIntent = item.optJSONObject("availability")?.optNullableString("intent"),
        liveNote = item.optJSONObject("availability")?.optNullableString("note"),
    )

    private fun parseSpaces(items: JSONArray): List<SpaceSummary> = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("uid").isBlank()) continue
            add(parseSpace(item))
        }
    }

    internal fun parseSpace(space: JSONObject): SpaceSummary {
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
            categorySlugs = space.optJSONArray("category_slugs")?.let { values ->
                buildList { for (index in 0 until values.length()) {
                    values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                } }
            } ?: emptyList(),
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


internal fun parseFriendshipPage(body: String, key: String): PubChatApi.FriendshipPage {
    val root = JSONObject(body)
    val items = root.optJSONArray(key) ?: JSONArray()
    val rows = buildList {
        for (index in 0 until items.length()) {
            val row = items.optJSONObject(index) ?: continue
            val accountUid = row.optString("account_uid").takeIf { it.isNotBlank() } ?: continue
            val persona = row.optJSONObject("persona")
            add(PubChatApi.FriendshipEntry(
                accountUid = accountUid,
                displayName = persona?.optString("display_name")?.takeIf { it.isNotBlank() }
                    ?: persona?.optString("handle")?.takeIf { it.isNotBlank() }
                    ?: "Участник PubChat",
                handle = persona?.optString("handle")?.takeIf { it.isNotBlank() },
                avatar = persona?.optString("avatar")?.takeIf { it.isNotBlank() },
            ))
        }
    }
    return PubChatApi.FriendshipPage(rows, root.optJSONObject("pagination")?.optInt("total", rows.size) ?: rows.size)
}

internal fun parseSocialAvailability(value: JSONObject?): PubChatApi.SocialAvailability? {
    value ?: return null
    val intent = value.optString("intent")
    val expiresAt = value.optString("expires_at")
    if (intent.isBlank() || expiresAt.isBlank()) return null
    return PubChatApi.SocialAvailability(
        intent = intent,
        note = value.optString("note").takeIf { it.isNotBlank() },
        expiresAt = expiresAt,
    )
}


/** Strictly read private /me or PATCH /privacy projections; unknown policies
 * must never silently become 'everyone'. */
internal fun parseDMConsentResponse(body: String): PubChatApi.DMConsentSnapshot {
    val root = JSONObject(body)
    require(root.getString("status") == "ok") { "Сервер не подтвердил настройку приватности" }
    val accountUid = root.getJSONObject("account").getString("uid")
    val persona = root.getJSONObject("persona")
    val personaUid = persona.getString("uid")
    require(accountUid.isNotBlank() && personaUid.isNotBlank()) { "Отсутствует текущий Account/Persona" }
    val policyCode = root.getJSONObject("privacy").getString("dm_policy")
    val policy = PubChatApi.DMConsentPolicy.fromCode(policyCode)
        ?: error("Неизвестная политика личных сообщений")
    return PubChatApi.DMConsentSnapshot(
        accountUid = accountUid,
        personaUid = personaUid,
        primaryPersona = persona.getBoolean("is_primary"),
        policy = policy,
    )
}

/** The PATCH is partial: all other privacy fields remain unchanged. */
internal fun dmConsentPayload(policy: PubChatApi.DMConsentPolicy): JSONObject =
    JSONObject().put("dm_policy", policy.code)
