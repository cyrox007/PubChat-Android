package ru.pubchat.app.core.realtime

/**
 * One pending server-confirmed Messenger send at a time.
 * The draft is retained until a matching server echo arrives.
 */
internal data class PendingMessengerSend(
    val frontId: String,
    val peerUid: String,
    val text: String,
    val encrypted: Boolean = false,
) {
    fun canRetryEncrypted(peerUid: String, draft: String): Boolean =
        encrypted && this.peerUid == peerUid && this.text == draft.trim()

    fun matchesEcho(frontId: String, peerUid: String): Boolean =
        this.frontId == frontId && this.peerUid == peerUid

    fun shouldClearDraft(currentDraft: String): Boolean =
        currentDraft.trim() == text
}
