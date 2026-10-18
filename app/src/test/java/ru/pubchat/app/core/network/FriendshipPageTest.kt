package ru.pubchat.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FriendshipPageTest {
    @Test fun incomingRequestsUseAccountUidAndNestedPublicPersona() {
        val page = parseFriendshipPage(
            """{"status":"ok","direction":"incoming","requests":[
              {"account_uid":"account-1","persona":{"uid":"account-1",
               "persona_uid":"persona-1","handle":"fox","display_name":"Лис","avatar":null}},
              {"account_uid":"account-2","persona":null}],
              "pagination":{"limit":50,"offset":0,"count":2,"total":3}}""",
            "requests",
        )
        assertEquals(2, page.rows.size)
        assertEquals(3, page.total)
        assertEquals("account-1", page.rows[0].accountUid)
        assertEquals("Лис", page.rows[0].displayName)
        assertEquals("fox", page.rows[0].handle)
        assertNull(page.rows[0].avatar)
        assertEquals("Участник PubChat", page.rows[1].displayName)
    }

    @Test fun friendshipListSkipsMalformedRowsAndPreservesCount() {
        val page = parseFriendshipPage(
            """{"status":"ok","friends":[{"account_uid":"account-1",
               "persona":{"handle":"friend"}},{"account_uid":""},null],
               "pagination":{"total":7}}""",
            "friends",
        )
        assertEquals(1, page.rows.size)
        assertEquals("friend", page.rows[0].displayName)
        assertEquals(7, page.total)
    }
}
