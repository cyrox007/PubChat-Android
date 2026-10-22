package ru.pubchat.app.core.crypto

import java.util.UUID

/**
 * Offline, fail-closed boundary between checked Account directories / signed
 * prekey bundles and a multi-device encrypted send. Does not claim that a
 * remote identity is safety-number verified or that the server is a witness.
 */
internal object E2eeDevicePreflight {
    data class VerifiedTargets(
        val ownAccountUid: String,
        val peerAccountUid: String,
        val senderDeviceUid: String,
        val own: Map<String, VerifiedPeerPrekey>,
        val peer: Map<String, VerifiedPeerPrekey>,
        val peerClaimed: Map<String, VerifiedPeerPrekey>,
        val ownCheckpoint: DeviceDirectoryCheckpoint,
        val peerCheckpoint: DeviceDirectoryCheckpoint,
    ) {
        val requiredDeviceUids: Set<String>
            get() = own.keys + peer.keys
    }

    private fun canonical(raw: String): String =
        UUID.fromString(raw).toString().also { require(raw == it) { "Noncanonical Account/device UID" } }

    fun verify(
        ownerAccountUid: String,
        peerAccountUid: String,
        localIdentity: CryptoDeviceMaterial,
        ownInspection: PeerDirectoryInspection,
        peerInspection: PeerDirectoryInspection,
        peerBundleResponse: String,
    ): VerifiedTargets {
        val owner = canonical(ownerAccountUid)
        val peerUid = canonical(peerAccountUid)
        require(owner != peerUid) { "Self-conversation requires separate protocol review" }
        require(localIdentity.accountUid == owner)
        require(canonical(localIdentity.deviceUid) == localIdentity.deviceUid)
        require(!ownInspection.requiresAcknowledgement && !peerInspection.requiresAcknowledgement) {
            "Device directory changed: user acknowledgment required"
        }
        val own = ownInspection.devices
        val peer = peerInspection.devices
        require(own.isNotEmpty() && peer.isNotEmpty())
        val activeCount = own.size + peer.size
        require(activeCount in 2..40) { "All-device fanout exceeds server envelope cap" }
        require((own.keys intersect peer.keys).isEmpty()) { "Device assigned to two accounts" }

        val local = own[localIdentity.deviceUid]
            ?: throw IllegalArgumentException("Local crypto device not registered in active directory")
        require(
            CryptoV1.b64(local.signingKey) == localIdentity.signingPublic &&
                CryptoV1.b64(local.identityDhKey) == localIdentity.dhPublic
        ) { "Local cryptographic identity mismatches active directory" }
        // Local signed prekey may rotate but cannot be silently substituted
        // with a signed key unknown to the currently persisted identity.
        val registered = localIdentity.registrationPayload().getJSONObject("signed_prekey")
        require(local.signedPrekeyId == registered.getInt("id") &&
            CryptoV1.b64(local.signedPrekey) == registered.getString("public_key")) {
            "Local signed prekey differs from durable local identity"
        }

        val claimed = CryptoPeerPrekeyValidation.bundle(peerBundleResponse, peerUid, peer)
        require(claimed.size == peer.size)
        val claimedByUid = claimed.associateBy { it.deviceUid }
        require(claimedByUid.size == peer.size && claimedByUid.keys == peer.keys)
        return VerifiedTargets(owner, peerUid, localIdentity.deviceUid, own, peer,
            claimedByUid, ownInspection.checkpoint, peerInspection.checkpoint)
    }

    /**
     * Call with fresh, checked directory inspections immediately before POST.
     * The sender must use exactly the target set captured by this preflight.
     * A rotated signed prekey, revoked/added device, or fork stops the send.
     */
    fun recheck(
        prepared: VerifiedTargets,
        freshOwn: PeerDirectoryInspection,
        freshPeer: PeerDirectoryInspection,
        batch: E2eeFanoutBatch.Batch,
    ) {
        require(!freshOwn.requiresAcknowledgement && !freshPeer.requiresAcknowledgement)
        require(batch.senderDeviceUid == prepared.senderDeviceUid &&
            batch.receiverUid == prepared.peerAccountUid)
        require(freshOwn.checkpoint == prepared.ownCheckpoint &&
            freshPeer.checkpoint == prepared.peerCheckpoint) {
            "Directory changed since encryption preparation"
        }
        require(samePublicKeys(prepared.own, freshOwn.devices) &&
            samePublicKeys(prepared.peer, freshPeer.devices)) {
            "Cryptographic identity changed before encrypted send"
        }
        val envelopeUids = batch.envelopes.map { it.recipientDeviceUid }
        require(envelopeUids.size == prepared.requiredDeviceUids.size &&
            envelopeUids.toSet() == prepared.requiredDeviceUids) {
            "Encrypted packet missing an active device or contains an unknown target"
        }
    }

    private fun samePublicKeys(
        first: Map<String, VerifiedPeerPrekey>,
        second: Map<String, VerifiedPeerPrekey>,
    ): Boolean {
        if (first.keys != second.keys) return false
        return first.all { (uid, item) ->
            val other = second[uid] ?: return@all false
            item.signedPrekeyId == other.signedPrekeyId &&
                item.signingKey.contentEquals(other.signingKey) &&
                item.identityDhKey.contentEquals(other.identityDhKey) &&
                item.signedPrekey.contentEquals(other.signedPrekey)
        }
    }
}
