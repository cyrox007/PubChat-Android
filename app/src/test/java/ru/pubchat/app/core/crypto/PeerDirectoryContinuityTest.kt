package ru.pubchat.app.core.crypto

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PeerDirectoryContinuityTest {
    private val account = "22222222-2222-4222-8222-222222222222"
    private val deviceUid = "11111111-1111-4111-8111-111111111111"
    private val digest = "00e1afa3993a8c10de1b73dcf7b27879bcd6dbc32fcaadcf2af29562a1d7e116"
    private val zero = "0".repeat(64)
    private val firstHash = "a".repeat(64)
    private val secondHash = "b".repeat(64)

    private fun device() = CryptoDeviceMaterial(
        account, deviceUid, ByteArray(32) { it.toByte() },
        ByteArray(32) { (it + 32).toByte() },
        CryptoPreKey(42, ByteArray(32) { (it + 64).toByte() }), emptyList(),
    ).let { it.registrationPayload().put("fingerprint", it.fingerprint) }

    private fun cp(seq: Long = 1, event: String = firstHash, prior: String = zero, directory: String = digest): JSONObject =
        JSONObject().put("version", "pubchat-device-directory-v1").put("sequence", seq)
            .put("event_hash", event).put("previous_hash", prior).put("directory_hash", directory)

    private fun response(cp: JSONObject = cp()): String = JSONObject()
        .put("status", "ok").put("account_uid", account)
        .put("devices", JSONArray().put(device()))
        .put("directory_checkpoint", cp).toString()

    private fun history(previous: String = firstHash, event: String = secondHash): String = JSONObject()
        .put("status", "ok").put("account_uid", account)
        .put("directory_checkpoint", cp(2, event, previous))
        .put("events", JSONArray().put(JSONObject()
            .put("version", "pubchat-device-directory-v1").put("sequence", 2)
            .put("previous_hash", previous).put("event_hash", event)
            .put("directory_hash", digest))).toString()

    @Test fun serverCanonicalSnapshotAndTofuBaselineMatch() {
        val result = PeerDirectoryContinuity.inspect(response(), null, account, null)
        assertFalse(result.requiresAcknowledgement)
        val trust = PeerDirectoryContinuity.trust(account, result)
        assertEquals(firstHash, trust.checkpoint.eventHash)
        assertEquals(1, trust.acknowledgedDevices.size)
        assertEquals("brG_7aijCbgGyneWwadEDH7Td8fplt-he9-RyWlr_a4", trust.acknowledgedDevices[deviceUid])
    }

    @Test fun rejectsRollbackForkAndSnapshotSubstitution() {
        val previous = PeerDirectoryContinuity.trust(account, PeerDirectoryContinuity.inspect(response(), null, account, null))
        assertThrows(Exception::class.java) {
            PeerDirectoryContinuity.inspect(response(cp(0)), null, account, previous)
        }
        assertThrows(Exception::class.java) {
            PeerDirectoryContinuity.inspect(response(cp(event = secondHash)), null, account, previous)
        }
        assertThrows(Exception::class.java) {
            PeerDirectoryContinuity.inspect(response(cp(directory = zero)), null, account, previous)
        }
    }

    @Test fun acceptsContiguousHistoryAndRejectsMissingOrForgedLinks() {
        val prior = PeerDirectoryContinuity.trust(account, PeerDirectoryContinuity.inspect(response(), null, account, null))
        val next = response(cp(2, secondHash, firstHash))
        val inspected = PeerDirectoryContinuity.inspect(next, history(), account, prior)
        assertFalse(inspected.requiresAcknowledgement)
        assertEquals(2, inspected.checkpoint.sequence)
        assertThrows(Exception::class.java) { PeerDirectoryContinuity.inspect(next, null, account, prior) }
        assertThrows(Exception::class.java) {
            PeerDirectoryContinuity.inspect(next, history(previous = zero), account, prior)
        }
        val truncated = JSONObject(history()).put("events", JSONArray()).toString()
        assertThrows(Exception::class.java) { PeerDirectoryContinuity.inspect(next, truncated, account, prior) }
        val future = response(cp(202, secondHash, firstHash))
        assertThrows(Exception::class.java) { PeerDirectoryContinuity.inspect(future, history(), account, prior) }
    }

    @Test fun disallowsCrossAccountAndUnsupportedVersions() {
        val previous = PeerDirectoryContinuity.trust(account, PeerDirectoryContinuity.inspect(response(), null, account, null))
        assertThrows(Exception::class.java) {
            PeerDirectoryContinuity.inspect(response(), null, "33333333-3333-4333-8333-333333333333", previous)
        }
        assertThrows(Exception::class.java) {
            PeerDirectoryContinuity.inspect(response(cp().put("version", "other")), null, account, previous)
        }
    }

    @Test fun changeInAcknowledgedPinsRequiresExplicitAction() {
        val first = PeerDirectoryContinuity.inspect(response(), null, account, null)
        val prior = PeerDirectoryContinuity.trust(account, first)
        val unacknowledged = prior.copy(acknowledgedDevices = mapOf(deviceUid to CryptoV1.b64(ByteArray(32))))
        val latest = PeerDirectoryContinuity.inspect(response(), null, account, unacknowledged)
        assertTrue(latest.requiresAcknowledgement)
        assertEquals(prior.acknowledgedDevices, PeerDirectoryContinuity.trust(account, first).acknowledgedDevices)
    }
}
