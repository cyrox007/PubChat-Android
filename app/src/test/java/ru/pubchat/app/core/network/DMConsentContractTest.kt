package ru.pubchat.app.core.network

import org.junit.Assert.*
import org.junit.Test

class DMConsentContractTest {
    @Test fun allFourPoliciesPatchOnlyDmPolicy() {
        PubChatApi.DMConsentPolicy.values().forEach { policy ->
            val payload = dmConsentPayload(policy)
            assertEquals(1, payload.length())
            assertEquals(policy.code, payload.getString("dm_policy"))
            assertFalse(payload.has("account_uid"))
            assertFalse(payload.has("profile_visibility"))
            assertFalse(payload.has("persona_uid"))
        }
    }

    @Test fun primaryPersonaUsesServerPrivateProjection() {
        val body = """
            {"status":"ok","account":{"uid":"account-a"},
             "persona":{"uid":"persona-a","is_primary":true},
             "privacy":{"dm_policy":"shared_spaces","profile_visibility":"public"}}
        """.trimIndent()
        val projection = parseDMConsentResponse(body)
        assertEquals("account-a", projection.accountUid)
        assertEquals("persona-a", projection.personaUid)
        assertTrue(projection.primaryPersona)
        assertEquals(PubChatApi.DMConsentPolicy.SHARED_SPACES, projection.policy)
    }

    @Test fun secondaryPersonaMustNotPresentEditableAccountPrivacy() {
        val body = """
            {"status":"ok","account":{"uid":"account-a"},
             "persona":{"uid":"persona-b","is_primary":false},
             "privacy":{"dm_policy":"nobody"}}
        """.trimIndent()
        val projection = parseDMConsentResponse(body)
        assertFalse(projection.primaryPersona)
        assertEquals(PubChatApi.DMConsentPolicy.NOBODY, projection.policy)
    }

    @Test fun unknownOrMissingPolicyIsNeverInterpretedAsEveryone() {
        val unknown = """
            {"status":"ok","account":{"uid":"account-a"},
             "persona":{"uid":"persona-a","is_primary":true},
             "privacy":{"dm_policy":"unrecognized"}}
        """.trimIndent()
        val missing = unknown.replace("\"dm_policy\":\"unrecognized\"", "\"show_age\":true")
        for (invalid in listOf(unknown, missing)) {
            try {
                parseDMConsentResponse(invalid)
                fail("Invalid policy must be rejected, not replaced with a permissive default")
            } catch (_: Exception) {
                // Deliberate fail-closed parsing.
            }
        }
    }

    @Test fun accountAndPersonaIdentifiersAreRequired() {
        val invalid = """
            {"status":"ok","account":{"uid":""},
             "persona":{"uid":"persona-a","is_primary":true},
             "privacy":{"dm_policy":"everyone"}}
        """.trimIndent()
        try {
            parseDMConsentResponse(invalid)
            fail("Identity mismatch must not be hidden")
        } catch (_: IllegalArgumentException) {
            // Caller must not save based on an unidentified Account.
        }
    }
}
