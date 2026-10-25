package ru.pubchat.app.core.push

import ru.pubchat.app.core.network.PubChatApi

/**
 * Only a newly seen, still-unread, personal Space reply should interrupt
 * an unrelated section. Existing inbox items remain in Activity without
 * being re-announced on every poll/resume.
 */
internal fun foregroundSpaceReplyCandidate(
    notices: List<PubChatApi.Notice>,
    previouslySeen: Set<String>?,
    currentRoomUid: String?,
    viewingRoom: Boolean,
    viewingActivity: Boolean,
): PubChatApi.Notice? {
    if (previouslySeen == null || viewingActivity) return null
    return notices.firstOrNull { notice ->
        notice.kind == "space_reply" &&
            !notice.read &&
            notice.uid !in previouslySeen &&
            !(viewingRoom && notice.targetUid == currentRoomUid)
    }
}
