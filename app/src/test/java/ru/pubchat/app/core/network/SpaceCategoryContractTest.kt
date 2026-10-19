package ru.pubchat.app.core.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCategoryContractTest {
    private val api = PubChatApi(deviceUid = { "test-device" }, accessToken = { null }, refreshCookie = { null })

    @Test
    fun spaceProjectsServerOwnedCategorySlugs() {
        val payload = JSONObject(
            """{"uid":"11111111-1111-4111-8111-111111111111",
                "name":"Дизайн-комьюнити","category_slugs":["design","technology"]}"""
        )
        val space = api.parseSpace(payload)
        assertEquals(listOf("design", "technology"), space.categorySlugs)
    }

    @Test
    fun legacySpacesWithoutCategoriesRemainSupported() {
        val space = api.parseSpace(JSONObject("""{"uid":"any","name":"Старое пространство"}"""))
        assertTrue(space.categorySlugs.isEmpty())
    }

    @Test
    fun acceptsAtMostThreeUniqueWellFormedCategorySlugs() {
        api.validateSpaceCategorySlugs(emptyList())
        api.validateSpaceCategorySlugs(listOf("design", "technology", "games"))
    }

    @Test
    fun rejectsInvalidCategoryChangesBeforeNetwork() {
        val invalid = listOf(
            listOf("design", "games", "technology", "art"),
            listOf("design", "design"),
            listOf("bad slug"),
            listOf("../other"),
            listOf("Дизайн"),
        )
        invalid.forEach { slugs ->
            try {
                api.validateSpaceCategorySlugs(slugs)
                org.junit.Assert.fail("Unexpected valid assignment: $slugs")
            } catch (_: IllegalArgumentException) {
                // Expected: never send a malformed owner category update.
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidFilterSlugDoesNotMakeNetworkRequest() {
        api.spaces("../escape")
    }
}
