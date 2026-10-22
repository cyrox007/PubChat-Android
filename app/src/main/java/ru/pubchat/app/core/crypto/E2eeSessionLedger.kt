package ru.pubchat.app.core.crypto

import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest

/**
 * Offline E2EE ledger. Every transition MUST be committed with AtomicFile
 * before a ciphertext is transmitted or a decrypted message is shown.
 * Persistence and packet serialization are a single atomic transaction.
 */
internal object E2eeSessionLedger {
    private fun uid(raw: String): String = UUID.fromString(raw).toString().also { require(it == raw) }
    private fun key(direction: String, peer: String, session: String): String {
        require(direction == "in" || direction == "out")
        return direction + ":" + uid(peer) + ":" + uid(session)
    }
    fun empty(account: String, device: String): JSONObject = JSONObject()
        .put("version", 1).put("account", uid(account)).put("device", uid(device))
        .put("sessions", JSONObject()).put("pending", JSONObject())

    fun validate(root: JSONObject, account: String, device: String) {
        require(root.getInt("version") == 1)
        require(root.getString("account") == uid(account) && root.getString("device") == uid(device))
        val sessions = root.getJSONObject("sessions")
        val pending = root.getJSONObject("pending")
        require(sessions.length() <= 400 && pending.length() <= 256)
        for (name in sessions.keys()) {
            val row = sessions.getJSONObject(name)
            require(name == key(row.getString("direction"), row.getString("peer"), row.getString("session")))
            require(row.getLong("next") >= 0)
            CryptoV1.parseB64(row.getString("chain"), 32)
            if (row.getString("direction") == "in" && row.has("sender_identity_dh")) {
                CryptoV1.parseB64(row.getString("sender_identity_dh"), 32)
                CryptoV1.parseB64(row.getString("sender_identity_signing"), 32)
            }

            if (row.has("initial_header")) {
                CryptoV1.parseB64(row.getString("peer_identity_dh"), 32)
                CryptoV1.parseB64(row.getString("peer_signing"), 32)
                require(row.getString("direction") == "out")
                val header = JSONObject(row.getString("initial_header"))
                CryptoV1.parseB64(header.getString("ephemeral_key"), 32)
                CryptoV1.parseB64(header.getString("sender_identity_dh_key"), 32)
                CryptoV1.parseB64(header.getString("sender_identity_signing_key"), 32)
                require(header.getInt("target_signed_prekey_id") >= 0)
                if (!header.isNull("target_one_time_prekey_id")) {
                    require(header.getInt("target_one_time_prekey_id") >= 0)
                }
                row.getBoolean("header_confirmed")
            }
        }
        // Backward-compatible optional bounded ratchet key cache, scoped to each
        // sender device and session. No plaintext is ever stored on disk.
        val receiveKeys = root.optJSONObject("receive_keys")
        if (receiveKeys != null) {
            require(receiveKeys.length() <= 400)
            var keyCount = 0
            for (sessionName in receiveKeys.keys()) {
                val session = sessions.getJSONObject(sessionName)
                require(session.getString("direction") == "in")
                val entries = receiveKeys.getJSONObject(sessionName)
                require(entries.length() <= 128)
                keyCount += entries.length()
                for (counter in entries.keys()) {
                    val numeric = counter.toLongOrNull()
                    require(numeric != null && numeric >= 0 && numeric < session.getLong("next") &&
                        numeric.toString() == counter)
                    val row = entries.getJSONObject(counter)
                    CryptoV1.parseB64(row.getString("key"), 32)
                    if (!row.isNull("fingerprint")) {
                        CryptoV1.parseB64(row.getString("fingerprint"), 32)
                    }
                }
            }
            require(keyCount <= 512)
        }
        val consumed = root.optJSONObject("consumed_one_time_prekeys")
        if (consumed != null) {
            require(consumed.length() <= 100) { "Consumed prekey ledger exceeded bound" }
            for (id in consumed.keys()) {
                require(id.toIntOrNull()?.let { it >= 0 && it.toString() == id } == true)
                val sessionName = consumed.getString(id)
                require(sessions.has(sessionName) &&
                    sessions.getJSONObject(sessionName).getString("direction") == "in") {
                    "Consumed one-time prekey lost inbound session"
                }
            }
        }
        for (name in pending.keys()) {
            uid(name)
            val item = pending.getJSONObject(name)
            require(item.getString("status") == "pending")
            val session = sessions.getJSONObject(key("out", item.getString("recipient"), item.getString("session")))
            require(item.getLong("counter") >= 0 && item.getLong("counter") < session.getLong("next"))
            CryptoV1.parseB64(item.getString("nonce"), 12)
            val ciphertext = item.getString("ciphertext")
            val data = java.util.Base64.getUrlDecoder().decode(ciphertext)
            require(data.size >= 16 && CryptoV1.b64(data) == ciphertext)
        }
        E2eeFanoutBatch.validate(root)
    }

    fun addSession(root: JSONObject, direction: String, peer: String, session: String, chain: ByteArray) {
        require(chain.size == 32)
        val name = key(direction, peer, session)
        val sessions = root.getJSONObject("sessions")
        require(sessions.length() < 400 && !sessions.has(name))
        sessions.put(name, JSONObject().put("direction", direction).put("peer", uid(peer))
            .put("session", uid(session)).put("next", 0L)
            .put("chain", CryptoV1.b64(chain)))
    }

    data class PendingPacket(
        val frontId: String, val recipient: String, val session: String,
        val counter: Long, val nonce: String, val ciphertext: String,
    )

    fun reserve(
        root: JSONObject,
        frontId: String,
        sender: String,
        recipient: String,
        sessionId: String,
        plaintext: ByteArray,
    ): PendingPacket {
        val packetId = uid(frontId)
        val pending = root.getJSONObject("pending")
        require(pending.length() < 256 && !pending.has(packetId))
        val session = root.getJSONObject("sessions").getJSONObject(key("out", recipient, sessionId))
        val counter = session.getLong("next")
        require(counter in 0 until Long.MAX_VALUE)
        val chain = CryptoV1.parseB64(session.getString("chain"), 32)
        val (messageKey, nextChain) = E2eeSessionCrypto.step(chain, counter)
        return try {
            val sealed = E2eeSessionCrypto.seal(messageKey, plaintext,
                E2eeSessionCrypto.aad(sender, recipient, sessionId, counter))
            val record = JSONObject().put("status", "pending")
                .put("recipient", uid(recipient)).put("session", uid(sessionId))
                .put("counter", counter).put("nonce", CryptoV1.b64(sealed.nonce))
                .put("ciphertext", CryptoV1.b64(sealed.ciphertext))
            pending.put(packetId, record)
            session.put("chain", CryptoV1.b64(nextChain)).put("next", counter + 1)
            PendingPacket(packetId, uid(recipient), uid(sessionId), counter,
                record.getString("nonce"), record.getString("ciphertext"))
        } finally {
            chain.fill(0); messageKey.fill(0); nextChain.fill(0)
        }
    }

    fun pending(root: JSONObject): List<PendingPacket> {
        val rows = root.getJSONObject("pending")
        return rows.keys().asSequence().map { id ->
            val item = rows.getJSONObject(id)
            PendingPacket(id, item.getString("recipient"), item.getString("session"),
                item.getLong("counter"), item.getString("nonce"), item.getString("ciphertext"))
        }.toList()
    }

    /** Only after server ACK. On ambiguous error keep identical ciphertext. */
    fun acknowledge(root: JSONObject, frontId: String): Boolean {
        val packetId = uid(frontId)
        // Per-device ACK must not tear apart an all-device send.
        val batches = root.optJSONObject("batches")
        if (batches != null) for (batch in batches.keys()) {
            val ids = batches.getJSONObject(batch).getJSONArray("packet_ids")
            for (index in 0 until ids.length()) {
                require(ids.getString(index) != packetId) { "Use atomic batch acknowledgement" }
            }
        }
        return root.getJSONObject("pending").remove(packetId) != null
    }

    /**
     * Crash-durable, bounded receive ratchet. Cached previously authenticated
     * ciphertext may be decrypted again for history after an app restart, but
     * changing nonce/ciphertext at the same counter is rejected (AEAD + hash).
     * Skipped keys can authenticate late arrivals within a 64-message window.
     * Caller wraps this mutation in one AtomicFile commit before UI display.
     */
    fun decryptNext(
        root: JSONObject, sender: String, recipient: String, sessionId: String,
        counter: Long, nonce: String, ciphertext: String,
    ): ByteArray {
        require(counter in 0..2_147_483_647L)
        val sessionName = key("in", sender, sessionId)
        val session = root.getJSONObject("sessions").getJSONObject(sessionName)
        val current = session.getLong("next")
        require(counter <= current || counter - current <= 64) {
            "Receive counter gap exceeds 64"
        }
        val nonceBytes = CryptoV1.parseB64(nonce, 12)
        val cipherBytes = java.util.Base64.getUrlDecoder().decode(ciphertext)
        require(cipherBytes.size >= 16 && CryptoV1.b64(cipherBytes) == ciphertext)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(nonceBytes + cipherBytes)
        val aad = E2eeSessionCrypto.aad(sender, recipient, sessionId, counter)
        val cache = root.optJSONObject("receive_keys") ?: JSONObject().also {
            root.put("receive_keys", it)
        }
        val records = cache.optJSONObject(sessionName) ?: JSONObject().also {
            cache.put(sessionName, it)
        }
        if (counter < current) {
            val record = requireNotNull(records.optJSONObject(counter.toString())) {
                "Old receive key expired or unavailable"
            }
            val pinned = if (record.isNull("fingerprint")) "" else record.getString("fingerprint")
            require(pinned.isEmpty() || pinned == CryptoV1.b64(fingerprint)) {
                "Same counter replayed with different ciphertext"
            }
            val messageKey = CryptoV1.parseB64(record.getString("key"), 32)
            return try {
                val plaintext = E2eeSessionCrypto.open(messageKey,
                    E2eeSessionCrypto.Sealed(nonceBytes, cipherBytes), aad)
                // Pin a delayed gap key to its first authenticated ciphertext.
                if (pinned.isEmpty()) record.put("fingerprint", CryptoV1.b64(fingerprint))
                plaintext
            } finally { messageKey.fill(0) }
        }
        require(counter - current <= 64)
        require(records.length() + (counter - current + 1) <= 128) {
            "Session receive cache is full; explicit recovery required"
        }
        val allRecords = cache.keys().asSequence().sumOf { cache.getJSONObject(it).length() }
        require(allRecords + (counter - current + 1) <= 512) {
            "Receive key storage limit reached"
        }
        val originalChain = CryptoV1.parseB64(session.getString("chain"), 32)
        var chain = originalChain
        var result: ByteArray? = null
        val staged = JSONObject()
        try {
            for (i in current..counter) {
                val (messageKey, advanced) = E2eeSessionCrypto.step(chain, i)
                try {
                    if (i == counter) {
                        // No state changes until AEAD succeeds.
                        result = E2eeSessionCrypto.open(messageKey,
                            E2eeSessionCrypto.Sealed(nonceBytes, cipherBytes), aad)
                    }
                    staged.put(i.toString(), JSONObject()
                        .put("key", CryptoV1.b64(messageKey))
                        .put("fingerprint", if (i == counter)
                            CryptoV1.b64(fingerprint) else JSONObject.NULL))
                } finally { messageKey.fill(0) }
                if (chain !== originalChain) chain.fill(0)
                chain = advanced
            }
            for (i in staged.keys()) records.put(i, staged.getJSONObject(i))
            session.put("chain", CryptoV1.b64(chain)).put("next", counter + 1)
            return requireNotNull(result)
        } finally {
            chain.fill(0)
            if (chain !== originalChain) originalChain.fill(0)
        }
    }
}
