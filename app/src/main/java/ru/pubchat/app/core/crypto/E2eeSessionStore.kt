package ru.pubchat.app.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed, no-backup, atomic E2EE state per Account + cryptographic device.
 *
 * Never return a new packet or plaintext until the updated chain and packet
 * have durably committed. If storage, wrapping key or decryption fails, fail
 * closed. Do NOT silently reset missing/corrupt state on login.
 *
 * Single-process only; future multi-process workers must use a file lock or
 * a transactional database with equivalent cross-process serialization.
 */
internal class E2eeSessionStore(context: Context) {
    private val app = context.applicationContext
    private val alias = "pubchat_e2ee_session_wrap_v1"

    companion object {
        private val processLock = Any()
    }

    private fun file(account: String, device: String): AtomicFile {
        val canonical = UUID.fromString(account).toString() + ":" + UUID.fromString(device).toString()
        val suffix = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(app.noBackupFilesDir, "e2ee_session_v1_" + suffix + ".bin"))
    }

    private fun aad(account: String, device: String): ByteArray =
        ("pubchat-e2ee-session-state-v1:" + UUID.fromString(account).toString() +
            ":" + UUID.fromString(device).toString()).toByteArray(Charsets.UTF_8)

    private fun wrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    private fun read(account: String, device: String): JSONObject {
        val target = file(account, device)
        require(target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) {
            "E2EE session state missing; explicit recovery required"
        }
        val outer = JSONObject(String(target.readFully(), Charsets.UTF_8))
        require(outer.getInt("version") == 1)
        val nonce = CryptoV1.parseB64(outer.getString("iv"), 12)
        val ciphertext = outer.getString("data")
        val data = java.util.Base64.getUrlDecoder().decode(ciphertext)
        require(data.size >= 16 && CryptoV1.b64(data) == ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(account, device))
        val plaintext = cipher.doFinal(data)
        return try {
            JSONObject(String(plaintext, Charsets.UTF_8)).also {
                E2eeSessionLedger.validate(it, account, device)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun persist(account: String, device: String, state: JSONObject) {
        E2eeSessionLedger.validate(state, account, device)
        val plaintext = state.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val ciphertext = try {
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
            cipher.updateAAD(aad(account, device))
            cipher.doFinal(plaintext)
        } finally { plaintext.fill(0) }
        val wrapped = JSONObject().put("version", 1)
            .put("iv", CryptoV1.b64(cipher.iv))
            .put("data", CryptoV1.b64(ciphertext))
            .toString().toByteArray(Charsets.UTF_8)
        val target = file(account, device)
        val stream = target.startWrite()
        try {
            stream.write(wrapped)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    /** Explicit setup only, after server-confirmed crypto device registration. */
    fun initialize(account: String, device: String) = synchronized(processLock) {
        val target = file(account, device)
        check(!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) {
            "Existing E2EE session state must not be overwritten"
        }
        persist(account, device, E2eeSessionLedger.empty(account, device))
    }

    private fun <T> mutate(account: String, device: String, change: (JSONObject) -> T): T =
        synchronized(processLock) {
            val state = read(account, device)
            val result = change(state)
            persist(account, device, state)
            result
        }

    fun addSession(account: String, device: String, direction: String, peerDevice: String,
                   sessionId: String, chain: ByteArray) {
        mutate(account, device) { state ->
            E2eeSessionLedger.addSession(state, direction, peerDevice, sessionId, chain)
        }
    }

    /**
     * Returned packet is durably saved together with the advanced counter.
     * Only this exact ciphertext may be retried after a crash.
     */
    fun reserveOutbound(account: String, device: String, frontId: String,
                        recipientDevice: String, sessionId: String, plaintext: ByteArray):
        E2eeSessionLedger.PendingPacket =
        mutate(account, device) { state ->
            E2eeSessionLedger.reserve(state, frontId, device, recipientDevice, sessionId, plaintext)
        }

    /**
     * One durable transaction for ALL own and peer active devices. Never send
     * partial envelopes; callers must inspect trusted directories beforehand.
     */
    fun prepareBatch(
        account: String,
        device: String,
        frontId: String,
        receiverUid: String,
        ownDevices: List<E2eeFanoutBatch.Target>,
        peerDevices: List<E2eeFanoutBatch.Target>,
        plaintext: ByteArray,
    ): E2eeFanoutBatch.Batch = mutate(account, device) { state ->
        E2eeFanoutBatch.prepare(state, frontId, device, receiverUid, ownDevices, peerDevices, plaintext)
    }

    /** New 3DH sessions + headers + first encrypted batch, one durable transaction. */
    fun bootstrapAndPrepareBatch(
        account: String, device: String,
        local: CryptoDeviceMaterial,
        verified: E2eeDevicePreflight.VerifiedTargets,
        frontId: String, plaintext: ByteArray,
    ): E2eeFanoutBatch.Batch = mutate(account, device) { state ->
        require(account == local.accountUid && device == local.deviceUid)
        E2eeAtomicBootstrap.prepare(state, local, verified, frontId, plaintext)
    }

    /** Confirm first headers only together with the entire server-accepted batch. */
    fun acknowledgeBootstrappedBatch(account: String, device: String, frontId: String): Boolean =
        mutate(account, device) { state -> E2eeAtomicBootstrap.acknowledge(state, frontId) }

    /** Distinguish an absent batch from corrupted durable state; never regenerate on read failure. */
    fun hasPendingBatch(account: String, device: String, frontId: String): Boolean =
        synchronized(processLock) {
            read(account, device).optJSONObject("batches")?.has(frontId) == true
        }

    /** Durable recovery index, containing IDs only; plaintext is never stored in UI state. */
    fun pendingBatchIdsForPeer(account: String, device: String, peer: String): List<String> =
        synchronized(processLock) {
            E2eeFanoutBatch.pendingIdsForPeer(read(account, device), peer)
        }

    /** Always re-read the exact committed envelopes before any HTTP retry. */
    fun pendingBatch(account: String, device: String, frontId: String): E2eeFanoutBatch.Batch =
        synchronized(processLock) { E2eeFanoutBatch.load(read(account, device), frontId) }

    /** One server ACK removes every packet of this batch in a single durable write. */
    fun acknowledgeBatch(account: String, device: String, frontId: String): Boolean =
        mutate(account, device) { state -> E2eeFanoutBatch.acknowledge(state, frontId) }

    fun pendingPackets(account: String, device: String): List<E2eeSessionLedger.PendingPacket> =
        synchronized(processLock) { E2eeSessionLedger.pending(read(account, device)) }

    fun acknowledge(account: String, device: String, frontId: String): Boolean =
        mutate(account, device) { state -> E2eeSessionLedger.acknowledge(state, frontId) }

    /**
     * Plaintext is returned only after the authenticated counter transition
     * has committed. No automatic replay of old or skipped counters.
     */
    fun decryptNext(account: String, device: String, sender: String, sessionId: String,
                    counter: Long, nonce: String, ciphertext: String): ByteArray =
        mutate(account, device) { state ->
            E2eeSessionLedger.decryptNext(state, sender, device, sessionId, counter, nonce, ciphertext)
        }

    /**
     * First inbound 3DH + authenticated ciphertext + consumed OTK marker +
     * advanced chain are committed together before plaintext is returned.
     * Caller must have checked sender's latest directory / history.
     */
    fun decryptInbound(
        account: String,
        device: String,
        identity: CryptoDeviceMaterial,
        checkedSender: PeerDirectoryInspection,
        envelope: E2eeInboundBootstrap.Envelope,
    ): ByteArray = mutate(account, device) { state ->
        require(account == identity.accountUid && device == identity.deviceUid)
        E2eeInboundBootstrap.decrypt(state, identity, checkedSender, envelope)
    }

    /**
     * Authenticate and validate the decrypted text BEFORE committing the new
     * inbound ratchet counter. Reject malformed payloads without consuming
     * the session/one-time prekey in durable storage.
     */
    fun decryptInboundText(
        account: String,
        device: String,
        identity: CryptoDeviceMaterial,
        checkedSender: PeerDirectoryInspection,
        envelope: E2eeInboundBootstrap.Envelope,
    ): String = mutate(account, device) { state ->
        require(account == identity.accountUid && device == identity.deviceUid)
        val plaintext = E2eeInboundBootstrap.decrypt(state, identity, checkedSender, envelope)
        try {
            E2eeInboundText.decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    /** Only after confirmed remote device revocation, never on ordinary logout. */
    fun clearAfterServerRevocation(account: String, device: String) = synchronized(processLock) {
        file(account, device).delete()
    }
}
