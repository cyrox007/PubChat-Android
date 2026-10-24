package ru.pubchat.app.core.crypto

import org.json.JSONObject
import java.util.UUID

/**
 * Offline atomic bootstrap: session roots, immutable first-message headers
 * and all device ciphertexts are persisted by ONE E2eeSessionStore mutation.
 */
internal object E2eeAtomicBootstrap {
    private fun uuid(s: String) = UUID.fromString(s).toString().also { require(it == s) }

    private fun existing(state: JSONObject, peer: VerifiedPeerPrekey): JSONObject? {
        val sessions = state.getJSONObject("sessions")
        val found = mutableListOf<JSONObject>()
        for (name in sessions.keys()) {
            val row = sessions.getJSONObject(name)
            if (row.getString("direction") == "out" && row.getString("peer") == peer.deviceUid) {
                found.add(row)
            }
        }
        require(found.size <= 1) { "Duplicate outbound sessions" }
        return found.singleOrNull()
    }

    private fun target(state: JSONObject, local: CryptoDeviceMaterial, peer: VerifiedPeerPrekey):
        E2eeFanoutBatch.Target {
        val old = existing(state, peer)
        if (old != null) {
            require(old.getString("peer_identity_dh") == CryptoV1.b64(peer.identityDhKey) &&
                old.getString("peer_signing") == CryptoV1.b64(peer.signingKey)) {
                "Peer identity changed; re-verification required"
            }
            val header = if (old.getBoolean("header_confirmed")) null
                else JSONObject(old.getString("initial_header"))
            return E2eeFanoutBatch.Target(peer.deviceUid, uuid(old.getString("session")), header)
        }
        val sessionId = UUID.randomUUID().toString()
        val ephemeral = CryptoV1.randomSeed()
        val secret = try {
            E2eeSessionCrypto.senderSharedSecret(local.dhSeed, ephemeral, peer)
        } finally {
            // Ephemeral public key must be derived before seed is destroyed.
        }
        val root = try {
            E2eeSessionCrypto.deriveRoot(secret)
        } finally {
            secret.fill(0)
        }
        val header = JSONObject()
            .put("ephemeral_key", CryptoV1.b64(CryptoV1.xPublic(ephemeral)))
            .put("target_signed_prekey_id", peer.signedPrekeyId)
            .put("target_one_time_prekey_id", peer.oneTimePrekeyId ?: JSONObject.NULL)
            .put("sender_identity_dh_key", local.dhPublic)
            .put("sender_identity_signing_key", local.signingPublic)
        ephemeral.fill(0)
        try {
            E2eeSessionLedger.addSession(state, "out", peer.deviceUid, sessionId, root)
            val row = state.getJSONObject("sessions").getJSONObject(
                "out:" + peer.deviceUid + ":" + sessionId)
            row.put("peer_identity_dh", CryptoV1.b64(peer.identityDhKey))
                .put("peer_signing", CryptoV1.b64(peer.signingKey))
                .put("initial_header", header.toString())
                .put("header_confirmed", false)
        } finally {
            root.fill(0)
        }
        return E2eeFanoutBatch.Target(peer.deviceUid, sessionId, header)
    }

    fun prepare(state: JSONObject, local: CryptoDeviceMaterial,
                verified: E2eeDevicePreflight.VerifiedTargets,
                frontId: String, plaintext: ByteArray,
                attachmentUid: String? = null): E2eeFanoutBatch.Batch {
        require(local.accountUid == verified.ownAccountUid &&
            local.deviceUid == verified.senderDeviceUid)
        val ownIdentity = requireNotNull(verified.own[local.deviceUid]) {
            "Sender is missing from verified own directory"
        }
        require(CryptoV1.b64(ownIdentity.signingKey) == local.signingPublic &&
            CryptoV1.b64(ownIdentity.identityDhKey) == local.dhPublic &&
            ownIdentity.signedPrekeyId == local.signedPrekey.id &&
            CryptoV1.b64(ownIdentity.signedPrekey) ==
                CryptoV1.b64(CryptoV1.xPublic(local.signedPrekey.seed))) {
            "Local Keystore identity or signed prekey changed since preflight"
        }
        require(verified.peerClaimed.keys == verified.peer.keys)
        for ((deviceUid, claimed) in verified.peerClaimed) {
            val pinned = requireNotNull(verified.peer[deviceUid])
            require(claimed.signingKey.contentEquals(pinned.signingKey) &&
                claimed.identityDhKey.contentEquals(pinned.identityDhKey) &&
                claimed.signedPrekeyId == pinned.signedPrekeyId &&
                claimed.signedPrekey.contentEquals(pinned.signedPrekey)) {
                "Claimed prekey no longer matches checked peer directory"
            }
        }
        require(verified.own.keys == verified.own.values.map { it.deviceUid }.toSet())
        require(verified.peer.keys == verified.peerClaimed.keys)
        require(!(state.optJSONObject("batches")?.has(uuid(frontId)) ?: false)) {
            "Duplicate frontId; reload exact committed ciphertext"
        }
        val own = verified.own.values.sortedBy { it.deviceUid }.map { target(state, local, it) }
        val peer = verified.peerClaimed.values.sortedBy { it.deviceUid }.map { target(state, local, it) }
        return E2eeFanoutBatch.prepare(state, frontId, local.deviceUid,
            verified.peerAccountUid, own, peer, plaintext, attachmentUid)
    }

    fun acknowledge(state: JSONObject, frontId: String): Boolean {
        val batch = E2eeFanoutBatch.load(state, uuid(frontId))
        require(E2eeFanoutBatch.acknowledge(state, frontId))
        val sessions = state.getJSONObject("sessions")
        for (envelope in batch.envelopes) {
            if (envelope.initialHeader == null) continue
            val row = sessions.getJSONObject("out:" + envelope.recipientDeviceUid +
                ":" + envelope.sessionId)
            require(row.getString("initial_header") == envelope.initialHeader.toString()) {
                "Initial header changed after ciphertext reservation"
            }
            row.put("header_confirmed", true)
        }
        return true
    }
}
