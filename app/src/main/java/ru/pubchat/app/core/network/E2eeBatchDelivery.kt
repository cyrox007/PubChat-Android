package ru.pubchat.app.core.network

import org.json.JSONObject
import ru.pubchat.app.core.crypto.E2eeSessionStore
import ru.pubchat.app.core.crypto.E2eeDevicePreflight
import ru.pubchat.app.core.crypto.PeerDirectoryInspection

/** Fail closed if a conversation changed away from authenticated E2EE before transport. */
internal fun requireE2eeDeliveryPolicy(policy: MessengerEncryptionStatus) {
    check(policy.effectiveMode == ConversationEffectiveMode.E2EE && policy.cryptoReady) {
        "Encrypted delivery blocked: server no longer confirms E2EE readiness"
    }
}

/**
 * No UI caller until verified device-directory preflight, signed-prekey
 * bootstrap, history recovery and device-revocation handling are integrated.
 *
 * An ambiguous HTTP failure keeps the durable batch unacknowledged. Never
 * generate new envelopes for the same frontId after a failed request.
 */
internal class E2eeBatchDelivery(
    private val api: PubChatApi,
    private val store: E2eeSessionStore,
) {
    fun submitPending(
        ownerAccountUid: String,
        localDeviceUid: String,
        frontId: String,
        prepared: E2eeDevicePreflight.VerifiedTargets,
        loadFreshCheckedDirectories: () -> Pair<PeerDirectoryInspection, PeerDirectoryInspection>,
    ): JSONObject {
        // Read and verify the already committed ciphertext before any network IO.
        val batch = store.pendingBatch(ownerAccountUid, localDeviceUid, frontId)
        require(batch.senderDeviceUid == localDeviceUid) { "Sender device does not match store" }
        // Must check both Account directories, key identities and exact
        // fan-out target membership immediately before transport.
        val (freshOwn, freshPeer) = loadFreshCheckedDirectories()
        E2eeDevicePreflight.recheck(prepared, freshOwn, freshPeer, batch)
        // A device-directory check alone cannot establish current conversation policy.
        // Never submit ciphertext after the server has disabled or withdrawn E2EE readiness.
        requireE2eeDeliveryPolicy(api.messengerEncryption(batch.receiverUid))
        val message = api.sendCommittedE2eeBatch(batch)
        check(store.acknowledgeBootstrappedBatch(ownerAccountUid, localDeviceUid, frontId)) {
            "Server accepted batch but local ACK persistence failed: reconcile state"
        }
        return message
    }
}
