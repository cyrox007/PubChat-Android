package ru.pubchat.app.core.crypto

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal data class DeviceDirectoryCheckpoint(val sequence: Long, val eventHash: String, val previousHash: String, val directoryHash: String)
internal data class PeerDirectoryTrust(val accountUid: String, val checkpoint: DeviceDirectoryCheckpoint, val acknowledgedDevices: Map<String, String>)
internal data class PeerDirectoryInspection(val checkpoint: DeviceDirectoryCheckpoint, val devices: Map<String, VerifiedPeerPrekey>, val requiresAcknowledgement: Boolean)

/**
 * Rejects rollback, fork, truncated history, and substituted device identities.
 * Server history lacks the full event payload; links can be checked but historical
 * event hashes cannot be independently recomputed. No external witness exists.
 */
internal object PeerDirectoryContinuity {
    private const val VERSION = "pubchat-device-directory-v1"
    private const val MAX_HISTORY = 200
    private val hashRegex = Regex("^[0-9a-f]{64}$")

    private fun hash(value: String): String = value.also { require(hashRegex.matches(it)) { "Invalid directory hash" } }
    private fun account(value: String): String = UUID.fromString(value).toString().also { require(value == it) }

    fun checkpoint(raw: JSONObject): DeviceDirectoryCheckpoint {
        require(raw.getString("version") == VERSION)
        val sequence = raw.getLong("sequence")
        require(sequence >= 1)
        return DeviceDirectoryCheckpoint(sequence, hash(raw.getString("event_hash")), hash(raw.getString("previous_hash")), hash(raw.getString("directory_hash")))
    }

    private fun snapshotHash(devices: Map<String, VerifiedPeerPrekey>): String {
        // Canonical server JSON: sort_keys=True, separators=(',', ':'), ASCII-only
        // fields: device_uid, fingerprint, identity_dh_key, identity_signing_key, key_version.
        val entries = devices.values.sortedBy { it.deviceUid }.map { item ->
            val fp = CryptoV1.fingerprint(item.signingKey, item.identityDhKey)
            "{\"device_uid\":\"" + item.deviceUid + "\",\"fingerprint\":\"" + fp +
                "\",\"identity_dh_key\":\"" + CryptoV1.b64(item.identityDhKey) +
                "\",\"identity_signing_key\":\"" + CryptoV1.b64(item.signingKey) +
                "\",\"key_version\":1}"
        }
        val canonical = entries.joinToString(separator = ",", prefix = "[", postfix = "]")
        return MessageDigest.getInstance("SHA-256").digest(
            "pubchat-device-directory-snapshot-v1\u0000".toByteArray(Charsets.UTF_8) +
                canonical.toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun inspect(directoryResponse: String, historyResponse: String?, expectedAccountUid: String, previous: PeerDirectoryTrust?): PeerDirectoryInspection {
        val uid = account(expectedAccountUid)
        require(previous == null || previous.accountUid == uid) { "Account checkpoint mix-up" }
        val root = JSONObject(directoryResponse)
        require(root.getString("status") == "ok" && account(root.getString("account_uid")) == uid)
        val devices = CryptoPeerPrekeyValidation.directory(directoryResponse, uid)
        val latest = checkpoint(root.getJSONObject("directory_checkpoint"))
        require(snapshotHash(devices) == latest.directoryHash) { "Device directory snapshot hash mismatch" }
        if (previous != null) {
            val prior = previous.checkpoint
            require(latest.sequence >= prior.sequence) { "Device directory rollback" }
            if (latest.sequence == prior.sequence) {
                require(latest == prior) { "Device directory fork" }
            } else {
                val gap = latest.sequence - prior.sequence
                require(gap in 1..MAX_HISTORY.toLong()) { "Device directory history exceeds bound" }
                val history = JSONObject(requireNotNull(historyResponse) { "Missing directory history" })
                require(history.getString("status") == "ok" && account(history.getString("account_uid")) == uid)
                require(checkpoint(history.getJSONObject("directory_checkpoint")) == latest) { "Directory changed during history fetch" }
                val events = history.getJSONArray("events")
                require(events.length() == gap.toInt()) { "Truncated directory history" }
                var expectedSeq = prior.sequence + 1
                var previousHash = prior.eventHash
                for (index in 0 until events.length()) {
                    val event = events.getJSONObject(index)
                    require(event.getString("version") == VERSION)
                    require(event.getLong("sequence") == expectedSeq++) { "Noncontiguous history" }
                    require(hash(event.getString("previous_hash")) == previousHash) { "Broken directory chain" }
                    previousHash = hash(event.getString("event_hash"))
                    val directoryHash = hash(event.getString("directory_hash"))
                    if (index == events.length() - 1) {
                        require(previousHash == latest.eventHash && directoryHash == latest.directoryHash &&
                            event.getString("previous_hash") == latest.previousHash) { "History head disagrees with checkpoint" }
                    }
                }
            }
        } else if (latest.sequence == 1L) {
            require(latest.previousHash == "0".repeat(64)) { "Invalid genesis checkpoint" }
        }
        val current = devices.mapValues { (_, item) -> CryptoV1.fingerprint(item.signingKey, item.identityDhKey) }
        return PeerDirectoryInspection(latest, devices, previous != null && previous.acknowledgedDevices != current)
    }

    fun trust(expectedAccountUid: String, inspection: PeerDirectoryInspection): PeerDirectoryTrust =
        PeerDirectoryTrust(account(expectedAccountUid), inspection.checkpoint,
            inspection.devices.mapValues { (_, item) -> CryptoV1.fingerprint(item.signingKey, item.identityDhKey) })
}
