package com.example.inchat.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPayloadPolicyTest {

    @Test
    fun encryptedDirectPushIgnoresAllUntrustedDisplayContent() {
        val presentation = NotificationPayloadPolicy.from(
            data = mapOf(
                "type" to NotificationPayloadPolicy.TYPE_ENCRYPTED_CHAT,
                "chatId" to "chat-1",
                "senderId" to "sender-1",
                "messageId" to "message-1",
                "senderName" to "Private Sender",
                "message" to "Secret plaintext must never appear",
                "groupName" to "Private Group",
            ),
            fallbackTitle = "FCM title containing private data",
            fallbackBody = "FCM body containing private data",
        )

        assertEquals("InChat", presentation.title)
        assertEquals("New encrypted message", presentation.body)
        assertEquals("InChat", presentation.senderName)
        assertEquals("New encrypted message", presentation.messageText)
        assertEquals(NotificationPayloadPolicy.ROUTE_CHAT, presentation.notificationType)
        assertEquals("chat-1", presentation.chatId)
        assertEquals("sender-1", presentation.senderId)
        assertEquals("message-1", presentation.messageId)
        assertFalse(presentation.includeMessageHistory)
    }

    @Test
    fun encryptedGroupPushDoesNotRevealSenderOrGroupName() {
        val presentation = NotificationPayloadPolicy.from(
            data = mapOf(
                "type" to NotificationPayloadPolicy.TYPE_ENCRYPTED_GROUP,
                "chatId" to "group-1",
                "senderId" to "sender-1",
                "messageId" to "message-2",
                "senderName" to "Private Sender",
                "groupName" to "Private Group",
                "message" to "Secret group message",
            ),
            fallbackTitle = "Private Group",
            fallbackBody = "Secret group message",
        )

        assertEquals("InChat", presentation.title)
        assertEquals("New encrypted message", presentation.body)
        assertEquals(NotificationPayloadPolicy.ROUTE_GROUP, presentation.notificationType)
        assertFalse(presentation.includeMessageHistory)
    }

    @Test
    fun plaintextDirectPushRetainsExistingPresentationAndHistory() {
        val presentation = NotificationPayloadPolicy.from(
            data = mapOf(
                "type" to NotificationPayloadPolicy.TYPE_CHAT,
                "chatId" to "chat-1",
                "senderId" to "sender-1",
                "messageId" to "message-1",
                "senderName" to "@Alice",
                "message" to "Hello",
            ),
            fallbackTitle = null,
            fallbackBody = null,
        )

        assertEquals("@Alice", presentation.title)
        assertEquals("Hello", presentation.body)
        assertEquals("Alice", presentation.senderName)
        assertEquals("Hello", presentation.messageText)
        assertTrue(presentation.includeMessageHistory)
    }

    @Test
    fun plaintextGroupPushRetainsExistingGroupPresentation() {
        val presentation = NotificationPayloadPolicy.from(
            data = mapOf(
                "type" to NotificationPayloadPolicy.TYPE_GROUP,
                "chatId" to "group-1",
                "senderId" to "sender-1",
                "messageId" to "message-2",
                "senderName" to "Alice",
                "groupName" to "Friends",
                "message" to "Hello everyone",
            ),
            fallbackTitle = null,
            fallbackBody = null,
        )

        assertEquals("Friends", presentation.title)
        assertEquals("Alice: Hello everyone", presentation.body)
        assertEquals(NotificationPayloadPolicy.ROUTE_GROUP, presentation.notificationType)
        assertTrue(presentation.includeMessageHistory)
    }
}
