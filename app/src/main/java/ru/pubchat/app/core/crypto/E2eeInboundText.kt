package ru.pubchat.app.core.crypto

import org.json.JSONObject

/**
 * Parse only authenticated decrypted text payloads.
 * Unknown content types stay locked; never render server message.content.
 */
internal object E2eeInboundText {
    fun decode(plaintext: ByteArray): String {
        require(plaintext.isNotEmpty() && plaintext.size <= 16_384)
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plaintext)).toString()
        val payload = JSONObject(text)
        require(payload.getString("type") == "text") { "Unsupported encrypted payload type" }
        val message = payload.getString("text")
        require(message.isNotBlank() && message.length <= 8_192) { "Invalid encrypted text length" }
        return message
    }

    fun selectEnvelope(rows: JSONObject, messageUid: String, localDeviceUid: String):
        E2eeInboundBootstrap.Envelope {
        val entries = rows.getJSONArray(messageUid)
        require(entries.length() in 1..40)
        val candidates = (0 until entries.length())
            .map { E2eeInboundBootstrap.parseEnvelope(entries.getJSONObject(it)) }
            .filter { it.recipientDeviceUid == localDeviceUid }
        require(candidates.size == 1) { "Missing or duplicate envelope for local crypto device" }
        return candidates.single()
    }
}


/**
 * Decrypted content is parsed inside the AtomicFile receive transaction, before
 * advancing its ratchet. Attachment metadata is never read from public DM fields.
 * Keys and nonces stay in memory; media bytes remain ciphertext on the server.
 */
internal sealed interface E2eeInboundPayload {
    data class Text(val value: String) : E2eeInboundPayload
    class Attachment internal constructor(
        val uid: String,
        val name: String,
        val mime: String,
        val kind: String,
        val caption: String,
        private val metadata: String,
    ) : E2eeInboundPayload {
        fun authenticatedDescriptor(): JSONObject = JSONObject(metadata)
    }

    companion object {
        fun decode(plaintext: ByteArray): E2eeInboundPayload {
            require(plaintext.isNotEmpty() && plaintext.size <= 16_384)
            val decoded = Charsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(plaintext)).toString()
            val json = JSONObject(decoded)
            return when (json.getString("type")) {
                "text" -> Text(E2eeInboundText.decode(plaintext))
                "attachment" -> {
                    val descriptor = json.getJSONObject("attachment")
                    require(descriptor.getString("version") ==
                        E2eeAttachmentCrypto.VERSION)
                    val uid = descriptor.getString("uid")
                    require(java.util.UUID.fromString(uid).toString() == uid)
                    val bytes = descriptor.getInt("size")
                    require(bytes in 1..E2eeAttachmentCrypto.MAX_PLAINTEXT_BYTES)
                    val kind = descriptor.getString("kind")
                    val mime = descriptor.getString("mime_type")
                    require(when (kind) {
                        "image" -> mime in setOf("image/jpeg", "image/png",
                            "image/webp", "image/gif")
                        "audio", "voice" -> mime in setOf("audio/mp4",
                            "audio/x-m4a", "audio/mpeg", "audio/ogg")
                        "video" -> mime in setOf("video/mp4", "video/webm")
                        "file" -> mime == "application/pdf"
                        else -> false
                    }) { "Unsupported encrypted attachment media type" }
                    val name = descriptor.getString("name")
                    require(name.length in 1..255 && name.isNotBlank() &&
                        name.none { it == '/' || it == '\\' || it.code < 32 })
                    require(descriptor.getString("ciphertext_sha256")
                        .matches(Regex("[0-9a-f]{64}")))
                    fun validB64(name: String, length: Int) {
                        val token = descriptor.getString(name)
                        val bytes = java.util.Base64.getUrlDecoder().decode(token)
                        require(bytes.size == length &&
                            java.util.Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(bytes) == token)
                        bytes.fill(0)
                    }
                    validB64("key", 32)
                    validB64("nonce", 12)
                    val caption = json.optString("caption", "")
                    require(caption.length <= 4096)
                    Attachment(uid, name, mime, kind, caption,
                        descriptor.toString())
                }
                else -> error("Unsupported encrypted message type")
            }
        }
    }
}
