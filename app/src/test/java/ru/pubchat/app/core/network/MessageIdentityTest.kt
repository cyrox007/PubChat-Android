package ru.pubchat.app.core.network

import org.junit.Assert.*
import org.junit.Test

class MessageIdentityTest {
    @Test fun optimisticRoomMessageReconcilesWithoutChangingComposeKey() {
        val pending = PubChatApi.ChatMessage(
            uid = "local:abc", frontId = "abc", content = "Hello",
            senderUid = "author", senderName = "Author",
            createdAt = null, contentType = "text", deliveryState = "sending",
        )
        val committed = pending.copy(uid = "server-uid", deliveryState = null)
        val rows = mergeCanonicalMessage(listOf(pending), committed)
        assertEquals(1, rows.size)
        assertEquals("server-uid", rows[0].uid)
        assertEquals("sent", rows[0].deliveryState)
        assertEquals(messageRowKey(pending), messageRowKey(rows[0]))
        assertEquals(1, mergeCanonicalMessage(rows, committed).size)
        assertEquals(2, mergeCanonicalMessage(rows, committed.copy(uid = "another", senderUid = "other")).size)
    }
}
