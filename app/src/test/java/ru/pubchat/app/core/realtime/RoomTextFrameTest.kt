package ru.pubchat.app.core.realtime

import org.junit.Assert.*
import org.junit.Test

class RoomTextFrameTest {
    @Test fun repliesUseTheRoomRealtimeContract() {
        val frame = roomTextFrame(" test ", "account", "Name", "source-message")
        assertEquals("test", frame.getString("content"))
        assertEquals("text", frame.getString("content_type"))
        assertEquals("source-message", frame.getString("reply_to_uid"))
        assertEquals("account", frame.getJSONObject("sender").getString("uid"))
        assertTrue(frame.getString("frontId").isNotBlank())
    }
    @Test fun ordinaryMessagesDoNotCarryAReplyReference() {
        assertFalse(roomTextFrame("test", "account", "Name", null).has("reply_to_uid"))
        assertFalse(roomTextFrame("test", "account", "Name", " ").has("reply_to_uid"))
    }
}