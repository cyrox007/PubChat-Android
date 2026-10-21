package ru.pubchat.app.core.crypto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CryptoPeerPrekeyValidationTest {
    private val account = "22222222-2222-4222-8222-222222222222"
    private val uid = "11111111-1111-4111-8111-111111111111"

    private fun keys() = CryptoDeviceMaterial(
        accountUid = account,
        deviceUid = uid,
        edSeed = ByteArray(32) { it.toByte() },
        dhSeed = ByteArray(32) { (it + 32).toByte() },
        signedPrekey = CryptoPreKey(42, ByteArray(32) { (it + 64).toByte() }),
        oneTimePrekeys = listOf(CryptoPreKey(100, ByteArray(32) { (it + 96).toByte() })),
    )

    private fun device(): JSONObject = keys().registrationPayload()
        .put("fingerprint", keys().fingerprint)

    private fun directory(vararg devices: JSONObject): String = JSONObject()
        .put("status", "ok").put("account_uid", account)
        .put("devices", JSONArray().also { rows -> devices.forEach(rows::put) })
        .toString()

    private fun bundle(vararg devices: JSONObject): String = JSONObject()
        .put("status", "ok")
        .put("bundle", JSONObject()
            .put("account_uid", account)
            .put("bundle_version", "v1")
            .put("devices", JSONArray().also { rows -> devices.forEach(rows::put) }))
        .toString()

    @Test fun verifiedSignatureAndDirectoryBoundBundle() {
        val accepted = CryptoPeerPrekeyValidation.directory(directory(device()), account)
        val row = device()
            .put("one_time_prekey", JSONObject()
                .put("id", 100)
                .put("public_key", CryptoV1.b64(CryptoV1.xPublic(keys().oneTimePrekeys.single().seed))))
        val result = CryptoPeerPrekeyValidation.bundle(bundle(row), account, accepted)
        assertEquals(uid, result.single().deviceUid)
        assertEquals(42, result.single().signedPrekeyId)
        assertEquals(100, result.single().oneTimePrekeyId)
        assertEquals(32, result.single().oneTimePrekey?.size)
    }

    @Test fun rejectTamperedSigningIdentitySignatureAndPrekeyId() {
        val good = device()
        val signed = good.getJSONObject("signed_prekey")
        for (mutated in listOf(
            device().put("identity_signing_key", CryptoV1.b64(ByteArray(32))),
            device().apply { getJSONObject("signed_prekey").put("id", 43) },
            device().apply { getJSONObject("signed_prekey").put("signature", CryptoV1.b64(ByteArray(64))) },
            device().apply { getJSONObject("signed_prekey").put("public_key", CryptoV1.b64(ByteArray(32))) },
        )) {
            assertThrows(Exception::class.java) {
                CryptoPeerPrekeyValidation.directory(directory(mutated), account)
            }
        }
        assertEquals(42, signed.getInt("id"))
    }

    @Test fun rejectKeySubstitutionAndDeviceMembershipChanges() {
        val pinned = CryptoPeerPrekeyValidation.directory(directory(device()), account)
        assertThrows(Exception::class.java) {
            val switched = device().put("identity_dh_key", CryptoV1.b64(ByteArray(32)))
            CryptoPeerPrekeyValidation.bundle(bundle(switched), account, pinned)
        }
        assertThrows(Exception::class.java) {
            CryptoPeerPrekeyValidation.bundle(bundle(), account, pinned)
        }
        assertThrows(Exception::class.java) {
            CryptoPeerPrekeyValidation.bundle(bundle(device(), device()), account, pinned)
        }
    }

    @Test fun rejectMalformedOrMismatchedAccountDirectoryAndBundle() {
        assertThrows(Exception::class.java) {
            CryptoPeerPrekeyValidation.directory(directory(device()), UUID.randomUUID().toString())
        }
        assertThrows(Exception::class.java) {
            CryptoPeerPrekeyValidation.directory(directory(device(), device()), account)
        }
        assertThrows(Exception::class.java) {
            CryptoPeerPrekeyValidation.directory(directory(), account)
        }
        val pinned = CryptoPeerPrekeyValidation.directory(directory(device()), account)
        assertThrows(Exception::class.java) {
            CryptoPeerPrekeyValidation.bundle(bundle(device()), UUID.randomUUID().toString(), pinned)
        }
        assertThrows(Exception::class.java) {
            CryptoPeerPrekeyValidation.bundle(bundle(device().put("one_time_prekey",
                JSONObject().put("id", 7).put("public_key", "not-canonical"))), account, pinned)
        }
    }
}
