package ru.pubchat.app.core.network

import org.json.JSONObject

private fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }

internal fun parseChatMessage(json: JSONObject): PubChatApi.ChatMessage? {
    val uid = json.text("uid") ?: json.text("frontId") ?: return null
    val sender = json.optJSONObject("sender")
    val metadata = json.optJSONObject("media_metadata")
    val reply = json.optJSONObject("reply_to") ?: metadata?.optJSONObject("reply_to")
    val replySender = reply?.optJSONObject("sender")
    val files = metadata?.optJSONArray("files")
    val attachments = buildList {
        if (files != null) for (index in 0 until files.length()) {
            val item = files.optJSONObject(index) ?: continue
            val url = item.text("url") ?: continue
            add(PubChatApi.Attachment(url, item.text("name") ?: "Вложение", item.text("type")))
        }
    }
    val type = json.text("content_type") ?: "text"
    val content = json.text("content").orEmpty()
    val media = if (attachments.isEmpty() && type in setOf("audio", "voice", "image", "video", "file") &&
        (content.startsWith("https://") || content.startsWith("http://") || content.startsWith("/uploads/"))) {
        listOf(PubChatApi.Attachment(content, when(type) { "audio", "voice" -> "Голосовое сообщение"; "image" -> "Изображение"; "video" -> "Видео"; else -> "Вложение" }, null))
    } else attachments
    return PubChatApi.ChatMessage(
        uid = uid, frontId = json.text("frontId"),
        content = content, contentType = type,
        senderUid = sender?.text("uid") ?: json.text("sender_uid"),
        senderName = sender?.text("display_name") ?: sender?.text("name") ?: sender?.text("username"),
        senderAvatar = sender?.text("avatar"), createdAt = json.text("created_at"),
        receiverUid = json.text("receiver_uid"), isRead = json.optBoolean("is_read", false),
        reactions = metadata?.optJSONObject("reactions")?.toString(),
        replyToUid = reply?.text("uid") ?: json.text("reply_to_uid"),
        replyContent = reply?.text("content"),
        replySenderName = replySender?.text("display_name") ?: replySender?.text("name") ?: replySender?.text("username"),
        attachmentName = media.firstOrNull()?.name, attachments = media,
    )
}
