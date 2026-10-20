package ru.pubchat.app.core.push

import java.util.UUID

/**
 * OS notification destinations contain no sender identity or decrypted message.
 * An unsupported or malformed event must never open an arbitrary URI.
 */
fun nativePushDestination(type: String?, roomUid: String?): String? = when (type) {
    "messenger" -> "pubchat://inbox"
    "space_reply" -> {
        val validRoom = runCatching { UUID.fromString(roomUid) }.getOrNull()
        validRoom?.let { "pubchat://spaces/$it" }
    }
    else -> null
}
