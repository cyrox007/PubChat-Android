package ru.pubchat.app.core.crypto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class E2eeInboundBootstrapTest {
    private val accountA = "11111111-1111-4111-8111-111111111111"
    private val accountB = "22222222-2222-4222-8222-222222222222"
    private val deviceA = "33333333-3333-4333-8333-333333333333"
    private val deviceB = "44444444-4444-4444-8444-444444444444"
    private val session = "55555555-5555-4555-8555-555555555555"
    private val checkpoint = DeviceDirectoryCheckpoint(1, "a".repeat(64), "0".repeat(64), "b".repeat(64))

    private fun material(account: String, device: String, offset: Int) = CryptoDeviceMaterial(
        account, device,
        ByteArray(32) { (it + offset).toByte() },
        ByteArray(32) { (it + offset + 32).toByte() },
        CryptoPreKey(11 + offset, ByteArray(32) { (it + offset + 64).toByte() }),
        listOf(CryptoPreKey(100 + offset, ByteArray(32) { (it + offset + 96).toByte() })),
    )

    private fun inspected(sender: CryptoDeviceMaterial): PeerDirectoryInspection {
        val raw = JSONObject().put("status", "ok").put("account_uid", sender.accountUid)
            .put("devices", JSONArray().put(sender.registrationPayload().put("fingerprint", sender.fingerprint)))
        return PeerDirectoryInspection(checkpoint,
            CryptoPeerPrekeyValidation.directory(raw.toString(), sender.accountUid), false)
    }

    private fun inbound(
        sender: CryptoDeviceMaterial, recipient: CryptoDeviceMaterial,
        counter: Long = 0, useOneTime: Boolean = true,
    ): E2eeInboundBootstrap.Envelope {
        val remote = inspected(recipient).devices.getValue(recipient.deviceUid)
        val claimed = remote.copy(
            oneTimePrekeyId = if (useOneTime) recipient.oneTimePrekeys.single().id else null,
            oneTimePrekey = if (useOneTime) CryptoV1.xPublic(recipient.oneTimePrekeys.single().seed) else null,
        )
        val ephemeral = ByteArray(32) { (it + 13).toByte() }
        val secret = E2eeSessionCrypto.senderSharedSecret(sender.dhSeed, ephemeral, claimed)
        val root = try { E2eeSessionCrypto.deriveRoot(secret) } finally { secret.fill(0) }
        var chain = root
        var messageKey = ByteArray(0)
        for (n in 0..counter) {
            val (key, next) = E2eeSessionCrypto.step(chain, n)
            if (chain !== root) chain.fill(0)
            if (messageKey.isNotEmpty()) messageKey.fill(0)
            chain = next
            messageKey = key
        }
        val sealed = E2eeSessionCrypto.seal(messageKey, "hello $counter".toByteArray(),
            E2eeSessionCrypto.aad(sender.deviceUid, recipient.deviceUid, session, counter))
        val header = JSONObject().put("ephemeral_key", CryptoV1.b64(CryptoV1.xPublic(ephemeral)))
            .put("target_signed_prekey_id", recipient.signedPrekey.id)
            .put("target_one_time_prekey_id", if (useOneTime) recipient.oneTimePrekeys.single().id else JSONObject.NULL)
            .put("sender_identity_dh_key", sender.dhPublic)
            .put("sender_identity_signing_key", sender.signingPublic)
        chain.fill(0); root.fill(0); messageKey.fill(0); ephemeral.fill(0)
        return E2eeInboundBootstrap.Envelope(sender.deviceUid, recipient.deviceUid,
            session, counter, CryptoV1.b64(sealed.nonce), CryptoV1.b64(sealed.ciphertext),
            if (counter == 0L) header else null)
    }

    @Test fun validFirstMessageCreatesSessionAndConsumesOneTimeKeyAtomically() {
        val sender = material(accountA, deviceA, 1)
        val recipient = material(accountB, deviceB, 5)
        val state = E2eeSessionLedger.empty(accountB, deviceB)
        val first = inbound(sender, recipient)
        val output = E2eeInboundBootstrap.decrypt(state, recipient, inspected(sender), first)
        assertArrayEquals("hello 0".toByteArray(), output)
        E2eeSessionLedger.validate(state, accountB, deviceB)
        val restored = JSONObject(state.toString())
        assertEquals(1, restored.getJSONObject("consumed_one_time_prekeys").length())
        assertThrows(Exception::class.java) {
            E2eeInboundBootstrap.decrypt(restored, recipient, inspected(sender), first)
        }
        val second = inbound(sender, recipient, 1)
        assertArrayEquals("hello 1".toByteArray(),
            E2eeInboundBootstrap.decrypt(restored, recipient, inspected(sender), second))
        E2eeSessionLedger.validate(restored, accountB, deviceB)
        assertEquals(2L, restored.getJSONObject("sessions")
            .getJSONObject("in:$deviceA:$session").getLong("next"))
    }

    @Test fun authenticationFailureDoesNotCommitUntrustedBootstrap() {
        val sender = material(accountA, deviceA, 1)
        val recipient = material(accountB, deviceB, 5)
        val state = E2eeSessionLedger.empty(accountB, deviceB)
        val first = inbound(sender, recipient)
        val damaged = first.copy(ciphertext = CryptoV1.b64(
            java.util.Base64.getUrlDecoder().decode(first.ciphertext).apply {
                this[0] = (this[0].toInt() xor 1).toByte()
            }))
        val copy = JSONObject(state.toString())
        assertThrows(Exception::class.java) {
            E2eeInboundBootstrap.decrypt(copy, recipient, inspected(sender), damaged)
        }
        // E2eeSessionStore's atomic mutate must discard the entire copy on error.
        assertEquals(0, state.getJSONObject("sessions").length())
        assertArrayEquals("hello 0".toByteArray(),
            E2eeInboundBootstrap.decrypt(state, recipient, inspected(sender), first))
    }

    @Test fun identitySubstitutionAndWrongRecipientRejectBeforeDecryption() {
        val sender = material(accountA, deviceA, 1)
        val recipient = material(accountB, deviceB, 5)
        val first = inbound(sender, recipient)
        assertThrows(Exception::class.java) {
            val tampered = first.copy(initialHeader =
                JSONObject(first.initialHeader!!.toString())
                    .put("sender_identity_dh_key", CryptoV1.b64(ByteArray(32))))
            E2eeInboundBootstrap.decrypt(E2eeSessionLedger.empty(accountB, deviceB),
                recipient, inspected(sender), tampered)
        }
        assertThrows(Exception::class.java) {
            E2eeInboundBootstrap.decrypt(E2eeSessionLedger.empty(accountB, deviceB),
                recipient, inspected(sender).copy(requiresAcknowledgement = true), first)
        }
        assertThrows(Exception::class.java) {
            E2eeInboundBootstrap.decrypt(E2eeSessionLedger.empty(accountB, deviceB),
                recipient, inspected(sender), first.copy(recipientDeviceUid = UUID.randomUUID().toString()))
        }
        assertThrows(Exception::class.java) {
            E2eeInboundBootstrap.decrypt(E2eeSessionLedger.empty(accountB, deviceB),
                recipient, inspected(sender).copy(devices = emptyMap()), first)
        }
        assertThrows(Exception::class.java) {
            E2eeInboundBootstrap.decrypt(E2eeSessionLedger.empty(accountB, deviceB),
                recipient, inspected(sender), first.copy(initialHeader = null))
        }
    }

    @Test fun rejectsCounterSkipAndCompromisedInitialPrekey() {
        val sender = material(accountA, deviceA, 1)
        val recipient = material(accountB, deviceB, 5)
        val first = inbound(sender, recipient)
        assertThrows(Exception::class.java) {
            E2eeInboundBootstrap.decrypt(E2eeSessionLedger.empty(accountB, deviceB),
                recipient, inspected(sender), first.copy(counter = 1))
        }
        assertThrows(Exception::class.java) {
            val broken = first.copy(initialHeader = JSONObject(first.initialHeader!!.toString())
                .put("target_one_time_prekey_id", 99999))
            E2eeInboundBootstrap.decrypt(E2eeSessionLedger.empty(accountB, deviceB),
                recipient, inspected(sender), broken)
        }
    }
}
