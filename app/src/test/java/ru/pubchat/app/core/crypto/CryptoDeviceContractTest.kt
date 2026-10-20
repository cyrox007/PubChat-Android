package ru.pubchat.app.core.crypto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class CryptoDeviceContractTest {
    private val account = "22222222-2222-4222-8222-222222222222"
    private fun vector() = CryptoDeviceMaterial(
        accountUid = account,
        deviceUid = "11111111-1111-4111-8111-111111111111",
        edSeed = ByteArray(32) { it.toByte() },
        dhSeed = ByteArray(32) { (it + 32).toByte() },
        signedPrekey = CryptoPreKey(42, ByteArray(32) { (it + 64).toByte() }),
        oneTimePrekeys = listOf(CryptoPreKey(100, ByteArray(32) { (it + 96).toByte() })),
    )

    @Test fun crossLanguageEd25519X25519AndSignedPrekeyVectorsMatchWebProtocol() {
        val material = vector()
        val payload = material.registrationPayload()
        assertEquals("A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg", payload.getString("identity_signing_key"))
        assertEquals("NYBy1jZYgNGu6jKa35EhODhR7SGijjt16WXQ0s0WYlQ", payload.getString("identity_dh_key"))
        val signed = payload.getJSONObject("signed_prekey")
        assertEquals(42, signed.getInt("id"))
        assertEquals("eaYx7t4b-cmPEgMs3q3Q56B5OY_HhriMyEbsia-FpRo", signed.getString("public_key"))
        assertEquals(
            "NP4zVkCe0BG-kuwdnj5IMJ905UqbkfLtP5w6mM_IOmacD56yR-2FczUg9DrCRl8nxyA9Xmcq9KwsQdV_DC4OCA",
            signed.getString("signature"),
        )
        assertEquals("brG_7aijCbgGyneWwadEDH7Td8fplt-he9-RyWlr_a4", material.fingerprint)
        assertTrue(CryptoV1.verify(
            CryptoV1.parseB64(material.signingPublic, 32),
            CryptoV1.parseB64(signed.getString("signature"), 64),
            CryptoV1.signedPrekeyMessage(42, CryptoV1.parseB64(signed.getString("public_key"), 32)),
        ))
        assertFalse(CryptoV1.verify(
            CryptoV1.parseB64(material.signingPublic, 32),
            CryptoV1.parseB64(signed.getString("signature"), 64),
            CryptoV1.signedPrekeyMessage(43, CryptoV1.parseB64(signed.getString("public_key"), 32)),
        ))
    }

    @Test fun registrationPayloadContainsPublicMaterialOnly() {
        val payload = vector().registrationPayload()
        assertEquals(7, payload.length())
        for (secret in listOf("seed", "private_key", "refresh_token", "access_token", "account_uid")) {
            assertFalse(payload.has(secret))
            assertFalse(payload.toString().contains(secret))
        }
        assertEquals(1, payload.getJSONArray("one_time_prekeys").length())
        assertEquals(1, payload.getInt("key_version"))
    }

    @Test fun privateSerializationRoundTripPreservesIdentityAcrossRetryButIsNotNetworkPayload() {
        val material = vector()
        val bytes = material.privateBytes()
        val restored = CryptoDeviceMaterial.fromPrivateBytes(bytes, account)
        assertEquals(material.deviceUid, restored.deviceUid)
        assertEquals(material.signingPublic, restored.signingPublic)
        assertEquals(material.dhPublic, restored.dhPublic)
        assertEquals(material.registrationPayload().toString(), restored.registrationPayload().toString())
        bytes.fill(0)
    }

    @Test fun accountIsolationAndMalformedKeyMaterialFailClosed() {
        val raw = vector().privateBytes()
        assertThrows(IllegalArgumentException::class.java) {
            CryptoDeviceMaterial.fromPrivateBytes(raw, UUID.randomUUID().toString())
        }
        val altered = JSONObject(String(raw))
        altered.put("ed_seed", "not-a-key")
        assertThrows(Exception::class.java) {
            CryptoDeviceMaterial.fromPrivateBytes(altered.toString().toByteArray(), account)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CryptoDeviceMaterial.generate(account, count = 101)
        }
    }

    private fun remote(material: CryptoDeviceMaterial): JSONObject = material.registrationPayload()
        .put("account_uid", material.accountUid)
        .put("fingerprint", material.fingerprint)
        .put("available_one_time_prekeys", 1)
        .put("revoked_at", JSONObject.NULL)

    @Test fun serverResponseMustMatchEveryIdentityAndSignedPrekeyField() {
        val material = vector()
        val body = JSONObject().put("status", "ok").put("device", remote(material)).toString()
        val snapshot = parseCryptoDeviceResponse(body, material)
        assertEquals(material.deviceUid, snapshot.deviceUid)
        assertEquals(1, snapshot.availablePrekeys)
        assertFalse(snapshot.revoked)
        for ((key, value) in listOf(
            "identity_signing_key" to CryptoV1.b64(ByteArray(32)),
            "identity_dh_key" to CryptoV1.b64(ByteArray(32)),
            "fingerprint" to CryptoV1.b64(ByteArray(32)),
            "device_uid" to UUID.randomUUID().toString(),
            "account_uid" to UUID.randomUUID().toString(),
        )) {
            assertThrows(Exception::class.java) {
                val tampered = remote(material).put(key, value)
                parseCryptoDeviceResponse(JSONObject().put("status", "ok").put("device", tampered).toString(), material)
            }
        }
        assertThrows(Exception::class.java) {
            val bad = remote(material)
            bad.getJSONObject("signed_prekey").put("id", 43)
            parseCryptoDevice(bad, material)
        }
    }

    @Test fun ownDeviceListDoesNotTreatMissingOrRevokedAsRegistered() {
        val material = vector()
        val empty = """{"status":"ok","devices":[]}"""
        assertNull(parseOwnCryptoDeviceList(empty, material))
        val revoked = remote(material).put("revoked_at", "2026-10-20T10:00:00")
        val list = JSONObject().put("status", "ok")
            .put("devices", org.json.JSONArray().put(revoked)).toString()
        assertTrue(parseOwnCryptoDeviceList(list, material)?.revoked == true)
        assertThrows(Exception::class.java) {
            parseOwnCryptoDeviceList(JSONObject().put("status", "ok")
                .put("devices", org.json.JSONArray().put(remote(material)).put(remote(material))).toString(), material)
        }
    }

    @Test fun revocationNeedsExactServerAcknowledgement() {
        val material = vector()
        val result = JSONObject().put("status", "ok")
            .put("device_uid", material.deviceUid)
            .put("revoked", true)
            .put("revoked_at", "2026-10-20T10:00:00")
        parseCryptoRevocation(result.toString(), material.deviceUid)
        assertThrows(Exception::class.java) {
            parseCryptoRevocation(result.put("device_uid", UUID.randomUUID().toString()).toString(), material.deviceUid)
        }
        assertThrows(Exception::class.java) {
            parseCryptoRevocation(result.put("device_uid", material.deviceUid).put("revoked", false).toString(), material.deviceUid)
        }
    }
}
