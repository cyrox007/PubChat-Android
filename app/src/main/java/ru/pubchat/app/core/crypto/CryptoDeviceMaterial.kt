package ru.pubchat.app.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Public material is exported only for /crypto/v1; secret seeds NEVER enter API JSON. */
internal object CryptoV1 {
    private val random = SecureRandom()
    private val codec = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val signedPrekeyDomain = "pubchat-signed-prekey-v1\u0000".toByteArray(Charsets.UTF_8)
    private val fingerprintDomain = "pubchat-device-identity-v1\u0000".toByteArray(Charsets.UTF_8)

    fun randomSeed(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun randomId(): Int = random.nextInt(Int.MAX_VALUE)
    fun b64(bytes: ByteArray): String = codec.encodeToString(bytes)

    fun parseB64(raw: String, expectedSize: Int): ByteArray {
        require(raw.isNotBlank() && !raw.contains("=")) { "Неканонический ключ" }
        val decoded = decoder.decode(raw)
        require(decoded.size == expectedSize && b64(decoded) == raw) { "Некорректная длина ключа" }
        return decoded
    }

    fun edPublic(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun xPublic(seed: ByteArray): ByteArray {
        require(seed.size == 32)
        return X25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun signedPrekeyMessage(id: Int, publicKey: ByteArray): ByteArray {
        require(id in 0..Int.MAX_VALUE && publicKey.size == 32)
        return signedPrekeyDomain + ByteBuffer.allocate(4).putInt(id).array() + publicKey
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32)
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }

    fun fingerprint(edKey: ByteArray, xKey: ByteArray): String {
        require(edKey.size == 32 && xKey.size == 32)
        return b64(MessageDigest.getInstance("SHA-256").digest(fingerprintDomain + edKey + xKey))
    }
}

internal data class CryptoPreKey(val id: Int, val seed: ByteArray) {
    init {
        require(id >= 0 && seed.size == 32)
    }
}

/**
 * Account-scoped identity. The caller saves it to encrypted no-backup storage
 * BEFORE attempting network registration; retries must reuse this exact key material.
 *
 * This is device identity infrastructure, NOT a Messenger ratchet or ciphertext transport.
 */
internal class CryptoDeviceMaterial(
    val accountUid: String,
    val deviceUid: String,
    val edSeed: ByteArray,
    val dhSeed: ByteArray,
    val signedPrekey: CryptoPreKey,
    val oneTimePrekeys: List<CryptoPreKey>,
) {
    init {
        UUID.fromString(accountUid)
        UUID.fromString(deviceUid)
        require(edSeed.size == 32 && dhSeed.size == 32)
        require(oneTimePrekeys.size <= 100)
        val allIds = oneTimePrekeys.map { it.id }
        require(allIds.size == allIds.toSet().size)
    }

    val signingPublic: String get() = CryptoV1.b64(CryptoV1.edPublic(edSeed))
    val dhPublic: String get() = CryptoV1.b64(CryptoV1.xPublic(dhSeed))
    val fingerprint: String get() = CryptoV1.fingerprint(CryptoV1.edPublic(edSeed), CryptoV1.xPublic(dhSeed))

    private fun signedPrekeyPublic() = CryptoV1.xPublic(signedPrekey.seed)

    fun registrationPayload(): JSONObject {
        val prekeyPub = signedPrekeyPublic()
        val signature = CryptoV1.sign(edSeed, CryptoV1.signedPrekeyMessage(signedPrekey.id, prekeyPub))
        check(CryptoV1.verify(CryptoV1.edPublic(edSeed), signature, CryptoV1.signedPrekeyMessage(signedPrekey.id, prekeyPub)))
        return JSONObject()
            .put("device_uid", deviceUid)
            .put("device_label", "Android")
            .put("key_version", 1)
            .put("identity_signing_key", signingPublic)
            .put("identity_dh_key", dhPublic)
            .put("signed_prekey", JSONObject()
                .put("id", signedPrekey.id)
                .put("public_key", CryptoV1.b64(prekeyPub))
                .put("signature", CryptoV1.b64(signature)))
            .put("one_time_prekeys", JSONArray().also { list ->
                oneTimePrekeys.forEach { key ->
                    list.put(JSONObject().put("id", key.id).put("public_key", CryptoV1.b64(CryptoV1.xPublic(key.seed))))
                }
            })
    }

    /** Only used inside encrypted local storage; do not log or transmit this JSON. */
    fun privateBytes(): ByteArray = JSONObject()
        .put("account_uid", accountUid).put("device_uid", deviceUid)
        .put("ed_seed", CryptoV1.b64(edSeed))
        .put("dh_seed", CryptoV1.b64(dhSeed))
        .put("signed_prekey", JSONObject()
            .put("id", signedPrekey.id).put("seed", CryptoV1.b64(signedPrekey.seed)))
        .put("one_time_prekeys", JSONArray().also { list ->
            oneTimePrekeys.forEach { key ->
                list.put(JSONObject().put("id", key.id).put("seed", CryptoV1.b64(key.seed)))
            }
        })
        .toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun generate(accountUid: String, count: Int = 20): CryptoDeviceMaterial {
            require(count in 0..100)
            UUID.fromString(accountUid)
            val randomIds = mutableSetOf<Int>()
            fun uniqueId(): Int {
                var id: Int
                do { id = CryptoV1.randomId() } while (!randomIds.add(id))
                return id
            }
            return CryptoDeviceMaterial(
                accountUid = accountUid,
                deviceUid = UUID.randomUUID().toString(),
                edSeed = CryptoV1.randomSeed(),
                dhSeed = CryptoV1.randomSeed(),
                signedPrekey = CryptoPreKey(uniqueId(), CryptoV1.randomSeed()),
                oneTimePrekeys = List(count) { CryptoPreKey(uniqueId(), CryptoV1.randomSeed()) },
            )
        }

        fun fromPrivateBytes(bytes: ByteArray, expectedAccountUid: String): CryptoDeviceMaterial {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            require(root.getString("account_uid") == expectedAccountUid) { "Ключи принадлежат другому Account" }
            val signed = root.getJSONObject("signed_prekey")
            val keys = root.getJSONArray("one_time_prekeys")
            require(keys.length() <= 100)
            return CryptoDeviceMaterial(
                accountUid = expectedAccountUid,
                deviceUid = UUID.fromString(root.getString("device_uid")).toString(),
                edSeed = CryptoV1.parseB64(root.getString("ed_seed"), 32),
                dhSeed = CryptoV1.parseB64(root.getString("dh_seed"), 32),
                signedPrekey = CryptoPreKey(signed.getInt("id"), CryptoV1.parseB64(signed.getString("seed"), 32)),
                oneTimePrekeys = List(keys.length()) { index ->
                    val row = keys.getJSONObject(index)
                    CryptoPreKey(row.getInt("id"), CryptoV1.parseB64(row.getString("seed"), 32))
                },
            )
        }
    }
}
