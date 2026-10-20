package ru.pubchat.app.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativePushDestinationTest {
    @Test fun privateMessageGoesToInboxWithoutPeerLeak() {
        assertEquals("pubchat://inbox", nativePushDestination("messenger", null))
        assertEquals("pubchat://inbox", nativePushDestination("messenger", "malicious://peer"))
    }

    @Test fun spaceReplyRequiresUUIDAndNeverAcceptsArbitraryURL() {
        val room = "11111111-1111-4111-8111-111111111111"
        assertEquals("pubchat://spaces/$room", nativePushDestination("space_reply", room))
        assertNull(nativePushDestination("space_reply", "../../settings"))
        assertNull(nativePushDestination("space_reply", "https://example.invalid/"))
        assertNull(nativePushDestination("space_reply", null))
    }

    @Test fun unknownEventAndCallsHaveNoChatDestination() {
        assertNull(nativePushDestination("call", "11111111-1111-4111-8111-111111111111"))
        assertNull(nativePushDestination("unknown", null))
        assertNull(nativePushDestination(null, null))
    }
}
