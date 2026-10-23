package ru.pubchat.app.core.network

import org.junit.Assert.assertThrows
import org.junit.Test

class E2eeDeliveryPolicyTest {
    @Test fun requiresServerConfirmedE2eeAndCryptoReadiness() {
        requireE2eeDeliveryPolicy(
            MessengerEncryptionStatus(ConversationSystemPolicy.REQUIRED, ConversationEffectiveMode.E2EE, true)
        )
        requireE2eeDeliveryPolicy(
            MessengerEncryptionStatus(ConversationSystemPolicy.OPTIONAL, ConversationEffectiveMode.E2EE, true)
        )
        for (status in listOf(
            MessengerEncryptionStatus(ConversationSystemPolicy.REQUIRED, ConversationEffectiveMode.E2EE, false),
            MessengerEncryptionStatus(ConversationSystemPolicy.OPTIONAL, ConversationEffectiveMode.PLAINTEXT, true),
            MessengerEncryptionStatus(ConversationSystemPolicy.DISABLED, ConversationEffectiveMode.PLAINTEXT, false),
        )) {
            assertThrows(IllegalStateException::class.java) {
                requireE2eeDeliveryPolicy(status)
            }
        }
    }
}
