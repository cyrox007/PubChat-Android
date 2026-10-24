package ru.pubchat.app.core.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingMessengerSendTest {
    @Test
    fun onlyMatchingServerEchoCanConfirmSend() {
        val pending = PendingMessengerSend("request-1", "peer-1", "Привет")
        assertTrue(pending.matchesEcho("request-1", "peer-1"))
        assertFalse(pending.matchesEcho("request-2", "peer-1"))
        assertFalse(pending.matchesEcho("request-1", "peer-2"))
    }

    @Test
    fun draftIsOnlyClearedIfUserHasNotEditedIt() {
        val pending = PendingMessengerSend("request-1", "peer-1", "Привет")
        assertTrue(pending.shouldClearDraft("  Привет  "))
        assertFalse(pending.shouldClearDraft("Привет, ещё текст"))
    }
    @Test
    fun encryptedRetryKeepsOriginalFrontIdOnlyForIdenticalDraftAndPeer() {
        val pending = PendingMessengerSend("fixed-front-id", "peer-1", "Привет", encrypted = true)
        assertTrue(pending.canRetryEncrypted("peer-1", "  Привет  "))
        assertFalse(pending.canRetryEncrypted("peer-2", "Привет"))
        assertFalse(pending.canRetryEncrypted("peer-1", "Новый текст"))
        assertFalse(PendingMessengerSend("plain", "peer-1", "Привет")
            .canRetryEncrypted("peer-1", "Привет"))
        assertTrue(pending.matchesEcho("fixed-front-id", "peer-1"))
    }
}
