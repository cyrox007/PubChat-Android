package ru.pubchat.app.core.push

import org.junit.Assert.*
import org.junit.Test
import ru.pubchat.app.core.network.PubChatApi
import ru.pubchat.app.core.network.parseNotificationsResponse
import java.util.UUID

class ForegroundSpaceRepliesTest {
    private val roomUid = UUID.randomUUID().toString()

    private fun notice(uid: String, read: Boolean = false, kind: String = "space_reply") =
        PubChatApi.Notice(uid, "Ответ на ваше сообщение", "В Space ответили на ваше сообщение.",
            read, roomUid, "space", kind)

    @Test fun parsesCanonicalServerSpaceReplyWithoutLegacyPayload() {
        val result = parseNotificationsResponse(
            """{"notifications":[{"uid":"reply-1","kind":"space_reply","title":"Ответ на ваше сообщение","body":"Ответили на ваше сообщение.","context_space_uid":"$roomUid","is_read":false}]}"""
        )
        assertEquals(1, result.size)
        assertEquals("space_reply", result[0].kind)
        assertEquals("space", result[0].surface)
        assertEquals(roomUid, result[0].targetUid)
        assertFalse(result[0].read)
    }

    @Test fun firstForegroundSnapshotDoesNotReannounceHistoricalUnread() {
        assertNull(foregroundSpaceReplyCandidate(
            listOf(notice("old")), null, null, false, false,
        ))
    }

    @Test fun freshReplyShowsInAnotherSectionButNotOpenRoom() {
        val messages = listOf(notice("new"), notice("old"))
        assertEquals("new", foregroundSpaceReplyCandidate(
            messages, setOf("old"), null, false, false,
        )?.uid)
        assertNull(foregroundSpaceReplyCandidate(
            messages, setOf("old"), roomUid, true, false,
        ))
        assertNull(foregroundSpaceReplyCandidate(
            messages, setOf("old"), null, false, true,
        ))
    }

    @Test fun readOrAlreadySeenOrUnrelatedEventsDoNotAnnounce() {
        assertNull(foregroundSpaceReplyCandidate(
            listOf(notice("read", true), notice("ordinary", kind = "activity_reminder")),
            emptySet(), null, false, false,
        ))
        assertNull(foregroundSpaceReplyCandidate(
            listOf(notice("known")), setOf("known"), null, false, false,
        ))
    }

    @Test fun operatingSystemPushIsSuppressedOnlyForVisibleSameSpace() {
        try {
            AppVisibleSpace.foreground = true
            AppVisibleSpace.spaceUid = roomUid
            assertTrue(AppVisibleSpace.isViewing(roomUid))
            assertFalse(AppVisibleSpace.isViewing(UUID.randomUUID().toString()))
            AppVisibleSpace.foreground = false
            assertFalse(AppVisibleSpace.isViewing(roomUid))
        } finally {
            AppVisibleSpace.spaceUid = null
            AppVisibleSpace.foreground = false
        }
    }

    @Test fun missingContextDoesNotInventSpaceNavigation() {
        val item = parseNotificationsResponse(
            """{"notifications":[{"uid":"unknown","kind":"space_reply","is_read":false}]}"""
        ).single()
        assertNull(item.targetUid)
        assertEquals("space", item.surface)
    }
}
