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
 * Account-scoped, no-backup, keystore-encrypted peer directory trust anchors.
 * A failed read never silently resets TOFU history to "first use".
 */
internal class PeerDirectoryTrustStore(context: Context) {
    private val app = context.applicationContext
    private val alias = "pubchat_peer_directory_wrap_v1"

    private fun file(ownerUid: String, peerUid: String): AtomicFile {
        val owner = UUID.fromString(ownerUid).toString()
        val peer = UUID.fromString(peerUid).toString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$owner:$peer".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return AtomicFile(File(app.noBackupFilesDir, "peer_directory_v1_$digest.bin"))
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    private fun aad(ownerUid: String, peerUid: String): ByteArray =
        ("pubchat-peer-directory-v1:" + UUID.fromString(ownerUid) + ":" + UUID.fromString(peerUid))
            .toByteArray(Charsets.UTF_8)

    fun read(ownerUid: String, peerUid: String): PeerDirectoryTrust? {
        val file = file(ownerUid, peerUid)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val envelope = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(envelope.getInt("version") == 1) { "Unknown peer trust storage version" }
        val iv = CryptoV1.parseB64(envelope.getString("iv"), 12)
        val ciphertext = java.util.Base64.getUrlDecoder().decode(envelope.getString("ciphertext"))
        require(ciphertext.size >= 16 && CryptoV1.b64(ciphertext) == envelope.getString("ciphertext"))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(ownerUid, peerUid))
        val plaintext = cipher.doFinal(ciphertext)
        return try {
            val stored = JSONObject(String(plaintext, Charsets.UTF_8))
            require(stored.getString("owner_uid") == UUID.fromString(ownerUid).toString())
            require(stored.getString("peer_uid") == UUID.fromString(peerUid).toString())
            val cp = PeerDirectoryContinuity.checkpoint(stored.getJSONObject("checkpoint"))
            val devices = stored.getJSONObject("acknowledged_devices")
            val pins = linkedMapOf<String, String>()
            for (deviceUid in devices.keys()) {
                val canonical = UUID.fromString(deviceUid).toString()
                require(deviceUid == canonical)
                val fp = devices.getString(deviceUid)
                require(CryptoV1.parseB64(fp, 32).size == 32)
                require(pins.put(canonical, fp) == null)
            }
            require(pins.isNotEmpty() && pins.size <= 200)
            PeerDirectoryTrust(stored.getString("peer_uid"), cp, pins)
        } finally {
            plaintext.fill(0)
        }
    }

    fun save(ownerUid: String, state: PeerDirectoryTrust) {
        val peerUid = UUID.fromString(state.accountUid).toString()
        require(state.acknowledgedDevices.isNotEmpty() && state.acknowledgedDevices.size <= 200)
        val file = file(ownerUid, peerUid)
        // A stale inspection must never overwrite a newer durable checkpoint.
        val current = read(ownerUid, peerUid)
        if (current != null) {
            require(state.checkpoint.sequence >= current.checkpoint.sequence)
            if (state.checkpoint.sequence == current.checkpoint.sequence) {
                require(state.checkpoint == current.checkpoint) { "Refusing directory checkpoint fork" }
            }
        }
        val cp = state.checkpoint
        val payload = JSONObject()
            .put("owner_uid", UUID.fromString(ownerUid).toString())
            .put("peer_uid", peerUid)
            .put("checkpoint", JSONObject()
                .put("version", "pubchat-device-directory-v1")
                .put("sequence", cp.sequence)
                .put("event_hash", cp.eventHash)
                .put("previous_hash", cp.previousHash)
                .put("directory_hash", cp.directoryHash))
            .put("acknowledged_devices", JSONObject().also { pins ->
                state.acknowledgedDevices.forEach { (device, fingerprint) ->
                    pins.put(UUID.fromString(device).toString(), fingerprint)
                }
            })
        val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val ciphertext = try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(aad(ownerUid, peerUid))
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val record = JSONObject().put("version", 1)
            .put("iv", CryptoV1.b64(cipher.iv))
            .put("ciphertext", CryptoV1.b64(ciphertext))
            .toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(record)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
