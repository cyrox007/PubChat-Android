package ru.pubchat.app.core.network

import org.junit.Assert.*
import org.junit.Test

class MultiPersonaContractTest {
    @Test fun sessionSelectorKeepsPrimaryFlagSeparateFromAccount() {
        val a = PubChatApi.PersonaSummary(
            uid = "one", handle = "main", displayName = "Main", isPrimary = true,
        )
        val b = PubChatApi.PersonaSummary(
            uid = "two", handle = "alt", displayName = "Secondary", isPrimary = false,
        )
        val list = PubChatApi.PersonaList(listOf(a, b), activePersonaUid = b.uid)
        assertEquals(2, list.personas.size)
        assertFalse(list.personas[1].isPrimary)
        assertTrue(list.personas[0].isPrimary)
        assertEquals("two", list.activePersonaUid)
    }
}
