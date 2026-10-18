package ru.pubchat.app.core.realtime

import org.junit.Assert.*
import org.junit.Test

class RealtimeRetryPolicyTest {
    @Test fun backoffCapsAtThirtySeconds() {
        assertEquals(1_000L, RealtimeRetryPolicy.delayMillis(0))
        assertEquals(2_000L, RealtimeRetryPolicy.delayMillis(1))
        assertEquals(8_000L, RealtimeRetryPolicy.delayMillis(3))
        assertEquals(30_000L, RealtimeRetryPolicy.delayMillis(30))
    }
    @Test fun backoffHasBoundedJitter() {
        assertEquals(800L, RealtimeRetryPolicy.delayMillis(0, 0.0))
        assertEquals(1_200L, RealtimeRetryPolicy.delayMillis(0, 1.0))
        assertEquals(24_000L, RealtimeRetryPolicy.delayMillis(10, 0.0))
        assertEquals(36_000L, RealtimeRetryPolicy.delayMillis(10, 1.0))
    }
    @Test fun accessErrorsAreNotRetried() {
        for (status in listOf(401, 403, 404)) assertTrue(RealtimeRetryPolicy.terminalHttpStatus(status))
        for (status in listOf(null, 408, 429, 500, 502, 503, 504)) assertFalse(RealtimeRetryPolicy.terminalHttpStatus(status))
        assertTrue(RealtimeRetryPolicy.terminalCloseCode(1008))
        assertTrue(RealtimeRetryPolicy.terminalCloseCode(4001))
        assertFalse(RealtimeRetryPolicy.terminalCloseCode(1000))
        assertFalse(RealtimeRetryPolicy.terminalCloseCode(1006))
    }
}
