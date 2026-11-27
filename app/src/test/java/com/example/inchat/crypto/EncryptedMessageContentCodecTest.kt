package com.example.inchat.crypto

import com.example.inchat.data.model.ReplyTo
import org.junit.Assert.assertEquals
import org.junit.Test

class EncryptedMessageContentCodecTest {
    @Test
    fun roundTripsReplyMetadataInsideEncryptedBody() {
        val reply = ReplyTo("message_123", "user_456", "Alice", "Quoted text")
        val encoded = EncryptedMessageContentCodec.encode(
            text = "My response",
            replyTo = reply,
            currentUserId = "user_789",
            currentUserNickname = "Bob",
        )
        val decoded = EncryptedMessageContentCodec.decode(encoded)
        assertEquals("My response", decoded.text)
        assertEquals(reply, decoded.replyTo)
    }

    @Test
    fun usesCurrentNicknameWhenReplyingToOwnMessage() {
        val encoded = EncryptedMessageContentCodec.encode(
            text = "Another response",
            replyTo = ReplyTo("message_1", "user_1", "You", "Original"),
            currentUserId = "user_1",
            currentUserNickname = "Bob",
        )
        assertEquals("Bob", EncryptedMessageContentCodec.decode(encoded).replyTo?.senderNickname)
    }

    @Test
    fun preservesLegacyPlainTextBodies() {
        val decoded = EncryptedMessageContentCodec.decode("hello")
        assertEquals("hello", decoded.text)
        assertEquals(null, decoded.replyTo)
    }
}
