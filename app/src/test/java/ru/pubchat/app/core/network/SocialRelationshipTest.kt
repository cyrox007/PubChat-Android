package ru.pubchat.app.core.network

import org.junit.Assert.*
import org.junit.Test

class SocialRelationshipTest {
    private fun sample(
        following: Boolean = false,
        friends: Boolean = false,
        friendRequest: String? = null,
        blockedByMe: Boolean = false,
        blockedMe: Boolean = false,
    ) = PubChatApi.SocialRelationship(
        accountUid = "another-account",
        following = following,
        followedBy = false,
        friends = friends,
        friendRequest = friendRequest,
        blockedByMe = blockedByMe,
        blockedMe = blockedMe,
    )

    @Test fun incomingFriendRequestCanBeAccepted() {
        val relation = sample(friendRequest = "incoming")
        assertEquals("accept", relation.friendAction)
        assertEquals("Принять запрос", relation.friendActionTitle)
    }

    @Test fun confirmedAndPendingFriendshipsCanBeRemoved() {
        assertEquals("remove", sample(friends = true).friendAction)
        assertEquals("remove", sample(friendRequest = "outgoing").friendAction)
        assertEquals("Отменить запрос", sample(friendRequest = "outgoing").friendActionTitle)
    }

    @Test fun newContactRequestsFriendship() {
        assertEquals("request", sample().friendAction)
        assertTrue(sample().canInteract)
    }

    @Test fun eitherBlockingDirectionDisablesInteractions() {
        assertFalse(sample(blockedByMe = true).canInteract)
        assertFalse(sample(blockedMe = true).canInteract)
    }
}
