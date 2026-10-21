package ru.pubchat.app.core.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives for Messenger E2EE v1, not a network sender.
 * Key derivation/transport must pass independent Web vectors before use.
 */
internal object E2eeSessionCrypto {
    private val random = SecureRandom()
    private const val MAX_FORWARD_GAP = 64

    fun dh(privateSeed: ByteArray, publicRaw: ByteArray): ByteArray {
        require(privateSeed.size == 32 && publicRaw.size == 32)
        val out = ByteArray(32)
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateSeed, 0))
        agreement.calculateAgreement(X25519PublicKeyParameters(publicRaw, 0), out, 0)
        require(out.any { it.toInt() != 0 }) { "Invalid X25519 shared secret" }
        return out
    }

    /** 3DH with optional one-time prekey. Never use unverified peer prekeys. */
    fun senderSharedSecret(
        identitySeed: ByteArray,
        ephemeralSeed: ByteArray,
        recipient: VerifiedPeerPrekey,
    ): ByteArray {
        require(identitySeed.size == 32 && ephemeralSeed.size == 32)
        val secrets = arrayListOf(
            dh(identitySeed, recipient.signedPrekey),
            dh(ephemeralSeed, recipient.identityDhKey),
            dh(ephemeralSeed, recipient.signedPrekey),
        )
        recipient.oneTimePrekey?.let { secrets.add(dh(ephemeralSeed, it)) }
        return secrets.fold(ByteArray(0)) { left, right -> left + right }.also { secrets.forEach { it.fill(0) } }
    }

    /** Recipient side must select the archived signed/one-time private keys by header IDs. */
    fun recipientSharedSecret(
        identitySeed: ByteArray,
        signedPrekeySeed: ByteArray,
        oneTimePrekeySeed: ByteArray?,
        senderIdentityPublic: ByteArray,
        senderEphemeralPublic: ByteArray,
    ): ByteArray {
        val secrets = arrayListOf(
            dh(signedPrekeySeed, senderIdentityPublic),
            dh(identitySeed, senderEphemeralPublic),
            dh(signedPrekeySeed, senderEphemeralPublic),
        )
        oneTimePrekeySeed?.let { secrets.add(dh(it, senderEphemeralPublic)) }
        return secrets.fold(ByteArray(0)) { left, right -> left + right }.also { secrets.forEach { it.fill(0) } }
    }

    /**
     * HKDF-SHA256 uses an all-zero salt and versioned UTF-8 info string.
     * Interoperability with Web must be verified against its exact encoded vectors.
     */
    fun deriveRoot(concatenatedDh: ByteArray): ByteArray {
        require(concatenatedDh.size == 96 || concatenatedDh.size == 128)
        val output = ByteArray(32)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(concatenatedDh, ByteArray(32),
            "pubchat-e2ee-root-v1".toByteArray(Charsets.UTF_8)))
        hkdf.generateBytes(output, 0, output.size)
        return output
    }

    fun hmac(key: ByteArray, label: String): ByteArray {
        require(key.size == 32)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }

    fun step(chainKey: ByteArray, counter: Long): Pair<ByteArray, ByteArray> {
        require(counter >= 0)
        return hmac(chainKey, "pubchat-message-key-v1:$counter") to
            hmac(chainKey, "pubchat-chain-key-v1:$counter")
    }

    fun aad(senderDevice: String, recipientDevice: String, sessionId: String, counter: Long): ByteArray {
        require(counter >= 0)
        val sender = UUID.fromString(senderDevice).toString()
        val recipient = UUID.fromString(recipientDevice).toString()
        val session = UUID.fromString(sessionId).toString()
        require(senderDevice == sender && recipientDevice == recipient && sessionId == session)
        // This order/encoding is explicit; compare with Web AAD vector before enablement.
        return "1:$sender:$recipient:$session:$counter".toByteArray(Charsets.UTF_8)
    }

    data class Sealed(val nonce: ByteArray, val ciphertext: ByteArray)

    fun seal(messageKey: ByteArray, plaintext: ByteArray, associatedData: ByteArray): Sealed {
        require(messageKey.size == 32)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return Sealed(nonce, cipher.doFinal(plaintext))
    }

    fun open(messageKey: ByteArray, sealed: Sealed, associatedData: ByteArray): ByteArray {
        require(messageKey.size == 32 && sealed.nonce.size == 12 && sealed.ciphertext.size >= 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(messageKey, "AES"), GCMParameterSpec(128, sealed.nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed.ciphertext)
    }

    /** Forward-only key derivation; caller must durably commit state atomically. */
    fun advance(chainKey: ByteArray, nextCounter: Long, requestedCounter: Long): Pair<ByteArray, ByteArray> {
        require(nextCounter >= 0 && requestedCounter >= nextCounter)
        require(requestedCounter - nextCounter <= MAX_FORWARD_GAP) { "Counter gap exceeds bound" }
        var chain = chainKey.copyOf()
        var key = ByteArray(0)
        for (counter in nextCounter..requestedCounter) {
            val (messageKey, next) = step(chain, counter)
            chain.fill(0)
            chain = next
            key.fill(0)
            key = messageKey
        }
        return key to chain
    }
}
