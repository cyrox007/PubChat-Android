package ru.pubchat.app.core.push

/**
 * Process-local visible Space state. Never persists user content or identity.
 * OS push remains available while backgrounded or in another app section.
 */
object AppVisibleSpace {
    @Volatile var foreground: Boolean = false
    @Volatile var spaceUid: String? = null

    fun isViewing(roomUid: String?): Boolean =
        foreground && roomUid != null && roomUid == spaceUid
}
