package ru.pubchat.app.core.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatMessageParserTest {
    @Test fun serverUsernameAndRootReplySnapshotArePreserved() {
        val message = parseChatMessage(JSONObject("""{
          "uid":"reply", "content":"Ответ", "content_type":"text",
          "sender":{"uid":"author","username":"alex","avatar":"/avatar.jpg"},
          "reply_to":{"uid":"original","content":"Первое сообщение","sender":{"uid":"other","name":"Мария"}}
        }"""))!!
        assertEquals("alex", message.senderName)
        assertEquals("author", message.senderUid)
        assertEquals("/avatar.jpg", message.senderAvatar)
        assertEquals("original", message.replyToUid)
        assertEquals("Первое сообщение", message.replyContent)
        assertEquals("Мария", message.replySenderName)
    }
    @Test fun metadataReplyAndAllAttachmentUrlsArePreserved() {
        val message = parseChatMessage(JSONObject("""{
          "uid":"m","content":"Подпись","content_type":"image",
          "media_metadata":{"reply_to":{"uid":"source","content":"Цитата","sender":{"username":"kate"}},
          "files":[{"url":"/uploads/images/a.jpg","name":"a.jpg","type":"image/jpeg"},{"url":"/uploads/images/b.png","name":"b.png","type":"image/png"}]}
        }"""))!!
        assertEquals("source", message.replyToUid)
        assertEquals("kate", message.replySenderName)
        assertEquals(2, message.attachments.size)
        assertEquals("/uploads/images/b.png", message.attachments[1].url)
        assertEquals("Подпись", message.content)
    }
    @Test fun legacyVoiceUrlBecomesPlayableAttachment() {
        val message = parseChatMessage(JSONObject("""{"uid":"a","content_type":"audio","content":"/uploads/audio/test.webm"}"""))!!
        assertEquals("/uploads/audio/test.webm", message.attachments.single().url)
        assertEquals("Голосовое сообщение", message.attachments.single().name)
    }
    @Test fun nullValuesDoNotBecomeVisibleNullStrings() {
        assertNull(parseChatMessage(JSONObject("""{"uid":null}""")))
        val message = parseChatMessage(JSONObject("""{"uid":"m","sender":{"name":null,"username":"real"},"reply_to":null}"""))!!
        assertEquals("real", message.senderName)
        assertNull(message.replyToUid)
    }
}
