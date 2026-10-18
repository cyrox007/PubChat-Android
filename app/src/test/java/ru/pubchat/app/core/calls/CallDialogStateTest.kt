package ru.pubchat.app.core.calls

import org.junit.Assert.*
import org.junit.Test

class CallDialogStateTest {
    @Test
    fun unavailableSignalingAfterLoginDoesNotOpenPhantomCall() {
        listOf(null, "", "previous-peer").forEach { peer ->
            assertFalse(shouldShowCallDialog("unavailable", peer))
            assertFalse(shouldShowCallDialog("idle", peer))
            assertFalse(shouldShowCallDialog("ended", peer))
            assertFalse(shouldShowCallDialog("unknown", peer))
        }
        assertFalse(shouldShowCallDialog("error", null))
    }

    @Test
    fun realCallAndItsFailureRemainVisible() {
        listOf("incoming", "outgoing", "connecting", "reconnecting", "active",
            "video_track", "error", "disconnected").forEach { phase ->
            assertTrue("Missing dialog for $phase", shouldShowCallDialog(phase, "peer-uid"))
        }
    }
}
