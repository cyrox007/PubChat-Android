package ru.pubchat.app.core.network

/** Client identity remains stable after PostgreSQL assigns the canonical UID. */
internal fun messageRowKey(message: PubChatApi.ChatMessage): String = message.frontId ?: message.uid

internal fun isSameMessage(a: PubChatApi.ChatMessage, b: PubChatApi.ChatMessage): Boolean {
    if (!a.uid.startsWith("local:") && !b.uid.startsWith("local:")) {
        // Canonical PostgreSQL rows with different UIDs cannot be the same message.
        return a.uid == b.uid
    }
    val sameFrontId = !a.frontId.isNullOrBlank() && a.frontId == b.frontId
    val sameSender = a.senderUid == null || b.senderUid == null || a.senderUid == b.senderUid
    return sameFrontId && sameSender
}

internal fun mergeCanonicalMessage(
    messages: List<PubChatApi.ChatMessage>,
    incoming: PubChatApi.ChatMessage,
): List<PubChatApi.ChatMessage> {
    val index = messages.indexOfFirst { isSameMessage(it, incoming) }
    if (index < 0) return messages + incoming
    val previous = messages[index]
    // Canonical server data takes precedence, but a history projection from
    // an older server must not erase the optimistic client's correlation ID.
    val canonical = incoming.copy(
        frontId = incoming.frontId ?: previous.frontId,
        deliveryState = if (!incoming.uid.startsWith("local:")) "sent"
            else incoming.deliveryState ?: previous.deliveryState,
    )
    return messages.toMutableList().also { it[index] = canonical }
}
