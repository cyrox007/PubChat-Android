package ru.pubchat.app.core.crypto

import org.json.JSONObject

/**
 * Strict result of POST /crypto/v1/messages. A duplicate=true response means
 * server claim status is ambiguous; it cannot authorize deleting ciphertext.
 */
internal object E2eeSendAck {
    fun verifiedMessage(body: String, batch: E2eeFanoutBatch.Batch): JSONObject {
        val root = JSONObject(body)
        require(root.getString("status") == "ok")
        require(!root.optBoolean("duplicate", true)) {
            "Encrypted frontId claimed already: reconcile server history before ACK"
        }
        val message = root.getJSONObject("message")
        require(message.getString("frontId") == batch.frontId) {
            "Encrypted server ACK frontId mismatch"
        }
        require(message.getString("content_type") == "e2ee") {
            "Server ACK did not confirm ciphertext-only content"
        }
        return message
    }
}
