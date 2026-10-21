package ru.pubchat.app.core.crypto

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class E2eeSendAckTest {
    private fun sample(): E2eeFanoutBatch.Batch = E2eeFanoutBatch.Batch(
        frontId = "11111111-1111-4111-8111-111111111111",
        receiverUid = "22222222-2222-4222-8222-222222222222",
        senderDeviceUid = "33333333-3333-4333-8333-333333333333",
        envelopes = emptyList(),
    )

    private fun response(batch: E2eeFanoutBatch.Batch, duplicate: Boolean = false,
                         type: String = "e2ee", ack: String = batch.frontId): String =
        JSONObject().put("status", "ok").put("duplicate", duplicate)
            .put("message", JSONObject().put("frontId", ack).put("content_type", type))
            .toString()

    @Test fun onlyMatchingE2eeNormalSuccessMayAcknowledge() {
        val batch = sample()
        assertEquals(batch.frontId, E2eeSendAck.verifiedMessage(response(batch), batch).getString("frontId"))
        assertThrows(Exception::class.java) {
            E2eeSendAck.verifiedMessage(response(batch, duplicate = true), batch)
        }
        assertThrows(Exception::class.java) {
            E2eeSendAck.verifiedMessage(response(batch, ack = UUID.randomUUID().toString()), batch)
        }
        assertThrows(Exception::class.java) {
            E2eeSendAck.verifiedMessage(response(batch, type = "text"), batch)
        }
        assertThrows(Exception::class.java) {
            E2eeSendAck.verifiedMessage("{\"status\":\"error\"}", batch)
        }
        assertThrows(Exception::class.java) {
            E2eeSendAck.verifiedMessage("{\"status\":\"ok\",\"duplicate\":true,\"frontId\":\"" +
                batch.frontId + "\"}", batch)
        }
        assertThrows(Exception::class.java) {
            E2eeSendAck.verifiedMessage("{\"status\":\"ok\",\"message\":{\"frontId\":\"" +
                batch.frontId + "\",\"content_type\":\"e2ee\"}}", batch)
        }
    }
}
