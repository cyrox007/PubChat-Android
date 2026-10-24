package ru.pubchat.app.core.crypto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class E2eeFanoutBatchTest {
    private val account = "11111111-1111-4111-8111-111111111111"
    private val ownDevice = "22222222-2222-4222-8222-222222222222"
    private val secondOwn = "33333333-3333-4333-8333-333333333333"
    private val peerAccount = "44444444-4444-4444-8444-444444444444"
    private val peerDevice = "55555555-5555-4555-8555-555555555555"
    private val sessionA = "66666666-6666-4666-8666-666666666666"
    private val sessionB = "77777777-7777-4777-8777-777777777777"
    private val sessionC = "88888888-8888-4888-8888-888888888888"
    private val rootKey = ByteArray(32) { (it + 7).toByte() }
    private val ownTargets = listOf(
        E2eeFanoutBatch.Target(ownDevice, sessionA),
        E2eeFanoutBatch.Target(secondOwn, sessionB),
    )
    private val peerTargets = listOf(E2eeFanoutBatch.Target(peerDevice, sessionC))

    private fun fresh(): JSONObject = E2eeSessionLedger.empty(account, ownDevice).also { state ->
        for (target in ownTargets + peerTargets) {
            E2eeSessionLedger.addSession(state, "out", target.deviceUid, target.sessionId, rootKey)
        }
    }

    @Test fun commitsAllDevicesAndCanRetryOnlyIdenticalCiphertextAfterSerialization() {
        val state = fresh()
        val frontId = UUID.randomUUID().toString()
        val batch = E2eeFanoutBatch.prepare(state, frontId, ownDevice, peerAccount,
            ownTargets, peerTargets, "{\"type\":\"text\",\"text\":\"привет\"}".toByteArray())
        assertEquals(3, batch.envelopes.size)
        assertEquals(3, E2eeSessionLedger.pending(state).size)
        E2eeSessionLedger.validate(state, account, ownDevice)
        val afterCrash = JSONObject(state.toString())
        val retrieved = E2eeFanoutBatch.load(afterCrash, frontId)
        assertEquals(batch.envelopes, retrieved.envelopes)
        assertEquals(batch.frontId, retrieved.frontId)
        assertEquals(3, retrieved.requestBody().getJSONArray("envelopes").length())
        assertEquals(frontId, retrieved.requestBody().getString("front_id"))
        assertEquals(peerAccount, retrieved.receiverUid)
        assertThrows(Exception::class.java) {
            E2eeFanoutBatch.prepare(afterCrash, frontId, ownDevice, peerAccount,
                ownTargets, peerTargets, byteArrayOf(10))
        }
        val second = E2eeFanoutBatch.prepare(afterCrash, UUID.randomUUID().toString(), ownDevice,
            peerAccount, ownTargets, peerTargets, "{\"type\":\"text\",\"text\":\"другое\"}".toByteArray())
        assertTrue(second.envelopes.all { it.counter == 1L })
        assertTrue(batch.envelopes.all { it.counter == 0L })
        assertEquals(batch.envelopes, E2eeFanoutBatch.load(afterCrash, frontId).envelopes)
        E2eeSessionLedger.validate(afterCrash, account, ownDevice)
    }

    @Test fun pendingRecoveryIdsSurviveRestartAndRemainScopedToPeer() {
        val state = fresh()
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()
        E2eeFanoutBatch.prepare(state, firstId, ownDevice, peerAccount,
            ownTargets, peerTargets, "first".toByteArray())
        E2eeFanoutBatch.prepare(state, secondId, ownDevice, peerAccount,
            ownTargets, peerTargets, "second".toByteArray())
        val recovered = JSONObject(state.toString())
        assertEquals(listOf(firstId, secondId).sorted(),
            E2eeFanoutBatch.pendingIdsForPeer(recovered, peerAccount))
        assertTrue(E2eeFanoutBatch.pendingIdsForPeer(recovered, account).isEmpty())
        val ciphertext = E2eeFanoutBatch.load(recovered, firstId).requestBody().toString()
        assertTrue(E2eeFanoutBatch.acknowledge(recovered, firstId))
        assertEquals(listOf(secondId), E2eeFanoutBatch.pendingIdsForPeer(recovered, peerAccount))
        // Enumeration never re-encrypts surviving ciphertext or increments counters.
        assertEquals(E2eeFanoutBatch.load(state, secondId).requestBody().toString(),
            E2eeFanoutBatch.load(recovered, secondId).requestBody().toString())
        assertTrue(ciphertext.isNotBlank())
    }

    @Test fun ackIsAllOrNothingAndCannotDeleteSingleDevicePacket() {
        val state = fresh()
        val frontId = UUID.randomUUID().toString()
        E2eeFanoutBatch.prepare(state, frontId, ownDevice, peerAccount, ownTargets, peerTargets,
            "hello".toByteArray())
        val packet = E2eeSessionLedger.pending(state).first()
        assertThrows(IllegalArgumentException::class.java) {
            E2eeSessionLedger.acknowledge(state, packet.frontId)
        }
        assertEquals(3, E2eeSessionLedger.pending(state).size)
        assertTrue(E2eeFanoutBatch.acknowledge(state, frontId))
        assertFalse(E2eeFanoutBatch.acknowledge(state, frontId))
        assertTrue(E2eeSessionLedger.pending(state).isEmpty())
        E2eeSessionLedger.validate(state, account, ownDevice)
        val later = E2eeFanoutBatch.prepare(state, UUID.randomUUID().toString(), ownDevice, peerAccount,
            ownTargets, peerTargets, "later".toByteArray())
        assertTrue(later.envelopes.all { it.counter == 1L })
    }

    @Test fun rejectsMissingSenderDuplicateDevicesOrIncompletePrebuiltSessions() {
        val state = fresh()
        assertThrows(Exception::class.java) {
            E2eeFanoutBatch.prepare(state, UUID.randomUUID().toString(), ownDevice, peerAccount,
                listOf(E2eeFanoutBatch.Target(secondOwn, sessionB)), peerTargets, byteArrayOf(1))
        }
        assertThrows(Exception::class.java) {
            E2eeFanoutBatch.prepare(state, UUID.randomUUID().toString(), ownDevice, peerAccount,
                ownTargets, listOf(E2eeFanoutBatch.Target(ownDevice, sessionA)), byteArrayOf(1))
        }
        assertThrows(Exception::class.java) {
            E2eeFanoutBatch.prepare(state, UUID.randomUUID().toString(), ownDevice, peerAccount,
                ownTargets, emptyList(), byteArrayOf(1))
        }
        assertThrows(Exception::class.java) {
            E2eeFanoutBatch.prepare(state, UUID.randomUUID().toString(), ownDevice, peerAccount,
                ownTargets, listOf(E2eeFanoutBatch.Target(UUID.randomUUID().toString(), UUID.randomUUID().toString())),
                byteArrayOf(1))
        }
        // Failed staging may partially mutate only the in-memory copy.
        // E2eeSessionStore.mutate discards that copy without persist on error.
    }

    @Test fun corruptedPendingCiphertextOrSessionMismatchFailsClosed() {
        val state = fresh()
        val frontId = UUID.randomUUID().toString()
        E2eeFanoutBatch.prepare(state, frontId, ownDevice, peerAccount, ownTargets, peerTargets,
            "hello".toByteArray())
        val damaged = JSONObject(state.toString())
        val first = damaged.getJSONObject("batches").getJSONObject(frontId).getJSONArray("packet_ids").getString(0)
        damaged.getJSONObject("pending").getJSONObject(first).put("ciphertext", CryptoV1.b64(byteArrayOf(1)))
        assertThrows(Exception::class.java) { E2eeSessionLedger.validate(damaged, account, ownDevice) }

        val swapped = JSONObject(state.toString())
        swapped.getJSONObject("batches").getJSONObject(frontId).getJSONArray("envelopes").getJSONObject(0)
            .put("counter", 123)
        assertThrows(Exception::class.java) { E2eeSessionLedger.validate(swapped, account, ownDevice) }
    }
}
