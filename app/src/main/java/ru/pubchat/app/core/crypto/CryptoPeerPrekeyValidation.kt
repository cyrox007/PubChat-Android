package ru.pubchat.app.core.crypto

import org.json.JSONObject
import java.util.UUID

/**
 * Validates server-supplied public material before any E2EE session bootstrap.
 *
 * A successful HTTP response is NOT cryptographic trust. The signed X25519
 * prekey must be verified with the device's Ed25519 identity and the bundle
 * must match the already checked peer directory. This is a pure validator:
 * it never fetches/claims one-time prekeys or enables encrypted transport.
 */
internal data class VerifiedPeerPrekey(
    val deviceUid: String,
    val signingKey: ByteArray,
    val identityDhKey: ByteArray,
    val signedPrekeyId: Int,
    val signedPrekey: ByteArray,
    val oneTimePrekeyId: Int?,
    val oneTimePrekey: ByteArray?,
)

internal object CryptoPeerPrekeyValidation {
    private fun uuid(raw: String): String = UUID.fromString(raw).toString().also {
        require(it == raw) { "Noncanonical device or Account ID" }
    }

    private fun positiveId(id: Int): Int = id.also { require(it >= 0) { "Invalid prekey ID" } }

    private fun signedPrekey(device: JSONObject, signing: ByteArray): Pair<Int, ByteArray> {
        val signed = device.getJSONObject("signed_prekey")
        val id = positiveId(signed.getInt("id"))
        val key = CryptoV1.parseB64(signed.getString("public_key"), 32)
        val signature = CryptoV1.parseB64(signed.getString("signature"), 64)
        require(CryptoV1.verify(signing, signature, CryptoV1.signedPrekeyMessage(id, key))) {
            "Invalid signed prekey signature"
        }
        return id to key
    }

    private fun checkedDirectoryDevice(device: JSONObject): VerifiedPeerPrekey {
        require(device.getInt("key_version") == 1) { "Unsupported cryptographic device version" }
        val deviceUid = uuid(device.getString("device_uid"))
        val signing = CryptoV1.parseB64(device.getString("identity_signing_key"), 32)
        val identityDh = CryptoV1.parseB64(device.getString("identity_dh_key"), 32)
        require(device.getString("fingerprint") == CryptoV1.fingerprint(signing, identityDh)) {
            "Peer device fingerprint mismatch"
        }
        val (prekeyId, prekey) = signedPrekey(device, signing)
        return VerifiedPeerPrekey(deviceUid, signing, identityDh, prekeyId, prekey, null, null)
    }

    fun directory(response: String, expectedAccountUid: String): Map<String, VerifiedPeerPrekey> {
        val expected = uuid(expectedAccountUid)
        val root = JSONObject(response)
        require(root.getString("status") == "ok")
        require(uuid(root.getString("account_uid")) == expected) { "Wrong peer Account directory" }
        val rows = root.getJSONArray("devices")
        require(rows.length() in 1..200) { "Missing or excessive peer devices" }
        val verified = linkedMapOf<String, VerifiedPeerPrekey>()
        for (index in 0 until rows.length()) {
            val item = checkedDirectoryDevice(rows.getJSONObject(index))
            require(verified.put(item.deviceUid, item) == null) { "Duplicate peer device" }
        }
        return verified
    }

    /**
     * Only call after validating directory checkpoint continuity and obtaining
     * explicit user acknowledgement for changed device membership. It is not
     * a safety-number verification or a shortcut around that requirement.
     */
    fun bundle(
        response: String,
        expectedAccountUid: String,
        checkedDirectory: Map<String, VerifiedPeerPrekey>,
    ): List<VerifiedPeerPrekey> {
        require(checkedDirectory.isNotEmpty())
        val expected = uuid(expectedAccountUid)
        val root = JSONObject(response)
        require(root.getString("status") == "ok")
        val bundle = root.getJSONObject("bundle")
        require(bundle.getString("bundle_version") == "v1")
        require(uuid(bundle.getString("account_uid")) == expected) { "Wrong Account prekey bundle" }
        val rows = bundle.getJSONArray("devices")
        require(rows.length() == checkedDirectory.size) { "Device list changed" }
        val seen = mutableSetOf<String>()
        val verified = mutableListOf<VerifiedPeerPrekey>()
        for (index in 0 until rows.length()) {
            val item = rows.getJSONObject(index)
            val candidate = checkedDirectoryDevice(item)
            val directory = checkedDirectory[candidate.deviceUid]
                ?: throw IllegalArgumentException("Unknown peer device")
            require(seen.add(candidate.deviceUid)) { "Duplicate bundle device" }
            require(
                candidate.signingKey.contentEquals(directory.signingKey) &&
                    candidate.identityDhKey.contentEquals(directory.identityDhKey) &&
                    candidate.signedPrekeyId == directory.signedPrekeyId &&
                    candidate.signedPrekey.contentEquals(directory.signedPrekey)
            ) { "Peer key substitution or rotated signed prekey" }
            val oneTime = if (item.isNull("one_time_prekey")) null else item.getJSONObject("one_time_prekey")
            val oneTimeId = oneTime?.let { positiveId(it.getInt("id")) }
            val oneTimePublic = oneTime?.let { CryptoV1.parseB64(it.getString("public_key"), 32) }
            verified.add(candidate.copy(oneTimePrekeyId = oneTimeId, oneTimePrekey = oneTimePublic))
        }
        require(seen == checkedDirectory.keys) { "Missing peer bundle devices" }
        return verified
    }
}
