package ru.pubchat.app.core.network

import org.json.JSONObject
import ru.pubchat.app.core.crypto.CryptoDeviceStore
import ru.pubchat.app.core.crypto.E2eeDevicePreflight
import ru.pubchat.app.core.crypto.PeerDirectoryContinuity
import ru.pubchat.app.core.crypto.PeerDirectoryInspection
import ru.pubchat.app.core.crypto.PeerDirectoryTrustStore

/**
 * Authenticated, fail-closed preflight before any new 3DH session bootstrap.
 * Never uses unconfirmed directory changes or implicitly creates an identity.
 * This is not a Messenger UI send path.
 */
internal class E2eeTrustedDirectoryLoader(
    private val api: PubChatApi,
    private val identityStore: CryptoDeviceStore,
    private val directoryStore: PeerDirectoryTrustStore,
) {
    private fun checked(owner: String, account: String): PeerDirectoryInspection {
        val previous = directoryStore.read(owner, account)
        val response = api.peerCryptoDirectory(account)
        val current = PeerDirectoryContinuity.checkpoint(
            JSONObject(response).getJSONObject("directory_checkpoint"))
        val history = if (previous != null && current.sequence > previous.checkpoint.sequence) {
            api.peerCryptoDirectoryHistory(account, previous.checkpoint.sequence)
        } else null
        return PeerDirectoryContinuity.inspect(response, history, account, previous).also {
            require(!it.requiresAcknowledgement) {
                "Active device directory changed; explicit user acknowledgement required"
            }
        }
    }

    /**
     * First read and validate BOTH directories, then claim the peer's one-time
     * prekeys, validate all signatures/identities and re-read BOTH directories.
     * Store the newer trusted checkpoints only if all checks succeed.
     */
    fun load(ownerAccountUid: String, peerAccountUid: String):
        E2eeDevicePreflight.VerifiedTargets {
        val local = requireNotNull(identityStore.read(ownerAccountUid)) {
            "Local crypto-device identity unavailable; do not regenerate automatically"
        }
        val own = checked(ownerAccountUid, ownerAccountUid)
        val peer = checked(ownerAccountUid, peerAccountUid)
        val claimed = api.claimPeerCryptoPrekeyBundle(peerAccountUid)
        val preflight = E2eeDevicePreflight.verify(
            ownerAccountUid, peerAccountUid, local, own, peer, claimed)
        val refreshedOwn = checked(ownerAccountUid, ownerAccountUid)
        val refreshedPeer = checked(ownerAccountUid, peerAccountUid)
        E2eeDevicePreflight.checkUnchanged(preflight, refreshedOwn, refreshedPeer)
        // Persist continuity only AFTER bundle and final cross-directory validation.
        // Save an Account-specific trust anchor. Storage failure stops the send.
        directoryStore.save(ownerAccountUid,
            PeerDirectoryContinuity.trust(ownerAccountUid, refreshedOwn))
        directoryStore.save(ownerAccountUid,
            PeerDirectoryContinuity.trust(peerAccountUid, refreshedPeer))
        return preflight
    }

    /** Used immediately before POST to reject server-side device changes. */
    fun refreshForSend(prepared: E2eeDevicePreflight.VerifiedTargets):
        Pair<PeerDirectoryInspection, PeerDirectoryInspection> {
        val own = checked(prepared.ownAccountUid, prepared.ownAccountUid)
        val peer = checked(prepared.ownAccountUid, prepared.peerAccountUid)
        E2eeDevicePreflight.checkUnchanged(prepared, own, peer)
        return own to peer
    }
}
