package ru.pubchat.app.core.crypto

import org.json.JSONObject
import java.util.UUID

data class CryptoDeviceSnapshot(
    val deviceUid: String,
    val accountUid: String,
    val fingerprint: String,
    val revoked: Boolean,
    val availablePrekeys: Int?,
)

internal fun parseCryptoDeviceResponse(body: String, expected: CryptoDeviceMaterial): CryptoDeviceSnapshot {
    val root = JSONObject(body)
    require(root.getString("status") == "ok") { "Сервер не подтвердил криптографическое устройство" }
    val device = root.getJSONObject("device")
    return parseCryptoDevice(device, expected)
}

/** Account, key, fingerprint and signed-prekey immutability are checked before accepting server acknowledgement. */
internal fun parseCryptoDevice(device: JSONObject, expected: CryptoDeviceMaterial): CryptoDeviceSnapshot {
    val uid = UUID.fromString(device.getString("device_uid")).toString()
    val account = UUID.fromString(device.getString("account_uid")).toString()
    require(uid == expected.deviceUid && account == expected.accountUid)
    require(device.getInt("key_version") == 1)
    require(device.getString("identity_signing_key") == expected.signingPublic)
    require(device.getString("identity_dh_key") == expected.dhPublic)
    require(device.getString("fingerprint") == expected.fingerprint)
    val published = expected.registrationPayload().getJSONObject("signed_prekey")
    val received = device.getJSONObject("signed_prekey")
    require(received.getInt("id") == published.getInt("id"))
    require(received.getString("public_key") == published.getString("public_key"))
    require(received.getString("signature") == published.getString("signature"))
    val revoked = !device.isNull("revoked_at") && device.optString("revoked_at").isNotBlank()
    val count = if (device.isNull("available_one_time_prekeys")) null
        else device.getInt("available_one_time_prekeys").also { require(it >= 0) }
    return CryptoDeviceSnapshot(uid, account, expected.fingerprint, revoked, count)
}

internal fun parseOwnCryptoDeviceList(body: String, expected: CryptoDeviceMaterial): CryptoDeviceSnapshot? {
    val root = JSONObject(body)
    require(root.getString("status") == "ok")
    val rows = root.getJSONArray("devices")
    var found: CryptoDeviceSnapshot? = null
    for (i in 0 until rows.length()) {
        val row = rows.getJSONObject(i)
        if (row.getString("device_uid") == expected.deviceUid) {
            require(found == null) { "Сервер вернул повторяющееся устройство" }
            found = parseCryptoDevice(row, expected)
        }
    }
    return found
}

internal fun parseCryptoRevocation(body: String, expectedDeviceUid: String) {
    val root = JSONObject(body)
    require(root.getString("status") == "ok" && root.getBoolean("revoked"))
    require(UUID.fromString(root.getString("device_uid")).toString() == expectedDeviceUid)
    require(root.getString("revoked_at").isNotBlank())
}
