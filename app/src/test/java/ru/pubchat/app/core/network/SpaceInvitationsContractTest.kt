package ru.pubchat.app.core.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpaceInvitationsContractTest {
    private val invitationUid = "11111111-1111-4111-8111-111111111111"
    private val spaceUid = "22222222-2222-4222-8222-222222222222"
    private val secondUid = "33333333-3333-4333-8333-333333333333"
    private val api = PubChatApi(deviceUid = { "device" }, accessToken = { null }, refreshCookie = { null })

    private fun invitation(uid: String = invitationUid): String = """
        {"uid":"$uid","status":"pending","expires_at":"2026-10-26T12:00:00",
         "space":{"uid":"$spaceUid","name":"Тихий клуб","description":"Сообщество по приглашению"},
         "inviter":{"account_uid":"44444444-4444-4444-8444-444444444444","display_name":"Ольга","handle":"olga"}}
    """.trimIndent()

    private fun page(rows: String, total: Int = 1, limit: Int = 25, offset: Int = 0, count: Int = 1): String = """
        {"status":"ok","invitations":[$rows],
         "pagination":{"limit":$limit,"offset":$offset,"count":$count,"total":$total}}
    """.trimIndent()

    private fun acceptedSpace(membership: String = "active") = """
        {"uid":"$spaceUid","name":"Тихий клуб",
         "viewer_membership":{"status":"$membership","role":"member"}}
    """.trimIndent()

    @Test fun parsesServerOwnedPrivateInvitationProjection() {
        val result = parseSpaceInvitationPage(page(invitation()), 25, 0)
        assertEquals(1, result.total)
        assertEquals(25, result.limit)
        assertEquals(0, result.offset)
        assertEquals(1, result.rows.size)
        assertEquals(invitationUid, result.rows.single().uid)
        assertEquals(spaceUid, result.rows.single().spaceUid)
        assertEquals("Тихий клуб", result.rows.single().spaceName)
        assertEquals("Ольга", result.rows.single().inviterName)
        assertEquals("2026-10-26T12:00:00", result.rows.single().expiresAt)
    }

    @Test fun supportsBoundedPaginationAndEmptyState() {
        val empty = parseSpaceInvitationPage(page("", total = 0, count = 0), 25, 0)
        assertTrue(empty.rows.isEmpty())
        val more = parseSpaceInvitationPage(
            page(invitation(secondUid), total = 26, offset = 25), 25, 25
        )
        assertEquals(26, more.total)
        assertEquals(secondUid, more.rows.single().uid)
    }

    @Test fun rejectsServerPaginationMismatchAndDuplicateInvitations() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSpaceInvitationPage(page(invitation(), offset = 25), 25, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSpaceInvitationPage(page("${invitation()},${invitation()}", total = 2, count = 2), 25, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSpaceInvitationPage(page(invitation(), count = 0), 25, 0)
        }
    }

    @Test fun rejectsInactiveAndInvalidInvitationReferences() {
        assertThrows(IllegalArgumentException::class.java) {
            parseSpaceInvitationPage(page(invitation().replace("\"pending\"", "\"expired\"")), 25, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSpaceInvitationPage(page(invitation("not-a-uuid")), 25, 0)
        }
    }

    @Test fun acceptsOnlyTheServerConfirmedMembership() {
        val body = """
            {"status":"ok","invitation":{"uid":"$invitationUid","status":"accepted",
             "space":${acceptedSpace()}}}
        """.trimIndent()
        val confirmed = parseSpaceInvitationDecision(body, invitationUid, "accept", api::parseSpace)
        assertEquals("accepted", confirmed.status)
        assertEquals(spaceUid, confirmed.space?.uid)
        assertTrue(confirmed.space?.isActiveMember == true)
        assertThrows(IllegalArgumentException::class.java) {
            parseSpaceInvitationDecision(
                body.replace("\"active\"", "\"pending\""), invitationUid, "accept", api::parseSpace
            )
        }
    }

    @Test fun declineMustBeConfirmedByServerAndCannotReturnAnOpenableSpace() {
        val body = """{"status":"ok","invitation":{"uid":"$invitationUid","status":"declined"}}"""
        val decision = parseSpaceInvitationDecision(body, invitationUid, "decline", api::parseSpace)
        assertEquals("declined", decision.status)
        assertNull(decision.space)
        assertThrows(IllegalArgumentException::class.java) {
            parseSpaceInvitationDecision(body, invitationUid, "accept", api::parseSpace)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseSpaceInvitationDecision(body, secondUid, "decline", api::parseSpace)
        }
    }

    @Test fun sendingActionsNeverContainsClientOwnedPermissions() {
        for (action in listOf("accept", "decline")) {
            val payload = invitationActionPayload(action)
            assertEquals(1, payload.length())
            assertEquals(action, payload.getString("action"))
            assertFalse(payload.has("account_uid"))
            assertFalse(payload.has("space_uid"))
            assertFalse(payload.has("role"))
        }
        assertThrows(IllegalArgumentException::class.java) { invitationActionPayload("join") }
    }

    @Test fun clearDeniedAndExpiredServerFailures() {
        assertTrue(invitationErrorMessage(PubChatApi.ApiFailure(409, "space_invitation_not_active")).contains("истекло"))
        assertTrue(invitationErrorMessage(PubChatApi.ApiFailure(403, "social_relationship_blocked")).contains("блокировки"))
        assertTrue(invitationErrorMessage(PubChatApi.ApiFailure(409, "space_full")).contains("места"))
        assertTrue(invitationErrorMessage(PubChatApi.ApiFailure(404, "space_invitation_not_found")).contains("недоступно"))
    }
}
