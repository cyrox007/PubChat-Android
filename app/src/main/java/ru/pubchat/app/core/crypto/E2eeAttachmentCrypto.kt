package ru.pubchat.app.core.crypto

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web interoperable AES-256-GCM envelope for /crypto/v1/attachments/{uid}.
 *
 * The API stores ONLY ciphertext. Key/nonce/name/MIME live exclusively inside
 * the device-encrypted per-recipient E2EE message envelope. Never log descriptors,
 * and never send them in public Messenger/Space metadata.
 */
internal object E2eeAttachmentCrypto {
    const val VERSION = "pubchat-e2ee-attachment-v1"
    const val MAX_PLAINTEXT_BYTES = 2 * 1024 * 1024
    private val rng = SecureRandom()
    private val hex = "0123456789abcdef".toCharArray()

    data class Sealed(val ciphertext: ByteArray, val descriptor: JSONObject)

    private fun canonical(uid: String): String = UUID.fromString(uid).toString().also {
        require(uid == it) { "Noncanonical attachment uid" }
    }

    private fun aad(uid: String): ByteArray =
        """{"v":"$VERSION","uid":"${canonical(uid)}"}""".toByteArray(StandardCharsets.UTF_8)

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder()
        .withoutPadding().encodeToString(bytes)

    private fun unb64(value: String, exact: Int): ByteArray {
        val result = Base64.getUrlDecoder().decode(value)
        require(result.size == exact && b64(result) == value) { "Invalid canonical base64url" }
        return result
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun sha256Hex(bytes: ByteArray): String = buildString {
        for (b in sha256(bytes)) {
            val v = b.toInt() and 255
            append(hex[v ushr 4]); append(hex[v and 15])
        }
    }

    fun seal(
        uid: String,
        plaintext: ByteArray,
        name: String,
        mime: String,
        kind: String = "file",
    ): Sealed {
        canonical(uid)
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES)
        require(name.isNotBlank() && name.length <= 255)
        require(mime.isNotBlank() && mime.length <= 160)
        require(kind in setOf("image", "video", "audio", "voice", "file"))
        val key = ByteArray(32).also(rng::nextBytes)
        val nonce = ByteArray(12).also(rng::nextBytes)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(uid))
            val sealed = cipher.doFinal(plaintext)
            val descriptor = JSONObject()
                .put("uid", uid)
                .put("version", VERSION)
                .put("key", b64(key))
                .put("nonce", b64(nonce))
                .put("ciphertext_sha256", sha256Hex(sealed))
                .put("kind", kind)
                .put("name", name)
                .put("mime_type", mime)
                .put("size", plaintext.size)
                .put("duration", 0)
                .put("waveform", org.json.JSONArray())
            return Sealed(sealed, descriptor)
        } finally {
            key.fill(0); nonce.fill(0)
        }
    }

    /** Fail closed BEFORE the ciphertext reaches Android's media renderer. */
    fun open(descriptor: JSONObject, ciphertext: ByteArray): ByteArray {
        val uid = canonical(descriptor.getString("uid"))
        require(descriptor.getString("version") == VERSION)
        val declaredSize = descriptor.getInt("size")
        require(declaredSize in 1..MAX_PLAINTEXT_BYTES)
        require(ciphertext.size == declaredSize + 16)
        val declaredHash = descriptor.getString("ciphertext_sha256")
        require(declaredHash.matches(Regex("[0-9a-f]{64}")))
        require(MessageDigest.isEqual(
            declaredHash.toByteArray(StandardCharsets.US_ASCII),
            sha256Hex(ciphertext).toByteArray(StandardCharsets.US_ASCII)
        )) { "Encrypted attachment integrity mismatch" }
        val key = unb64(descriptor.getString("key"), 32)
        val nonce = unb64(descriptor.getString("nonce"), 12)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad(uid))
            return cipher.doFinal(ciphertext).also {
                require(it.size == declaredSize) { "Attachment size mismatch" }
            }
        } finally {
            key.fill(0); nonce.fill(0)
        }
    }

    fun encryptedPayload(descriptor: JSONObject, caption: String = ""): ByteArray {
        require(caption.length <= 4096)
        require(descriptor.getString("version") == VERSION)
        val payload = JSONObject()
            .put("type", "attachment")
            .put("attachment", descriptor)
            .put("caption", caption)
            .toString().toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= 16_384) { "E2EE envelope metadata too large" }
        return payload
    }
}
