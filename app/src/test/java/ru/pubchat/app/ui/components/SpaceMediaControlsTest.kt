package ru.pubchat.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SpaceMediaControlsTest {
    @Test fun displaysCompactPlaybackTime() {
        assertEquals("0:00", voiceClock(0))
        assertEquals("0:09", voiceClock(9999))
        assertEquals("1:05", voiceClock(65000))
        assertEquals("12:09", voiceClock(729000))
    }

    @Test fun rejectsNegativePlaybackPositionWithoutCrashing() {
        assertEquals("0:00", voiceClock(-2000))
    }
}
