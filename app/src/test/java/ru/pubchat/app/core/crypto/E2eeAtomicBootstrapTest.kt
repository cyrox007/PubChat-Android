package ru.pubchat.app.core.crypto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class E2eeAtomicBootstrapTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val peer = "22222222-2222-4222-8222-222222222222"
    private val ownUid = "33333333-3333-4333-8333-333333333333"
    private val peerUid = "44444444-4444-4444-8444-444444444444"
    private val checkpoint = DeviceDirectoryCheckpoint(1, "a".repeat(64), "0".repeat(64), "b".repeat(64))

    private fun local(account: String, device: String, offset: Int): CryptoDeviceMaterial =
        CryptoDeviceMaterial(account, device,
            ByteArray(32) { (it + offset).toByte() },
            ByteArray(32) { (it + offset + 32).toByte() },
            CryptoPreKey(123 + offset, ByteArray(32) { (it + offset + 64).toByte() }),
            emptyList())

    private fun checked(material: CryptoDeviceMaterial): VerifiedPeerPrekey {
        val entry = material.registrationPayload().put("fingerprint", material.fingerprint)
        val source = JSONObject().put("status", "ok").put("account_uid", material.accountUid)
            .put("devices", JSONArray().put(entry))
        return CryptoPeerPrekeyValidation.directory(source.toString(), material.accountUid)
            .getValue(material.deviceUid)
    }

    private fun fixture(): Pair<CryptoDeviceMaterial, E2eeDevicePreflight.VerifiedTargets> {
        val mine = local(owner, ownUid, 1)
        val remote = local(peer, peerUid, 5)
        val ownKey = checked(mine)
        val peerKey = checked(remote)
        return mine to E2eeDevicePreflight.VerifiedTargets(
            owner, peer, ownUid, mapOf(ownUid to ownKey), mapOf(peerUid to peerKey),
            mapOf(peerUid to peerKey), checkpoint, checkpoint)
    }

    @Test fun newSessionsAndInitialHeadersAreCommittedWithWholeFanout() {
        val (identity, verified) = fixture()
        val state = E2eeSessionLedger.empty(owner, ownUid)
        val frontId = UUID.randomUUID().toString()
        val first = E2eeAtomicBootstrap.prepare(state, identity, verified, frontId,
            "hello".toByteArray())
        assertEquals(setOf(ownUid, peerUid), first.envelopes.map { it.recipientDeviceUid }.toSet())
        assertTrue(first.envelopes.all { it.counter == 0L && it.initialHeader != null })
        assertEquals(2, state.getJSONObject("sessions").length())
        E2eeSessionLedger.validate(state, owner, ownUid)
        val restored = JSONObject(state.toString())
        assertEquals(first.envelopes.map { it.ciphertext },
            E2eeFanoutBatch.load(restored, frontId).envelopes.map { it.ciphertext })
        for (envelope in first.envelopes) {
            val target = if (envelope.recipientDeviceUid == ownUid) identity
                else local(peer, peerUid, 5)
            val header = requireNotNull(envelope.initialHeader)
            assertEquals(target.signedPrekey.id, header.getInt("target_signed_prekey_id"))
            val senderIdentity = CryptoV1.parseB64(header.getString("sender_identity_dh_key"), 32)
            val ephemeral = CryptoV1.parseB64(header.getString("ephemeral_key"), 32)
            val secret = E2eeSessionCrypto.recipientSharedSecret(
                target.dhSeed, target.signedPrekey.seed, null,
                senderIdentity, ephemeral)
            val root = E2eeSessionCrypto.deriveRoot(secret)
            secret.fill(0)
            val (key, _) = E2eeSessionCrypto.step(root, 0)
            val plaintext = E2eeSessionCrypto.open(
                key, E2eeSessionCrypto.Sealed(
                    CryptoV1.parseB64(envelope.nonce, 12),
                    java.util.Base64.getUrlDecoder().decode(envelope.ciphertext)),
                E2eeSessionCrypto.aad(ownUid, envelope.recipientDeviceUid,
                    envelope.sessionId, 0))
            assertArrayEquals("hello".toByteArray(), plaintext)
            root.fill(0); key.fill(0)
        }
        assertThrows(Exception::class.java) {
            E2eeAtomicBootstrap.prepare(restored, identity, verified, frontId, "changed".toByteArray())
        }
        assertEquals(2, restored.getJSONObject("sessions").length())
    }

    @Test fun reusedSessionsPreserveInitialHeadersUntilServerAcknowledges() {
        val (identity, verified) = fixture()
        val state = E2eeSessionLedger.empty(owner, ownUid)
        val firstId = UUID.randomUUID().toString()
        val first = E2eeAtomicBootstrap.prepare(state, identity, verified, firstId, byteArrayOf(1))
        val secondId = UUID.randomUUID().toString()
        val second = E2eeAtomicBootstrap.prepare(state, identity, verified, secondId, byteArrayOf(2))
        assertEquals(2, state.getJSONObject("sessions").length())
        assertTrue(second.envelopes.all { it.counter == 1L && it.initialHeader != null })
        assertEquals(first.envelopes.map { it.sessionId }, second.envelopes.map { it.sessionId })
        assertTrue(E2eeAtomicBootstrap.acknowledge(state, firstId))
        E2eeSessionLedger.validate(state, owner, ownUid)
        val third = E2eeAtomicBootstrap.prepare(state, identity, verified,
            UUID.randomUUID().toString(), byteArrayOf(3))
        assertTrue(third.envelopes.all { it.counter == 2L && it.initialHeader == null })
    }

    @Test fun changedIdentityAndIncompleteTargetsFailClosed() {
        val (identity, verified) = fixture()
        val state = E2eeSessionLedger.empty(owner, ownUid)
        E2eeAtomicBootstrap.prepare(state, identity, verified, UUID.randomUUID().toString(),
            byteArrayOf(1))
        val replacement = local(peer, peerUid, 10)
        val changed = verified.copy(peerClaimed = mapOf(peerUid to checked(replacement)))
        assertThrows(Exception::class.java) {
            E2eeAtomicBootstrap.prepare(JSONObject(state.toString()), identity, changed,
                UUID.randomUUID().toString(), byteArrayOf(1))
        }
        val missing = verified.copy(peerClaimed = emptyMap())
        assertThrows(Exception::class.java) {
            E2eeAtomicBootstrap.prepare(JSONObject(state.toString()), identity, missing,
                UUID.randomUUID().toString(), byteArrayOf(1))
        }
        assertThrows(Exception::class.java) {
            E2eeAtomicBootstrap.prepare(JSONObject(state.toString()), local(owner, ownUid, 99),
                verified, UUID.randomUUID().toString(), byteArrayOf(1))
        }
    }

    @Test fun lostHttpResponseKeepsExactCiphertextAndCountersAcrossRestart() {
        val (identity, verified) = fixture()
        val state = E2eeSessionLedger.empty(owner, ownUid)
        val frontId = UUID.randomUUID().toString()
        val prepared = E2eeAtomicBootstrap.prepare(
            state, identity, verified, frontId, "retry me".toByteArray()
        )
        // The HTTP request may have been accepted by the server, but the
        // response was lost. No local acknowledgement is allowed here.
        val committed = state.toString()
        val restarted = JSONObject(committed)
        val pending = E2eeFanoutBatch.load(restarted, frontId)
        assertEquals(prepared.requestBody().toString(), pending.requestBody().toString())
        assertEquals(2, restarted.getJSONObject("pending").length())
        assertEquals(1, restarted.getJSONObject("batches").length())
        val sessionsBeforeRetry = restarted.getJSONObject("sessions").toString()
        assertThrows(Exception::class.java) {
            E2eeAtomicBootstrap.prepare(
                restarted, identity, verified, frontId, "retry me".toByteArray()
            )
        }
        assertEquals(committed, restarted.toString())
        assertEquals(sessionsBeforeRetry, restarted.getJSONObject("sessions").toString())
        // An authenticated exact-frontId server acceptance is the only path
        // that may release the durable envelopes and confirm initial headers.
        assertTrue(E2eeAtomicBootstrap.acknowledge(restarted, frontId))
        assertEquals(0, restarted.getJSONObject("pending").length())
        assertEquals(0, restarted.getJSONObject("batches").length())
        for (envelope in prepared.envelopes) {
            val session = restarted.getJSONObject("sessions").getJSONObject(
                "out:" + envelope.recipientDeviceUid + ":" + envelope.sessionId
            )
            assertTrue(session.getBoolean("header_confirmed"))
        }
        E2eeSessionLedger.validate(restarted, owner, ownUid)
    }
}
