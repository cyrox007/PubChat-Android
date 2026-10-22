package ru.pubchat.app.core.crypto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class E2eeSessionLedgerTest {
    private val account = "11111111-1111-4111-8111-111111111111"
    private val sender = "22222222-2222-4222-8222-222222222222"
    private val recipient = "33333333-3333-4333-8333-333333333333"
    private val session = "44444444-4444-4444-8444-444444444444"
    private val root = ByteArray(32) { (it + 1).toByte() }

    private fun fresh(): JSONObject = E2eeSessionLedger.empty(account, sender).also {
        E2eeSessionLedger.addSession(it, "out", recipient, session, root)
    }

    @Test fun reservedPacketIsImmutableAcrossSerializedCrashRecovery() {
        val state = fresh()
        val id = UUID.randomUUID().toString()
        val packet = E2eeSessionLedger.reserve(state, id, sender, recipient, session,
            "привет".toByteArray(Charsets.UTF_8))
        assertEquals(0L, packet.counter)
        E2eeSessionLedger.validate(state, account, sender)
        val restored = JSONObject(state.toString())
        E2eeSessionLedger.validate(restored, account, sender)
        assertEquals(packet, E2eeSessionLedger.pending(restored).single())
        val next = E2eeSessionLedger.reserve(restored, UUID.randomUUID().toString(), sender,
            recipient, session, "другое сообщение".toByteArray(Charsets.UTF_8))
        assertEquals(1L, next.counter)
        assertNotEquals(packet.nonce, next.nonce)
        assertNotEquals(packet.ciphertext, next.ciphertext)
        assertEquals(packet, E2eeSessionLedger.pending(restored).single { it.frontId == id })
        assertThrows(Exception::class.java) {
            E2eeSessionLedger.reserve(restored, id, sender, recipient, session, byteArrayOf(1))
        }
    }

    @Test fun ackNeverAdvancesCounterAndUnconfirmedPacketSurvives() {
        val state = fresh()
        val front = UUID.randomUUID().toString()
        val packet = E2eeSessionLedger.reserve(state, front, sender, recipient, session,
            "pending".toByteArray())
        val stillPending = JSONObject(state.toString())
        assertEquals(packet, E2eeSessionLedger.pending(stillPending).single())
        assertFalse(E2eeSessionLedger.acknowledge(stillPending, UUID.randomUUID().toString()))
        assertEquals(packet, E2eeSessionLedger.pending(stillPending).single())
        assertTrue(E2eeSessionLedger.acknowledge(stillPending, front))
        assertTrue(E2eeSessionLedger.pending(stillPending).isEmpty())
        val later = E2eeSessionLedger.reserve(stillPending, UUID.randomUUID().toString(), sender,
            recipient, session, byteArrayOf(1))
        assertEquals(1L, later.counter)
    }

    @Test fun inboundAuthFailureDoesNotBurnCounterAndReplayFailsClosed() {
        val state = E2eeSessionLedger.empty(account, recipient)
        E2eeSessionLedger.addSession(state, "in", sender, session, root)
        val (messageKey, _) = E2eeSessionCrypto.step(root, 0)
        val aad = E2eeSessionCrypto.aad(sender, recipient, session, 0)
        val sealed = E2eeSessionCrypto.seal(messageKey, "trusted".toByteArray(), aad)
        val nonce = CryptoV1.b64(sealed.nonce)
        val original = CryptoV1.b64(sealed.ciphertext)
        val tampered = sealed.ciphertext.clone().apply {
            this[0] = (this[0].toInt() xor 1).toByte()
        }
        assertThrows(Exception::class.java) {
            E2eeSessionLedger.decryptNext(state, sender, recipient, session, 0, nonce,
                CryptoV1.b64(tampered))
        }
        // A failed decrypt never advances the in-memory chain, and the
        // durable wrapper commits only after the transition returns.
        assertArrayEquals("trusted".toByteArray(),
            E2eeSessionLedger.decryptNext(state, sender, recipient, session, 0, nonce, original))
        // Previously authenticated exact ciphertext can be restored from
        // persisted key cache after process restart without ratchet rollback.
        val restored = JSONObject(state.toString())
        assertArrayEquals("trusted".toByteArray(),
            E2eeSessionLedger.decryptNext(restored, sender, recipient, session, 0, nonce, original))
        assertEquals(1L, restored.getJSONObject("sessions")
            .getJSONObject("in:$sender:$session").getLong("next"))
        assertThrows(Exception::class.java) {
            E2eeSessionLedger.decryptNext(restored, sender, recipient, session, 0, nonce,
                CryptoV1.b64(tampered))
        }
        assertThrows(Exception::class.java) {
            E2eeSessionLedger.decryptNext(restored, sender, recipient, session, 100, nonce, original)
        }
        E2eeSessionLedger.validate(state, account, recipient)
    }

    @Test fun rejectsAccountDeviceMixupAndCorruptedState() {
        val state = fresh()
        assertThrows(Exception::class.java) { E2eeSessionLedger.validate(state, account, recipient) }
        assertThrows(Exception::class.java) {
            E2eeSessionLedger.addSession(state, "out", recipient, session, root)
        }
        val damaged = JSONObject(state.toString())
        val sessions = damaged.getJSONObject("sessions")
        val id = sessions.keys().next()
        sessions.getJSONObject(id).put("chain", "invalid")
        assertThrows(Exception::class.java) { E2eeSessionLedger.validate(damaged, account, sender) }
    }

    @Test fun cannotEncryptWithoutExplicitSessionOrUseUnknownVersions() {
        val empty = E2eeSessionLedger.empty(account, sender)
        assertThrows(Exception::class.java) {
            E2eeSessionLedger.reserve(empty, UUID.randomUUID().toString(), sender,
                recipient, session, byteArrayOf(1))
        }
        empty.put("version", 99)
        assertThrows(Exception::class.java) {
            E2eeSessionLedger.validate(empty, account, sender)
        }
    }
}
