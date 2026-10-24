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

    @Test fun authenticatedDownloadsStayOnTheApiOrigin() {
        val base = "https://pubchat.ru/api"
        assertEquals("https://pubchat.ru/api/uploads/document.pdf",
            authorizedMediaUrl(base, "/uploads/document.pdf"))
        assertEquals("https://pubchat.ru/static/file.pdf",
            authorizedMediaUrl(base, "https://pubchat.ru/static/file.pdf"))
        assertNull(authorizedMediaUrl(base, "https://evil.example/file.pdf"))
        assertNull(authorizedMediaUrl(base, "https://pubchat.ru.evil.example/file.pdf"))
        assertNull(authorizedMediaUrl(base, "https://pubchat.ru@evil.example/file.pdf"))
        assertNull(authorizedMediaUrl(base, "http://pubchat.ru/file.pdf"))
        assertNull(authorizedMediaUrl(base, "https://pubchat.ru:8443/file.pdf"))
        assertNull(authorizedMediaUrl(base, "javascript:alert(1)"))
        assertNull(authorizedMediaUrl(base, "//evil.example/file.pdf"))
        assertNull(authorizedMediaUrl(base, "/../private"))
    }
}