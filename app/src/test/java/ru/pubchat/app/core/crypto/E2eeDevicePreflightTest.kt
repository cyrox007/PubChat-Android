package ru.pubchat.app.core.crypto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class E2eeDevicePreflightTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val peer = "22222222-2222-4222-8222-222222222222"
    private val ownDevice = "33333333-3333-4333-8333-333333333333"
    private val peerDevice = "44444444-4444-4444-8444-444444444444"
    private val checkpoint = DeviceDirectoryCheckpoint(1, "a".repeat(64), "0".repeat(64), "b".repeat(64))

    private fun material(account: String, device: String, offset: Int): CryptoDeviceMaterial =
        CryptoDeviceMaterial(account, device,
            ByteArray(32) { (it + offset).toByte() },
            ByteArray(32) { (it + offset + 32).toByte() },
            CryptoPreKey(123 + offset, ByteArray(32) { (it + offset + 64).toByte() }),
            emptyList(),
        )

    private fun item(material: CryptoDeviceMaterial): JSONObject = material.registrationPayload()
        .put("fingerprint", material.fingerprint)

    private fun inspection(account: String, devices: List<JSONObject>, changed: Boolean = false):
        PeerDirectoryInspection {
        val raw = JSONObject().put("status", "ok").put("account_uid", account)
            .put("devices", JSONArray().also { a -> devices.forEach { a.put(it) } })
        return PeerDirectoryInspection(checkpoint,
            CryptoPeerPrekeyValidation.directory(raw.toString(), account), changed)
    }

    private fun bundle(account: String, items: List<JSONObject>): String =
        JSONObject().put("status", "ok")
            .put("bundle", JSONObject().put("bundle_version", "v1").put("account_uid", account)
                .put("devices", JSONArray().also { a -> items.forEach { a.put(it) } }))
            .toString()

    private fun fixture(): Triple<CryptoDeviceMaterial, PeerDirectoryInspection, PeerDirectoryInspection> {
        val local = material(owner, ownDevice, 1)
        val remote = material(peer, peerDevice, 3)
        return Triple(local, inspection(owner, listOf(item(local))),
            inspection(peer, listOf(item(remote))))
    }

    private fun batch(local: String, remote: String, extra: String? = null): E2eeFanoutBatch.Batch {
        val devices = listOf(local, remote) + listOfNotNull(extra)
        return E2eeFanoutBatch.Batch(
            "55555555-5555-4555-8555-555555555555", peer, local,
            devices.map { target ->
                E2eeFanoutBatch.Envelope(target,
                    "66666666-6666-4666-8666-666666666666", 0, "nonce", "cipher", null)
            },
        )
    }

    @Test fun verifiedBundleAndExactFanoutPass() {
        val (local, own, remote) = fixture()
        val preflight = E2eeDevicePreflight.verify(owner, peer, local, own, remote,
            bundle(peer, listOf(item(material(peer, peerDevice, 3))
                .put("one_time_prekey", JSONObject.NULL))))
        assertEquals(setOf(ownDevice, peerDevice), preflight.requiredDeviceUids)
        E2eeDevicePreflight.recheck(preflight, own, remote, batch(ownDevice, peerDevice))
    }

    @Test fun changedIdentityOrMembershipBlocksSend() {
        val (local, own, remote) = fixture()
        val preflight = E2eeDevicePreflight.verify(owner, peer, local, own, remote,
            bundle(peer, listOf(item(material(peer, peerDevice, 3))
                .put("one_time_prekey", JSONObject.NULL))))
        assertThrows(Exception::class.java) {
            E2eeDevicePreflight.recheck(preflight, own, remote, batch(ownDevice, peerDevice,
                "77777777-7777-4777-8777-777777777777"))
        }
        assertThrows(Exception::class.java) {
            E2eeDevicePreflight.recheck(preflight, own,
                remote.copy(checkpoint = checkpoint.copy(sequence = 2)),
                batch(ownDevice, peerDevice))
        }
        assertThrows(Exception::class.java) {
            E2eeDevicePreflight.recheck(preflight, own, remote.copy(requiresAcknowledgement = true),
                batch(ownDevice, peerDevice))
        }
        assertThrows(Exception::class.java) {
            E2eeDevicePreflight.recheck(preflight, own, remote, batch(ownDevice, ownDevice))
        }
    }

    @Test fun substitutionAndUnacknowledgedDirectoriesBlockPreflight() {
        val (local, own, remote) = fixture()
        val correctPeer = item(material(peer, peerDevice, 3)).put("one_time_prekey", JSONObject.NULL)
        assertThrows(Exception::class.java) {
            E2eeDevicePreflight.verify(owner, peer, local, own,
                remote.copy(requiresAcknowledgement = true), bundle(peer, listOf(correctPeer)))
        }
        assertThrows(Exception::class.java) {
            E2eeDevicePreflight.verify(owner, peer, local,
                own.copy(requiresAcknowledgement = true), remote,
                bundle(peer, listOf(correctPeer)))
        }
        assertThrows(Exception::class.java) {
            E2eeDevicePreflight.verify(owner, peer, local, own, remote,
                bundle(peer, listOf(item(material(peer, peerDevice, 18))
                    .put("one_time_prekey", JSONObject.NULL))))
        }
        assertThrows(Exception::class.java) {
            E2eeDevicePreflight.verify(owner, peer, local, own, remote, bundle(peer, emptyList()))
        }
        assertThrows(Exception::class.java) {
            E2eeDevicePreflight.verify(owner, peer,
                material(owner, ownDevice, 15), own, remote,
                bundle(peer, listOf(correctPeer)))
        }
    }
}
