package ru.pubchat.app.core.crypto

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import javax.crypto.AEADBadTagException

class E2eeSessionCryptoTest {
    private fun seed(start: Int) = ByteArray(32) { (start + it).toByte() }
    private fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun webCompatibleHkdfAndSymmetricRatchetVector() {
        // Reference: RFC 5869 HKDF-SHA256 (zero salt; Web info string),
        // independently computed with HMAC-SHA256.
        val material = ByteArray(96) { it.toByte() }
        val root = E2eeSessionCrypto.deriveRoot(material)
        assertEquals("bd141e163466e1e0dc67784cedb70b96bad9d69d8388da2333819ac4aec5a468", hex(root))
        val (message, next) = E2eeSessionCrypto.step(root, 0)
        assertEquals("2fce0b3f138f422b8e1c04122593e775033e42d832c608008268d35f246e8965", hex(message))
        assertEquals("005da6343fea95a35a3680000a83c90fb81b4dae2b9ae91387014facf2891052", hex(next))
        assertFalse(message.contentEquals(next))
    }

    @Test fun x25519ThreeDhAgreesInBothDirectionsWithAndWithoutOneTimeKey() {
        val senderIdentity = seed(1)
        val senderEphemeral = seed(33)
        val recipientIdentity = seed(65)
        val recipientSigned = seed(97)
        val recipientOneTime = seed(129)
        val recipientBase = VerifiedPeerPrekey(
            UUID.randomUUID().toString(), ByteArray(32),
            CryptoV1.xPublic(recipientIdentity), 17,
            CryptoV1.xPublic(recipientSigned), null, null,
        )
        val senderIdentityPublic = CryptoV1.xPublic(senderIdentity)
        val ephemeralPublic = CryptoV1.xPublic(senderEphemeral)
        for (target in listOf(recipientBase, recipientBase.copy(
            oneTimePrekeyId = 19, oneTimePrekey = CryptoV1.xPublic(recipientOneTime)
        ))) {
            val sender = E2eeSessionCrypto.senderSharedSecret(senderIdentity, senderEphemeral, target)
            val receiver = E2eeSessionCrypto.recipientSharedSecret(
                recipientIdentity, recipientSigned,
                if (target.oneTimePrekey != null) recipientOneTime else null,
                senderIdentityPublic, ephemeralPublic,
            )
            assertArrayEquals(sender, receiver)
            assertArrayEquals(E2eeSessionCrypto.deriveRoot(sender), E2eeSessionCrypto.deriveRoot(receiver))
            assertEquals(if (target.oneTimePrekey == null) 96 else 128, sender.size)
        }
    }

    @Test fun aesGcmRoundtripAndTamperingOrWrongAssociatedDataFail() {
        val key = seed(0)
        val session = "11111111-1111-4111-8111-111111111111"
        val sender = "22222222-2222-4222-8222-222222222222"
        val recipient = "33333333-3333-4333-8333-333333333333"
        val aad = E2eeSessionCrypto.aad(sender, recipient, session, 0)
        assertEquals(
            "{\"v\":\"pubchat-e2ee-v1\",\"s\":\"$sender\",\"r\":\"$recipient\",\"sid\":\"$session\",\"c\":0}",
            String(aad, Charsets.UTF_8),
        )
        val plain = "{\"type\":\"text\",\"text\":\"Привет\"}".toByteArray(Charsets.UTF_8)
        val sealed = E2eeSessionCrypto.seal(key, plain, aad)
        assertArrayEquals(plain, E2eeSessionCrypto.open(key, sealed, aad))
        assertThrows(Exception::class.java) {
            E2eeSessionCrypto.open(key, sealed, E2eeSessionCrypto.aad(sender, recipient, session, 1))
        }
        assertThrows(Exception::class.java) {
            E2eeSessionCrypto.open(key, sealed.copy(ciphertext = sealed.ciphertext.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }), aad)
        }
    }

    @Test fun boundedRatchetRejectsOverlargeGapsAndReplays() {
        val chain = seed(1)
        val (_, next) = E2eeSessionCrypto.advance(chain, 0, 0)
        assertArrayEquals(E2eeSessionCrypto.step(chain, 0).second, next)
        val (last, finalChain) = E2eeSessionCrypto.advance(chain, 0, 64)
        assertEquals(32, last.size)
        assertEquals(32, finalChain.size)
        assertThrows(IllegalArgumentException::class.java) { E2eeSessionCrypto.advance(chain, 0, 65) }
        assertThrows(IllegalArgumentException::class.java) { E2eeSessionCrypto.advance(chain, 2, 1) }
    }
}
