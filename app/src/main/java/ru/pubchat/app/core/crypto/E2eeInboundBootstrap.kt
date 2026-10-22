package ru.pubchat.app.core.crypto

import org.json.JSONObject
import java.util.UUID

/**
 * Inbound Messenger E2EE v1 cryptographic boundary.
 *
 * The caller must supply a fresh, continuity-checked sender directory, not
 * untrusted sender keys from the wire. First-message 3DH and AEAD decryption
 * modify only the caller's in-memory state; E2eeSessionStore commits the
 * entire snapshot before returning plaintext to the application.
 */
internal object E2eeInboundBootstrap {
    data class Envelope(
        val senderDeviceUid: String,
        val recipientDeviceUid: String,
        val sessionId: String,
        val counter: Long,
        val nonce: String,
        val ciphertext: String,
        val initialHeader: JSONObject?,
    )

    private fun canonical(value: String): String =
        UUID.fromString(value).toString().also { require(it == value) { "Noncanonical UUID" } }

    fun parseEnvelope(raw: JSONObject): Envelope {
        val sender = canonical(raw.getString("sender_device_uid"))
        val recipient = canonical(raw.getString("recipient_device_uid"))
        val session = canonical(raw.getString("session_id"))
        val counter = raw.getLong("counter")
        require(counter in 0..2_147_483_647L)
        CryptoV1.parseB64(raw.getString("nonce"), 12)
        val encoded = raw.getString("ciphertext")
        val cipher = java.util.Base64.getUrlDecoder().decode(encoded)
        require(cipher.size >= 16 && CryptoV1.b64(cipher) == encoded)
        val header = raw.optJSONObject("initial_header")
        require(raw.isNull("initial_header") || header != null) {
            "Invalid initial E2EE header"
        }
        return Envelope(sender, recipient, session, counter,
            raw.getString("nonce"), encoded, header)
    }

    fun decrypt(
        state: JSONObject,
        ownIdentity: CryptoDeviceMaterial,
        senderDirectory: PeerDirectoryInspection,
        envelope: Envelope,
    ): ByteArray {
        val ownDevice = canonical(ownIdentity.deviceUid)
        require(envelope.recipientDeviceUid == ownDevice) { "Wrong recipient device" }
        require(!senderDirectory.requiresAcknowledgement) {
            "Sender device directory changed without acknowledgement"
        }
        val trustedSender = requireNotNull(senderDirectory.devices[envelope.senderDeviceUid]) {
            "Unknown or revoked sender device"
        }
        val sessions = state.getJSONObject("sessions")
        val sessionKey = "in:" + envelope.senderDeviceUid + ":" + envelope.sessionId
        if (!sessions.has(sessionKey)) {
            val header = requireNotNull(envelope.initialHeader) {
                "First ciphertext requires authenticated 3DH initial header"
            }
            require(envelope.counter == 0L) { "New session must start at counter zero" }
            val senderDh = CryptoV1.parseB64(header.getString("sender_identity_dh_key"), 32)
            val senderSigning = CryptoV1.parseB64(header.getString("sender_identity_signing_key"), 32)
            require(senderDh.contentEquals(trustedSender.identityDhKey) &&
                senderSigning.contentEquals(trustedSender.signingKey)) {
                "Sender initial header substituted identity keys"
            }
            val ephemeral = CryptoV1.parseB64(header.getString("ephemeral_key"), 32)
            val signedId = header.getInt("target_signed_prekey_id")
            require(signedId == ownIdentity.signedPrekey.id) {
                "Target signed prekey unavailable: never silently substitute rotated key"
            }
            val oneTime = if (header.isNull("target_one_time_prekey_id")) null
                else header.getInt("target_one_time_prekey_id")
            require(oneTime == null || oneTime >= 0)
            val consumed = state.optJSONObject("consumed_one_time_prekeys")
                ?: JSONObject().also { state.put("consumed_one_time_prekeys", it) }
            require(oneTime == null || !consumed.has(oneTime.toString())) {
                "One-time prekey was already consumed"
            }
            val oneTimeSeed = oneTime?.let { id ->
                requireNotNull(ownIdentity.oneTimePrekeys.singleOrNull { it.id == id }) {
                    "Target one-time prekey is unavailable"
                }.seed
            }
            val shared = E2eeSessionCrypto.recipientSharedSecret(
                ownIdentity.dhSeed, ownIdentity.signedPrekey.seed, oneTimeSeed,
                senderDh, ephemeral,
            )
            val root = try {
                E2eeSessionCrypto.deriveRoot(shared)
            } finally { shared.fill(0) }
            try {
                E2eeSessionLedger.addSession(
                    state, "in", envelope.senderDeviceUid, envelope.sessionId, root,
                )
            } finally { root.fill(0) }
            val newSession = sessions.getJSONObject(sessionKey)
            newSession.put("sender_identity_dh", CryptoV1.b64(senderDh))
                .put("sender_identity_signing", CryptoV1.b64(senderSigning))
                .put("sender_account_uid", JSONObject.NULL)
            // This reservation becomes durable in the same snapshot as the
            // authenticated receive ratchet transition.
            if (oneTime != null) consumed.put(oneTime.toString(), sessionKey)
        } else {
            val existing = sessions.getJSONObject(sessionKey)
            require(existing.getString("sender_identity_dh") ==
                CryptoV1.b64(trustedSender.identityDhKey) &&
                existing.getString("sender_identity_signing") ==
                CryptoV1.b64(trustedSender.signingKey)) {
                "Sender identity changed during an existing session"
            }
            // No unauthenticated session replacement, regardless of header.
            if (envelope.initialHeader != null) {
                val header = envelope.initialHeader
                require(header.getString("sender_identity_dh_key") ==
                    existing.getString("sender_identity_dh") &&
                    header.getString("sender_identity_signing_key") ==
                    existing.getString("sender_identity_signing"))
            }
        }
        return E2eeSessionLedger.decryptNext(
            state, envelope.senderDeviceUid, envelope.recipientDeviceUid,
            envelope.sessionId, envelope.counter, envelope.nonce, envelope.ciphertext,
        )
    }
}
