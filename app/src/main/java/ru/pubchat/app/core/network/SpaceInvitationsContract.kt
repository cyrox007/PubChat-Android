package ru.pubchat.app.core.network

import org.json.JSONObject
import java.util.UUID

/** Server-only inbox: these are invitations for the authenticated Account, never a public directory. */
data class SpaceInvitation(
    val uid: String,
    val spaceUid: String,
    val spaceName: String,
    val spaceDescription: String?,
    val inviterName: String,
    val expiresAt: String,
)

data class SpaceInvitationPage(
    val rows: List<SpaceInvitation>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

data class SpaceInvitationDecision(val uid: String, val status: String, val space: PubChatApi.SpaceSummary?)

internal fun invitationActionPayload(action: String): JSONObject {
    require(action == "accept" || action == "decline") { "Неизвестное действие с приглашением" }
    return JSONObject().put("action", action)
}

private fun requiredUid(value: String, field: String): String {
    require(value.isNotBlank()) { "В ответе нет $field" }
    return UUID.fromString(value).toString()
}

internal fun parseSpaceInvitationPage(body: String, requestedLimit: Int, requestedOffset: Int): SpaceInvitationPage {
    require(requestedLimit in 1..100 && requestedOffset >= 0) { "Некорректная страница" }
    val root = JSONObject(body)
    require(root.getString("status") == "ok") { "Сервер не подтвердил список приглашений" }
    val pagination = root.getJSONObject("pagination")
    val limit = pagination.getInt("limit")
    val offset = pagination.getInt("offset")
    val total = pagination.getInt("total")
    val rowsJson = root.getJSONArray("invitations")
    require(limit == requestedLimit && offset == requestedOffset && total >= 0 &&
        rowsJson.length() <= limit && pagination.getInt("count") == rowsJson.length()) {
        "Сервер вернул некорректную страницу приглашений"
    }
    val rows = buildList {
        for (index in 0 until rowsJson.length()) {
            val item = rowsJson.getJSONObject(index)
            require(item.getString("status") == "pending") { "В списке получено неактивное приглашение" }
            val uid = requiredUid(item.getString("uid"), "invitation.uid")
            val space = item.getJSONObject("space")
            val spaceUid = requiredUid(space.getString("uid"), "space.uid")
            val inviter = item.optJSONObject("inviter")
            val name = inviter?.optString("display_name")?.takeIf { it.isNotBlank() }
                ?: inviter?.optString("handle")?.takeIf { it.isNotBlank() }?.let { "@$it" }
                ?: "Участник PubChat"
            add(SpaceInvitation(
                uid = uid,
                spaceUid = spaceUid,
                spaceName = space.optString("name").ifBlank { "Пространство" },
                spaceDescription = space.optString("description").takeUnless { it.isBlank() || it == "null" },
                inviterName = name,
                expiresAt = item.getString("expires_at").also { require(it.isNotBlank()) },
            ))
        }
    }
    require(rows.map { it.uid }.distinct().size == rows.size && (rows.isEmpty() || offset + rows.size <= total)) {
        "Сервер вернул противоречивый список приглашений"
    }
    return SpaceInvitationPage(rows, total, limit, offset)
}

internal fun parseSpaceInvitationDecision(
    body: String,
    requestedUid: String,
    requestedAction: String,
    expectedSpaceUid: String,
    parseSpace: (JSONObject) -> PubChatApi.SpaceSummary,
): SpaceInvitationDecision {
    require(requestedAction == "accept" || requestedAction == "decline")
    val root = JSONObject(body)
    require(root.getString("status") == "ok") { "Сервер не подтвердил действие" }
    val item = root.getJSONObject("invitation")
    val uid = requiredUid(item.getString("uid"), "invitation.uid")
    require(uid == requiredUid(requestedUid, "requested.uid")) { "Ответ относится к другому приглашению" }
    val expected = if (requestedAction == "accept") "accepted" else "declined"
    val status = item.getString("status")
    require(status == expected) { "Сервер не подтвердил результат приглашения" }
    val space = if (status == "accepted") {
        val projection = parseSpace(item.getJSONObject("space"))
        require(projection.uid == requiredUid(expectedSpaceUid, "expected.space_uid") && projection.isActiveMember) {
            "Сервер не подтвердил участие в пространстве"
        }
        projection
    } else null
    return SpaceInvitationDecision(uid, status, space)
}

internal fun invitationErrorMessage(error: Throwable): String = when (error) {
    is PubChatApi.ApiFailure -> when (error.message) {
        "space_invitation_not_active" -> "Приглашение уже истекло или отозвано. Обновите список."
        "space_invitation_not_found" -> "Приглашение больше недоступно. Обновите список."
        "social_relationship_blocked" -> "Приглашение недоступно из-за блокировки."
        "space_access_restricted", "invitee_has_active_restriction" -> "Участие в пространстве ограничено."
        "space_full", "space_member_limit_reached" -> "В пространстве закончились свободные места."
        else -> when (error.code) {
            401 -> "Сессия истекла. Войдите снова."
            403 -> "Сервер запретил действие с приглашением."
            404 -> "Приглашение или пространство больше недоступно."
            409 -> "Приглашение больше нельзя обработать. Обновите список."
            else -> "Не удалось обработать приглашение (HTTP ${error.code})."
        }
    }
    else -> error.message?.takeIf { it.isNotBlank() } ?: "Не удалось загрузить приглашения."
}
