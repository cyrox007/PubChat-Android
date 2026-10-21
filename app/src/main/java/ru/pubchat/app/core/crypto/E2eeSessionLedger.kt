package ru.pubchat.app.core.crypto

import org.json.JSONObject
import java.util.UUID

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

    /** An inbound counter may only advance AFTER authenticating the ciphertext. */
    fun decryptNext(
        root: JSONObject, sender: String, recipient: String, sessionId: String,
        counter: Long, nonce: String, ciphertext: String,
    ): ByteArray {
        val session = root.getJSONObject("sessions").getJSONObject(key("in", sender, sessionId))
        val current = session.getLong("next")
        require(counter == current) { "Replay, missing or reordered message: explicit recovery required" }
        require(counter < Long.MAX_VALUE)
        val chain = CryptoV1.parseB64(session.getString("chain"), 32)
        val (messageKey, nextChain) = E2eeSessionCrypto.step(chain, counter)
        try {
            val cipherBytes = java.util.Base64.getUrlDecoder().decode(ciphertext)
            require(cipherBytes.size >= 16 && CryptoV1.b64(cipherBytes) == ciphertext)
            val plaintext = E2eeSessionCrypto.open(messageKey,
                E2eeSessionCrypto.Sealed(CryptoV1.parseB64(nonce, 12), cipherBytes),
                E2eeSessionCrypto.aad(sender, recipient, sessionId, counter))
            session.put("chain", CryptoV1.b64(nextChain)).put("next", counter + 1)
            return plaintext
        } finally {
            chain.fill(0); messageKey.fill(0); nextChain.fill(0)
        }
    }
}
