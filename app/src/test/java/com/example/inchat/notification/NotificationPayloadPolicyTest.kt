package com.example.inchat.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPayloadPolicyTest {

    @Test
    fun conversationNotificationIdsAreStableAndAvoidKnownStringHashCollisions() {
        // These strings have the same Java String.hashCode() but must not
        // overwrite each other's conversation notification.
        assertEquals("AaAaAa".hashCode(), "BBBBBB".hashCode())
        val first = NotificationPayloadPolicy.notificationIdForChat("AaAaAa", 1001)
        val second = NotificationPayloadPolicy.notificationIdForChat("BBBBBB", 1001)

        assertTrue(first > 0)
        assertTrue(second > 0)
        assertTrue(first != 1001)
        assertTrue(second != 1001)
        assertTrue(first != second)
        assertEquals(
            first,
            NotificationPayloadPolicy.notificationIdForChat("AaAaAa", 1001),
        )
    }

    @Test
    fun sameConversationIsNamespacedByReceivingAccount() {
        val firstAccountId = NotificationPayloadPolicy.notificationIdForChat(
            chatId = "shared-chat-id",
            fallbackId = 1001,
            recipientUserId = "account-one",
        )
        val secondAccountId = NotificationPayloadPolicy.notificationIdForChat(
            chatId = "shared-chat-id",
            fallbackId = 1001,
            recipientUserId = "account-two",
        )

        assertTrue(firstAccountId > 0)
        assertTrue(secondAccountId > 0)
        assertTrue(firstAccountId != secondAccountId)
        assertEquals(
            firstAccountId,
            NotificationPayloadPolicy.notificationIdForChat(
                chatId = "shared-chat-id",
                fallbackId = 1001,
                recipientUserId = "account-one",
            ),
        )
    }

    @Test
    fun notificationTagsAreFullLengthStableAndAccountScoped() {
        val first = NotificationPayloadPolicy.notificationTagForChat("AaAaAa", "account-one")
        val hashCollisionPeer = NotificationPayloadPolicy.notificationTagForChat("BBBBBB", "account-one")
        val secondAccount = NotificationPayloadPolicy.notificationTagForChat("AaAaAa", "account-two")

        assertEquals(64, first.length)
        assertTrue(first != hashCollisionPeer)
        assertTrue(first != secondAccount)
        assertEquals(
            first,
            NotificationPayloadPolicy.notificationTagForChat("AaAaAa", "account-one"),
        )
    }

    @Test
    fun notificationHistoryKeysAreStableOpaqueAndAccountScoped() {
        val first = NotificationPayloadPolicy.notificationHistoryKey("account-one", "shared-chat")
        val second = NotificationPayloadPolicy.notificationHistoryKey("account-two", "shared-chat")

        assertEquals(
            first,
            NotificationPayloadPolicy.notificationHistoryKey("account-one", "shared-chat"),
        )
        assertTrue(first != second)
        assertFalse(first.contains("account-one"))
        assertFalse(first.contains("shared-chat"))
    }

    @Test
    fun blankConversationUsesTheNotificationFallbackId() {
        assertEquals(
            1001,
            NotificationPayloadPolicy.notificationIdForChat("  ", 1001),
        )
    }

    @Test
    fun messagePayloadRequiresKnownTypeAndStableRoutingIdentifiers() {
        assertTrue(
            NotificationPayloadPolicy.isValidMessagePayload(
                mapOf(
                    "type" to NotificationPayloadPolicy.TYPE_CHAT,
                    "chatId" to "chat-1",
                    "messageId" to "message-1",
                    "senderId" to "sender-1",
                ),
            ),
        )
        assertTrue(
            NotificationPayloadPolicy.isValidMessagePayload(
                mapOf(
                    "type" to NotificationPayloadPolicy.TYPE_GROUP,
                    "chatId" to "group-1",
                    "messageId" to "message-2",
                ),
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isValidMessagePayload(
                mapOf(
                    "type" to NotificationPayloadPolicy.TYPE_CHAT,
                    "chatId" to "chat-1",
                    "messageId" to "message-1",
                ),
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isValidMessagePayload(
                mapOf(
                    "type" to NotificationPayloadPolicy.TYPE_GROUP,
                    "chatId" to "group-1",
                ),
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isValidMessagePayload(
                mapOf(
                    "type" to "unknown",
                    "chatId" to "chat-1",
                    "messageId" to "message-1",
                ),
            ),
        )
    }

    @Test
    fun addressedPushIsAcceptedOnlyByItsIntendedSignedInAccount() {
        val payload = mapOf(
            "type" to NotificationPayloadPolicy.TYPE_CHAT,
            "recipientUserId" to "account-one",
        )

        assertTrue(NotificationPayloadPolicy.isIntendedForAccount(payload, "account-one"))
        assertFalse(NotificationPayloadPolicy.isIntendedForAccount(payload, "account-two"))
        assertFalse(NotificationPayloadPolicy.isIntendedForAccount(payload, ""))
    }

    @Test
    fun largeGroupTopicPushRequiresMatchingRecipientAccountTag() {
        val topicPayload = mapOf(
            "type" to NotificationPayloadPolicy.TYPE_GROUP,
            "deliveryScope" to "group_topic",
            "recipientAccountTags" to NotificationPayloadPolicy.recipientAccountTag("account-one"),
        )
        assertTrue(
            NotificationPayloadPolicy.isIntendedForAccount(topicPayload, "account-one"),
        )
        assertFalse(
            NotificationPayloadPolicy.isIntendedForAccount(topicPayload, "account-two"),
        )
        assertFalse(
            NotificationPayloadPolicy.isIntendedForAccount(topicPayload, ""),
        )
    }

    @Test
    fun largeGroupTopicPushWithoutRecipientTagsIsRejected() {
        assertFalse(
            NotificationPayloadPolicy.isIntendedForAccount(
                mapOf(
                    "type" to NotificationPayloadPolicy.TYPE_GROUP,
                    "deliveryScope" to "group_topic",
                ),
                "account-one",
            ),
        )
    }

    @Test
    fun unmarkedUnaddressedGroupPushIsRejected() {
        assertFalse(
            NotificationPayloadPolicy.isIntendedForAccount(
                mapOf(
                    "type" to NotificationPayloadPolicy.TYPE_GROUP,
                    "chatId" to "group-1",
                    "messageId" to "message-1",
                ),
                "account-one",
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isIntendedForAccount(
                mapOf(
                    "type" to NotificationPayloadPolicy.TYPE_GROUP,
                    "deliveryScope" to "other",
                ),
                "account-one",
            ),
        )
    }

    @Test
    fun unaddressedOrBlankRecipientDirectPushIsRejected() {
        assertFalse(
            NotificationPayloadPolicy.isIntendedForAccount(
                mapOf("type" to NotificationPayloadPolicy.TYPE_CHAT),
                "account-one",
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isIntendedForAccount(
                mapOf(
                    "type" to NotificationPayloadPolicy.TYPE_CHAT,
                    "recipientUserId" to " ",
                ),
                "account-one",
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isIntendedForAccount(
                mapOf("type" to NotificationPayloadPolicy.TYPE_CHAT),
                "",
            ),
        )
    }

    @Test
    fun encryptedOlmWakeupAcceptsOnlyItsMinimalDeliveryReference() {
        assertTrue(
            NotificationPayloadPolicy.isEncryptedOlmWakeup(
                mapOf(
                    "type" to "encrypted_olm",
                    "deliveryId" to "a".repeat(64),
                ),
            ),
        )
    }

    @Test
    fun encryptedOlmWakeupRejectsExtraFieldsAndInvalidDeliveryIds() {
        assertFalse(
            NotificationPayloadPolicy.isEncryptedOlmWakeup(
                mapOf(
                    "type" to "encrypted_olm",
                    "deliveryId" to "a".repeat(64),
                    "chatId" to "untrusted-chat",
                ),
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isEncryptedOlmWakeup(
                mapOf(
                    "type" to "encrypted_olm",
                    "deliveryId" to "not-a-delivery-id",
                ),
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isEncryptedOlmWakeup(
                mapOf("type" to "encrypted_message"),
            ),
        )
    }

    @Test
    fun malformedEncryptedOlmWakeupRemainsReservedAndCannotBeTreatedAsPlaintext() {
        val malformed = mapOf(
            "type" to NotificationPayloadPolicy.TYPE_ENCRYPTED_OLM_WAKEUP,
            "deliveryId" to "invalid",
            "message" to "untrusted plaintext",
            "senderName" to "untrusted sender",
        )

        assertTrue(NotificationPayloadPolicy.isEncryptedOlmWakeupType(malformed))
        assertFalse(NotificationPayloadPolicy.isEncryptedOlmWakeup(malformed))
        assertFalse(
            NotificationPayloadPolicy.isEncryptedOlmWakeupType(
                mapOf("type" to NotificationPayloadPolicy.TYPE_CHAT),
            ),
        )
    }

    @Test
    fun encryptedOlmAcknowledgementWakeupAcceptsOnlyExactSenderDeviceReference() {
        assertTrue(
            NotificationPayloadPolicy.isEncryptedOlmAckWakeup(
                mapOf(
                    "type" to NotificationPayloadPolicy.TYPE_ENCRYPTED_OLM_ACK_WAKEUP,
                    "deliveryId" to "a".repeat(64),
                    "senderDeviceId" to "sender-device",
                ),
            ),
        )
        assertTrue(
            NotificationPayloadPolicy.isEncryptedOlmAckWakeupType(
                mapOf("type" to NotificationPayloadPolicy.TYPE_ENCRYPTED_OLM_ACK_WAKEUP),
            ),
        )
    }

    @Test
    fun encryptedOlmAcknowledgementWakeupRejectsExtraFieldsAndInvalidIdentifiers() {
        val type = NotificationPayloadPolicy.TYPE_ENCRYPTED_OLM_ACK_WAKEUP
        assertFalse(
            NotificationPayloadPolicy.isEncryptedOlmAckWakeup(
                mapOf("type" to type, "deliveryId" to "a".repeat(64),
                    "senderDeviceId" to "sender-device", "message" to "untrusted"),
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isEncryptedOlmAckWakeup(
                mapOf("type" to type, "deliveryId" to "invalid", "senderDeviceId" to "sender-device"),
            ),
        )
        assertFalse(
            NotificationPayloadPolicy.isEncryptedOlmAckWakeup(
                mapOf("type" to type, "deliveryId" to "a".repeat(64), "senderDeviceId" to "../other"),
            ),
        )
    }

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
    fun legacyDirectPushNeverPersistsOrDisplaysPlaintext() {
        val presentation = NotificationPayloadPolicy.from(
            data = mapOf(
                "type" to NotificationPayloadPolicy.TYPE_CHAT,
                "chatId" to "chat-1",
                "senderId" to "sender-1",
                "messageId" to "message-1",
                "senderName" to "Private Sender",
                "message" to "Secret message body",
            ),
            fallbackTitle = "Private FCM title",
            fallbackBody = "Private FCM body",
        )

        assertEquals("InChat", presentation.title)
        assertEquals("You received a new message", presentation.body)
        assertEquals("InChat", presentation.senderName)
        assertEquals("You received a new message", presentation.messageText)
        assertFalse(presentation.includeMessageHistory)
    }

    @Test
    fun legacyGroupPushNeverPersistsOrDisplaysPlaintext() {
        val presentation = NotificationPayloadPolicy.from(
            data = mapOf(
                "type" to NotificationPayloadPolicy.TYPE_GROUP,
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
        assertEquals("You received a new message", presentation.body)
        assertEquals(NotificationPayloadPolicy.ROUTE_GROUP, presentation.notificationType)
        assertFalse(presentation.includeMessageHistory)
    }
}
