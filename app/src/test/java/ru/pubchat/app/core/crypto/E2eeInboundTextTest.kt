package ru.pubchat.app.core.crypto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class E2eeInboundTextTest {
    private val ownDevice = "11111111-1111-4111-8111-111111111111"
    private val sender = "22222222-2222-4222-8222-222222222222"
    private val session = "33333333-3333-4333-8333-333333333333"
    private val message = "44444444-4444-4444-8444-444444444444"

    private fun envelope(recipient: String = ownDevice): JSONObject = JSONObject()
        .put("recipient_device_uid", recipient)
        .put("sender_device_uid", sender)
        .put("session_id", session)
        .put("counter", 0)
        .put("nonce", CryptoV1.b64(ByteArray(12) { it.toByte() }))
        .put("ciphertext", CryptoV1.b64(ByteArray(32) { (it + 1).toByte() }))
        .put("initial_header", JSONObject.NULL)

    @Test fun choosesOnlyOwnVerifiedEnvelopeAndRejectsDuplicateOrMissing() {
        val other = "55555555-5555-4555-8555-555555555555"
        val data = JSONObject().put(message, JSONArray().put(envelope(other)).put(envelope()))
        assertEquals(ownDevice,
            E2eeInboundText.selectEnvelope(data, message, ownDevice).recipientDeviceUid)
        data.put(message, JSONArray().put(envelope()).put(envelope()))
        assertThrows(Exception::class.java) {
            E2eeInboundText.selectEnvelope(data, message, ownDevice)
        }
        data.put(message, JSONArray().put(envelope(other)))
        assertThrows(Exception::class.java) {
            E2eeInboundText.selectEnvelope(data, message, ownDevice)
        }
        assertThrows(Exception::class.java) {
            E2eeInboundText.selectEnvelope(JSONObject(), message, ownDevice)
        }
    }

    @Test fun onlyAuthenticatedStructuredTextCanBeDisplayed() {
        assertEquals("Привет", E2eeInboundText.decode(
            """{"type":"text","text":"Привет"}""".toByteArray(Charsets.UTF_8)))
        for (raw in listOf(
            """{"type":"image","text":"not a message"}""",
            """{"type":"text","text":""}""",
            """{"content":"untrusted legacy plaintext"}""",
            "not json"
        )) {
            assertThrows(Exception::class.java) {
                E2eeInboundText.decode(raw.toByteArray(Charsets.UTF_8))
            }
        }
        assertThrows(Exception::class.java) {
            E2eeInboundText.decode(ByteArray(16_385) { 65 })
        }
        assertThrows(Exception::class.java) {
            E2eeInboundText.decode(byteArrayOf(0xc3.toByte(), 0x28))
        }
    }
}
