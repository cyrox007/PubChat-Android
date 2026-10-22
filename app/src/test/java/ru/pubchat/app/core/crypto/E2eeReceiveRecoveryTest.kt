package ru.pubchat.app.core.crypto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class E2eeReceiveRecoveryTest {
    private val account = "11111111-1111-4111-8111-111111111111"
    private val sender = "22222222-2222-4222-8222-222222222222"
    private val recipient = "33333333-3333-4333-8333-333333333333"
    private val session = "44444444-4444-4444-8444-444444444444"
    private val seed = ByteArray(32) { (it + 9).toByte() }
    private val root: JSONObject get() = E2eeSessionLedger.empty(account, recipient).also {
        E2eeSessionLedger.addSession(it, "in", sender, session, seed)
    }

    private fun ciphertext(n: Long): E2eeSessionCrypto.Sealed {
        var chain = seed.copyOf()
        var message = ByteArray(32)
        for (index in 0..n) {
            val (nextMessage, nextChain) = E2eeSessionCrypto.step(chain, index)
            chain.fill(0)
            message.fill(0)
            chain = nextChain
            message = nextMessage
        }
        val sealed = E2eeSessionCrypto.seal(message, "text$n".toByteArray(),
            E2eeSessionCrypto.aad(sender, recipient, session, n))
        message.fill(0)
        chain.fill(0)
        return sealed
    }

    private fun decrypt(state: JSONObject, n: Long, packet: E2eeSessionCrypto.Sealed): ByteArray =
        E2eeSessionLedger.decryptNext(state, sender, recipient, session, n,
            CryptoV1.b64(packet.nonce), CryptoV1.b64(packet.ciphertext))

    @Test fun lateDeliveryAndHistoryRecoveryAfterPersistedRestart() {
        val first = ciphertext(0)
        val third = ciphertext(2)
        val second = ciphertext(1)
        val ledger = root
        assertArrayEquals("text0".toByteArray(), decrypt(ledger, 0, first))
        assertArrayEquals("text2".toByteArray(), decrypt(ledger, 2, third))
        assertEquals(3L, ledger.getJSONObject("sessions").getJSONObject(
            "in:$sender:$session").getLong("next"))
        E2eeSessionLedger.validate(ledger, account, recipient)

        val reloaded = JSONObject(ledger.toString())
        assertArrayEquals("text1".toByteArray(), decrypt(reloaded, 1, second))
        assertArrayEquals("text0".toByteArray(), decrypt(reloaded, 0, first))
        assertArrayEquals("text2".toByteArray(), decrypt(reloaded, 2, third))
        E2eeSessionLedger.validate(reloaded, account, recipient)
        assertEquals(3L, reloaded.getJSONObject("sessions").getJSONObject(
            "in:$sender:$session").getLong("next"))

        val tampered = second.copy(ciphertext = second.ciphertext.copyOf().apply {
            this[0] = (this[0].toInt() xor 0x1).toByte()
        })
        assertThrows(Exception::class.java) { decrypt(reloaded, 1, tampered) }
        val differentNonce = second.copy(nonce = ByteArray(12) { 0x77 })
        assertThrows(Exception::class.java) { decrypt(reloaded, 1, differentNonce) }
    }

    @Test fun authenticationFailureDoesNotAdvanceOrConsumeSkippedKeys() {
        val state = root
        val third = ciphertext(2)
        val damaged = third.copy(ciphertext = third.ciphertext.clone().apply {
            this[0] = (this[0].toInt() xor 0x1).toByte()
        })
        assertThrows(Exception::class.java) { decrypt(state, 2, damaged) }
        assertEquals(0L, state.getJSONObject("sessions").getJSONObject(
            "in:$sender:$session").getLong("next"))
        assertArrayEquals("text2".toByteArray(), decrypt(state, 2, third))
        E2eeSessionLedger.validate(state, account, recipient)
    }

    @Test fun hugeCounterGapFailsClosedWithoutChangingState() {
        val state = root
        val prior = state.toString()
        assertThrows(Exception::class.java) {
            decrypt(state, 65, ciphertext(65))
        }
        assertEquals(prior, state.toString())
    }
}
