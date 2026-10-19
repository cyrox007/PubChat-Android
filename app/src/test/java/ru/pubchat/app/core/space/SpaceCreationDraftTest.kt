package ru.pubchat.app.core.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCreationDraftTest {
    @Test fun defaultPublicSpaceIsValidAndMatchesServerDefaults() {
        val draft = SpaceCreationDraft("Разговоры")
        assertNull(draft.validationError())
        assertEquals("public", draft.visibility)
        assertEquals("open", draft.joinPolicy)
        assertEquals("community", draft.purpose)
        assertEquals(250, draft.memberLimit)
    }

    @Test fun privateSpacesNeverAllowOpenOrRequestedJoin() {
        assertNull(SpaceCreationDraft("Клуб", visibility = "private", joinPolicy = "invite").validationError())
        assertEquals(
            "В закрытое пространство можно вступить только по приглашению.",
            SpaceCreationDraft("Клуб", visibility = "private", joinPolicy = "open").validationError()
        )
        assertTrue(SpaceCreationDraft("Клуб", visibility = "private", joinPolicy = "request")
            .validationError()!!.contains("приглашению"))
    }

    @Test fun categoryAssignmentsMustBeDistinctActiveAndBounded() {
        val valid = SpaceCreationDraft("Клуб", categorySlugs = listOf("design", "games", "friends"))
        assertNull(valid.validationError(setOf("friends", "design", "games")))
        assertTrue(SpaceCreationDraft("Клуб", categorySlugs = listOf("design", "design"))
            .validationError()!!.contains("трёх"))
        assertTrue(SpaceCreationDraft("Клуб", categorySlugs = listOf("a", "b", "c", "d"))
            .validationError()!!.contains("трёх"))
        assertTrue(valid.validationError(setOf("design", "games"))!!.contains("устарел"))
        assertNull(SpaceCreationDraft("Клуб").validationError(emptySet()))
        assertTrue(SpaceCreationDraft("Клуб", categorySlugs = listOf("../unsafe"))
            .validationError() != null)
    }

    @Test fun textSizesTagsAndCapacityMatchServerRequest() {
        assertTrue(SpaceCreationDraft("ok").validationError() != null)
        assertTrue(SpaceCreationDraft("Клуб", description = "x".repeat(501)).validationError() != null)
        assertTrue(SpaceCreationDraft("Клуб", memberLimit = 1).validationError() != null)
        assertTrue(SpaceCreationDraft("Клуб", memberLimit = 5001).validationError() != null)
        assertTrue(SpaceCreationDraft("Клуб", country = "a".repeat(101)).validationError() != null)
        assertTrue(SpaceCreationDraft("Клуб", tagsInput = "x".repeat(41)).validationError() != null)
        assertTrue(SpaceCreationDraft("Клуб", tagsInput = (1..9).joinToString(",") { "tag$it" })
            .validationError() != null)
        val draft = SpaceCreationDraft("Клуб", tagsInput = "Дизайн, дизайн, кино, кино")
        assertEquals(listOf("Дизайн", "кино"), draft.tags)
        assertNull(draft.validationError())
    }

    @Test fun unknownEnumsFailBeforeNetworkAndNoAssignmentsIsFine() {
        assertTrue(SpaceCreationDraft("Клуб", purpose = "anything").validationError() != null)
        assertTrue(SpaceCreationDraft("Клуб", visibility = "secret").validationError() != null)
        assertTrue(SpaceCreationDraft("Клуб", joinPolicy = "unknown").validationError() != null)
        assertNull(SpaceCreationDraft("Клуб", categorySlugs = emptyList()).validationError())
    }
}
