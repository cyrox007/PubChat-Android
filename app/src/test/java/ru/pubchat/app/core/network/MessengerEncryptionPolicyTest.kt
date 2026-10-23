package ru.pubchat.app.core.network

import org.junit.Assert.*
import org.junit.Test

class MessengerEncryptionPolicyTest {
    private fun response(system: String = "optional", mode: String = "plaintext", ready: Boolean = false) = """
        {"status":"ok","encryption":{
          "system_policy":"$system","configured_mode":null,"effective_mode":"$mode",
          "can_change":true,"crypto_ready":$ready,
          "own_crypto_devices":0,"peer_crypto_devices":0}}
    """.trimIndent()

    @Test fun optionalUnencryptedConversationRemainsPlaintextAndClearlyLabeled() {
        val policy = parseMessengerEncryptionStatus(response())
        assertEquals(ConversationSystemPolicy.OPTIONAL, policy.systemPolicy)
        assertEquals(ConversationEffectiveMode.PLAINTEXT, policy.effectiveMode)
        assertTrue(policy.canUseLegacyTransport)
        assertTrue(policy.explanation.contains("без E2EE"))
        requirePlaintextMessengerTransport(policy)
    }

    @Test fun requiredE2eeCannotBeSentAsPlaintextEvenWithoutDevices() {
        val policy = parseMessengerEncryptionStatus(response("required", "e2ee", false))
        assertFalse(policy.canUseLegacyTransport)
        assertFalse(policy.cryptoReady)
        assertTrue(policy.explanation.contains("заблокирована"))
        assertThrows(IllegalStateException::class.java) { requirePlaintextMessengerTransport(policy) }
    }

    @Test fun explicitlyEncryptedOptionalConversationNeverDowngrades() {
        val policy = parseMessengerEncryptionStatus(response("optional", "e2ee", true))
        assertFalse(policy.canUseLegacyTransport)
        assertThrows(IllegalStateException::class.java) { requirePlaintextMessengerTransport(policy) }
    }

    @Test fun administrativelyDisabledE2eeIsRecognizedAsOrdinaryMessaging() {
        val policy = parseMessengerEncryptionStatus(response("disabled", "plaintext"))
        assertEquals(ConversationSystemPolicy.DISABLED, policy.systemPolicy)
        assertTrue(policy.canUseLegacyTransport)
    }

    @Test fun unrecognizedOrMissingPolicyBlocksSending() {
        for (invalid in listOf(
            response("future_policy", "plaintext"),
            response("optional", "unknown"),
            response().replace("\"effective_mode\":\"plaintext\"", "\"wrong_key\":true"),
            response().replace("\"crypto_ready\":false,", ""),
            response().replace("\"status\":\"ok\"", "\"status\":\"error\""),
            response("required", "plaintext"),
            response("disabled", "e2ee"),
        )) {
            try {
                val status = parseMessengerEncryptionStatus(invalid)
                requirePlaintextMessengerTransport(status)
                fail("Invalid policy must never enable a legacy send")
            } catch (_: Exception) {
                // A malformed or contradictory server response is never an authorization.
            }
        }
    }
}
