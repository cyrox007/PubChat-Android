package ru.pubchat.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaUrlTest {
    @Test fun relativeMediaKeepsApiPrefixLikeWebClient() {
        assertEquals("https://pubchat.ru/api/uploads/avatar.webp", resolveMediaUrl("https://pubchat.ru/api", "/uploads/avatar.webp"))
        assertEquals("https://pubchat.ru/api/static/default_male.webp", resolveMediaUrl("https://pubchat.ru/api/", "static/default_male.webp"))
    }
    @Test fun absoluteMediaIsPreservedAndUnsafeSchemesAreRejected() {
        assertEquals("https://cdn.example/avatar.webp", resolveMediaUrl("https://pubchat.ru/api", "https://cdn.example/avatar.webp"))
        assertNull(resolveMediaUrl("https://pubchat.ru/api", "javascript:alert(1)"))
        assertNull(resolveMediaUrl("https://pubchat.ru/api", "//other.example/a"))
        assertNull(resolveMediaUrl("https://pubchat.ru/api", "/../private"))
    }
}