package ru.pubchat.app.core.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SocialAvailabilityTest {
    @Test fun readsExpiringIntentWithoutPrivateFields() {
        val json = JSONObject("""{"intent":"meet","note":"Ищу компанию","expires_at":"2026-10-18T20:00:00"}""")
        val value = parseSocialAvailability(json)
        assertNotNull(value)
        assertEquals("meet", value?.intent)
        assertEquals("Ищу компанию", value?.note)
        assertEquals("2026-10-18T20:00:00", value?.expiresAt)
    }

    @Test fun missingOrExpiredStateIsRepresentedAsAbsent() {
        assertNull(parseSocialAvailability(null))
        assertNull(parseSocialAvailability(JSONObject("{}")))
        assertNull(parseSocialAvailability(JSONObject("""{"intent":"open"}""")))
    }
}
