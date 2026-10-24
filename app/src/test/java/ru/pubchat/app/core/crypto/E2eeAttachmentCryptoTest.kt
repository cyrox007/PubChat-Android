package ru.pubchat.app.core.crypto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class E2eeAttachmentCryptoTest {
    private val uid = UUID.randomUUID().toString()
    private val raw = "web/native E2EE media boundary 🔐".toByteArray(Charsets.UTF_8)

    @Test fun aesGcmRoundtripWithWebV1DescriptorAndNoPlaintextLeak() {
        val sealed = E2eeAttachmentCrypto.seal(uid, raw, "note.txt", "text/plain")
        val descriptor = sealed.descriptor
        assertEquals("pubchat-e2ee-attachment-v1", descriptor.getString("version"))
        assertEquals(uid, descriptor.getString("uid"))
        assertEquals(raw.size + 16, sealed.ciphertext.size)
        assertFalse(sealed.ciphertext.contentEquals(raw))
        assertArrayEquals(raw, E2eeAttachmentCrypto.open(descriptor, sealed.ciphertext))
        val payload = E2eeAttachmentCrypto.encryptedPayload(descriptor)
        val json = JSONObject(String(payload, Charsets.UTF_8))
        assertEquals("attachment", json.getString("type"))
        assertEquals(descriptor.getString("key"),
            json.getJSONObject("attachment").getString("key"))
        assertFalse(String(sealed.ciphertext, Charsets.ISO_8859_1).contains("web/native"))
    }

    @Test fun rejectsTamperingWithUidCiphertextHashAndKey() {
        val sealed = E2eeAttachmentCrypto.seal(uid, raw, "clip.mp4", "video/mp4", "video")
        val corrupted = sealed.ciphertext.copyOf()
        corrupted[0] = (corrupted[0].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            E2eeAttachmentCrypto.open(sealed.descriptor, corrupted)
        }
        val wrongUid = JSONObject(sealed.descriptor.toString()).put(
            "uid", UUID.randomUUID().toString())
        assertThrows(Exception::class.java) {
            E2eeAttachmentCrypto.open(wrongUid, sealed.ciphertext)
        }
        val wrongKey = JSONObject(sealed.descriptor.toString()).put(
            "key", E2eeAttachmentCrypto.seal(UUID.randomUUID().toString(),
                raw, "other.txt", "text/plain").descriptor.getString("key"))
        assertThrows(Exception::class.java) {
            E2eeAttachmentCrypto.open(wrongKey, sealed.ciphertext)
        }
        val wrongHash = JSONObject(sealed.descriptor.toString()).put(
            "ciphertext_sha256", "0".repeat(64))
        assertThrows(Exception::class.java) {
            E2eeAttachmentCrypto.open(wrongHash, sealed.ciphertext)
        }
    }

    @Test fun limitsPayloadAndRejectsInvalidDescriptorVersion() {
        assertThrows(Exception::class.java) {
            E2eeAttachmentCrypto.seal(uid, ByteArray(0), "empty", "text/plain")
        }
        assertThrows(Exception::class.java) {
            E2eeAttachmentCrypto.seal(uid, ByteArray(
                E2eeAttachmentCrypto.MAX_PLAINTEXT_BYTES + 1), "oversized", "text/plain")
        }
        val result = E2eeAttachmentCrypto.seal(uid, raw, "tiny", "text/plain")
        assertThrows(Exception::class.java) {
            E2eeAttachmentCrypto.open(
                JSONObject(result.descriptor.toString()).put("version", "unknown"),
                result.ciphertext)
        }
        assertThrows(Exception::class.java) {
            E2eeAttachmentCrypto.encryptedPayload(result.descriptor, "x".repeat(4097))
        }
    }
}
