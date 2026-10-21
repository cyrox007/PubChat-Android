package ru.pubchat.app.core.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Offline atomic multi-device send. Immutable envelopes + every consumed
 * outbound counter are committed in the SAME E2eeSessionStore snapshot.
 *
 * No HTTP call is made here. Caller MUST obtain fresh, validated own/peer
 * directories and bind exactly those active targets before calling prepare.
 */
internal object E2eeFanoutBatch {
    private const val MAX_ENVELOPES = 40
    data class Target(val deviceUid: String, val sessionId: String, val initialHeader: JSONObject? = null)
    data class Envelope(
        val recipientDeviceUid: String,
        val sessionId: String,
        val counter: Long,
        val nonce: String,
        val ciphertext: String,
        val initialHeader: JSONObject?,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("recipient_device_uid", recipientDeviceUid)
            .put("session_id", sessionId)
            .put("counter", counter)
            .put("nonce", nonce)
            .put("ciphertext", ciphertext)
            .put("initial_header", initialHeader ?: JSONObject.NULL)
    }
    data class Batch(
        val frontId: String,
        val receiverUid: String,
        val senderDeviceUid: String,
        val envelopes: List<Envelope>,
    ) {
        fun requestBody(): JSONObject = JSONObject()
            .put("receiver_uid", receiverUid)
            .put("sender_device_uid", senderDeviceUid)
            .put("front_id", frontId)
            .put("envelopes", JSONArray().also { a -> envelopes.forEach { a.put(it.json()) } })
    }

    private fun uuid(value: String): String = UUID.fromString(value).toString().also {
        require(value == it) { "Noncanonical identifier" }
    }

    private fun rows(state: JSONObject): JSONObject = state.optJSONObject("batches")
        ?: JSONObject().also { state.put("batches", it) }

    private fun packetId(frontId: String, recipient: String): String =
        UUID.nameUUIDFromBytes(
            ("pubchat-e2ee-fanout-v1:" + frontId + ":" + recipient).toByteArray(StandardCharsets.UTF_8)
        ).toString()

    /**
     * Does not bootstrap sessions. All targets need already-created outbound
     * sessions; the next milestone must bind initial headers to verified keys.
     * Repeated frontId never re-encrypts or advances a counter.
     */
    fun prepare(
        state: JSONObject,
        frontId: String,
        senderDeviceUid: String,
        receiverUid: String,
        ownActiveDevices: List<Target>,
        peerActiveDevices: List<Target>,
        plaintext: ByteArray,
    ): Batch {
        val id = uuid(frontId)
        val sender = uuid(senderDeviceUid)
        val receiver = uuid(receiverUid)
        require(plaintext.isNotEmpty() && plaintext.size <= 16_384)
        val targets = ownActiveDevices + peerActiveDevices
        require(ownActiveDevices.isNotEmpty() && peerActiveDevices.isNotEmpty())
        require(targets.size <= MAX_ENVELOPES && targets.map { uuid(it.deviceUid) }.toSet().size == targets.size) {
            "Missing, duplicated or excessive active devices"
        }
        require(ownActiveDevices.any { it.deviceUid == sender }) { "Current sender device missing" }
        val batches = rows(state)
        require(!batches.has(id)) { "Duplicate frontId: load the immutable pending batch" }
        val pending = state.getJSONObject("pending")
        require(pending.length() + targets.size <= 256) { "Pending capacity exceeded" }
        val envelopes = mutableListOf<Envelope>()
        val packetIds = JSONArray()
        for (target in targets) {
            val recipient = uuid(target.deviceUid)
            val session = uuid(target.sessionId)
            val packetUid = packetId(id, recipient)
            val packet = E2eeSessionLedger.reserve(state, packetUid, sender, recipient, session, plaintext)
            require(packet.counter <= 2_147_483_647L) { "Server envelope counter limit exceeded" }
            // Store header alongside the immutable packet, never fetch a newer
            // initial_header during retry after device or key rotation.
            envelopes.add(Envelope(recipient, session, packet.counter, packet.nonce, packet.ciphertext,
                target.initialHeader?.let { JSONObject(it.toString()) }))
            packetIds.put(packetUid)
        }
        val body = Batch(id, receiver, sender, envelopes)
        batches.put(id, JSONObject()
            .put("receiver_uid", receiver).put("sender_device_uid", sender)
            .put("envelopes", body.requestBody().getJSONArray("envelopes"))
            .put("packet_ids", packetIds))
        return body
    }

    fun load(state: JSONObject, frontId: String): Batch {
        val id = uuid(frontId)
        val row = rows(state).getJSONObject(id)
        val entries = row.getJSONArray("envelopes")
        val envelopes = (0 until entries.length()).map { i ->
            val item = entries.getJSONObject(i)
            Envelope(
                uuid(item.getString("recipient_device_uid")),
                uuid(item.getString("session_id")),
                item.getLong("counter"),
                item.getString("nonce"),
                item.getString("ciphertext"),
                item.optJSONObject("initial_header")?.let { JSONObject(it.toString()) },
            )
        }
        return Batch(id, uuid(row.getString("receiver_uid")),
            uuid(row.getString("sender_device_uid")), envelopes)
    }

    fun validate(state: JSONObject) {
        val batches = state.optJSONObject("batches") ?: return
        require(batches.length() <= 256)
        val pending = state.getJSONObject("pending")
        val referenced = mutableSetOf<String>()
        for (frontId in batches.keys()) {
            uuid(frontId)
            val batch = load(state, frontId)
            require(batch.envelopes.size in 2..MAX_ENVELOPES)
            val row = batches.getJSONObject(frontId)
            val packetIds = row.getJSONArray("packet_ids")
            require(packetIds.length() == batch.envelopes.size)
            require(batch.envelopes.map { it.recipientDeviceUid }.toSet().size == batch.envelopes.size)
            batch.envelopes.forEachIndexed { i, envelope ->
                val packetUid = uuid(packetIds.getString(i))
                require(packetUid == packetId(frontId, envelope.recipientDeviceUid))
                require(referenced.add(packetUid)) { "Packet linked by two batches" }
                val packet = pending.getJSONObject(packetUid)
                require(packet.getString("recipient") == envelope.recipientDeviceUid)
                require(packet.getString("session") == envelope.sessionId)
                require(packet.getLong("counter") == envelope.counter)
                require(packet.getString("nonce") == envelope.nonce)
                require(packet.getString("ciphertext") == envelope.ciphertext)
            }
        }
    }

    /** Invoked ONLY after authenticated server success and exact frontId match. */
    fun acknowledge(state: JSONObject, frontId: String): Boolean {
        val id = uuid(frontId)
        val batches = rows(state)
        if (!batches.has(id)) return false
        validate(state)
        val row = batches.getJSONObject(id)
        val packets = row.getJSONArray("packet_ids")
        for (i in 0 until packets.length()) {
            require(state.getJSONObject("pending").remove(packets.getString(i)) != null)
        }
        batches.remove(id)
        return true
    }
}
