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
