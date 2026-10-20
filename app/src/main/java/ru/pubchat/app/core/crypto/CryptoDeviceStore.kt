package ru.pubchat.app.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * No-backup AndroidKeyStore-encrypted storage. Never persist raw Ed25519/X25519
 * seeds to SharedPreferences, telemetry, a cloud backup, or an HTTP request.
 *
 * A keystore reset means decryption fails CLOSED. Do not auto-generate and
 * re-register a different identity over the old device UID.
 */
internal class CryptoDeviceStore(context: Context) {
    private val app = context.applicationContext
    private val alias = "pubchat_crypto_device_wrap_v1"
    private val aadDomain = "pubchat-device-material-v1:"

    private fun file(accountUid: String): AtomicFile {
        val canonical = java.util.UUID.fromString(accountUid).toString()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(app.noBackupFilesDir, "crypto_device_v1_$hash.bin"))
    }

    fun hasLocalIdentity(accountUid: String): Boolean = file(accountUid).baseFile.exists()

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    fun read(accountUid: String): CryptoDeviceMaterial? {
        val target = file(accountUid)
        if (!target.baseFile.exists()) return null
        val record = JSONObject(String(target.readFully(), Charsets.UTF_8))
        require(record.getInt("version") == 1) { "Неизвестная версия локального хранилища ключей" }
        val nonce = CryptoV1.parseB64(record.getString("iv"), 12)
        val ciphertext = java.util.Base64.getUrlDecoder().decode(record.getString("ciphertext")).also {
            require(it.size >= 16 && CryptoV1.b64(it) == record.getString("ciphertext"))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, nonce))
        cipher.updateAAD((aadDomain + accountUid).toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(ciphertext)
        return try {
            CryptoDeviceMaterial.fromPrivateBytes(plaintext, accountUid)
        } finally {
            plaintext.fill(0)
        }
    }

    fun save(material: CryptoDeviceMaterial) {
        val target = file(material.accountUid)
        check(!target.baseFile.exists()) { "Криптографическая идентичность уже существует: перезапись запрещена" }
        val plaintext = material.privateBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val ciphertext = try {
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            cipher.updateAAD((aadDomain + material.accountUid).toByteArray(Charsets.UTF_8))
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val data = JSONObject()
            .put("version", 1)
            .put("iv", CryptoV1.b64(cipher.iv))
            .put("ciphertext", CryptoV1.b64(ciphertext))
            .toString().toByteArray(Charsets.UTF_8)
        val stream = target.startWrite()
        try {
            stream.write(data)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    fun clearAfterServerRevocation(accountUid: String) {
        // Never call this until DELETE /crypto/v1/devices/{uid} is confirmed by the server.
        file(accountUid).delete()
    }
}
