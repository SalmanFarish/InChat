package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedMessageDeliveryStatusRepositoryTest {

    @Test
    fun observesQueuedSendingSentAndDeliveredUsingMetadataOnlyRoomState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "encrypted-delivery-status-test.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val queue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(database, database.olmDeliveryQueueDao()),
            )
            val repository = EncryptedMessageDeliveryStatusRepository(
                queueDao = database.olmDeliveryQueueDao(),
                receiptDao = database.olmDeliveryReceiptDao(),
            )

            queue.enqueue(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                messageId = "status-message",
                prepared = listOf(prepared("bob-phone")),
            )
            val statusFlow = repository.observe(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                recipientUserId = "bob",
                messageId = "status-message",
            )
            assertEquals(EncryptedMessageDeliveryStatus.WAITING, statusFlow.first())

            val claimed = queue.claimNext("alice", "alice-phone", 10_000L)!!
            assertEquals(EncryptedMessageDeliveryStatus.SENDING, statusFlow.first())

            assertTrue(queue.markUploaded(claimed, 10_001L))
            assertEquals(EncryptedMessageDeliveryStatus.SENT, statusFlow.first())

            RoomOlmDeliveryAcknowledgementRecorder(
                database = database,
                dao = database.olmDeliveryReceiptDao(),
            ).record(
                OlmDeliveryAcknowledgement(
                    deliveryId = claimed.deliveryId,
                    recipientUserId = "bob",
                    recipientDeviceId = "bob-phone",
                    messageId = "status-message",
                    senderUserId = "alice",
                    senderDeviceId = "alice-phone",
                ),
                10_002L,
            )
            assertEquals(EncryptedMessageDeliveryStatus.DELIVERED, statusFlow.first())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun conversationStatusesUseEncryptedOutboxAndDurableRecipientAcksOnly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "encrypted-conversation-status-test.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val queue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(database, database.olmDeliveryQueueDao()),
            )
            val repository = EncryptedMessageDeliveryStatusRepository(
                queueDao = database.olmDeliveryQueueDao(),
                receiptDao = database.olmDeliveryReceiptDao(),
            )

            val inserted = queue.enqueue(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                messageId = "direct-status",
                prepared = listOf(
                    prepared("bob-phone"),
                    prepared("bob-tablet"),
                ),
                directMessageCommit = DirectOutgoingMessageCommit(
                    ownerUserId = "alice",
                    conversationId = "alice_bob",
                    conversationPeerUserId = "bob",
                    messageId = "direct-status",
                    senderDeviceId = "alice-phone",
                    sentAtMillis = 20_000L,
                    recipientDeviceCount = 2,
                ),
                conversationId = "alice_bob",
                conversationPeerUserId = "bob",
            )
            assertEquals(2, inserted)

            val statusFlow = repository.observeConversation(
                ownerUserId = "alice",
                recipientUserId = "bob",
                conversationId = "alice_bob",
            )
            val waiting = statusFlow.first()
            assertEquals(EncryptedMessageDeliveryStatus.WAITING, waiting["direct-status"])

            val firstDelivery = queue.claimNext("alice", "alice-phone", 30_000L)!!
            assertTrue(queue.markUploaded(firstDelivery, 30_001L))
            assertEquals(
                EncryptedMessageDeliveryStatus.SENT,
                statusFlow.first()["direct-status"],
            )

            RoomOlmDeliveryAcknowledgementRecorder(
                database = database,
                dao = database.olmDeliveryReceiptDao(),
            ).record(
                OlmDeliveryAcknowledgement(
                    deliveryId = firstDelivery.deliveryId,
                    recipientUserId = "bob",
                    recipientDeviceId = firstDelivery.recipientDeviceId,
                    messageId = "direct-status",
                    senderUserId = "alice",
                    senderDeviceId = "alice-phone",
                ),
                30_002L,
            )
            assertEquals(
                EncryptedMessageDeliveryStatus.DELIVERED,
                statusFlow.first()["direct-status"],
            )

            // The same projection must exclude messages from another conversation.
            queue.enqueue(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                messageId = "other-conversation-status",
                prepared = listOf(prepared("carol-phone", "carol")),
                directMessageCommit = DirectOutgoingMessageCommit(
                    ownerUserId = "alice",
                    conversationId = "alice_carol",
                    conversationPeerUserId = "carol",
                    messageId = "other-conversation-status",
                    senderDeviceId = "alice-phone",
                    sentAtMillis = 21_000L,
                    recipientDeviceCount = 1,
                ),
                conversationId = "alice_carol",
                conversationPeerUserId = "carol",
            )
            assertTrue(
                "Other conversations must be excluded",
                "other-conversation-status" !in statusFlow.first(),
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun terminalDirectDeliveriesCanOnlyBeRequeuedForExplicitlyTrustedDevices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "encrypted-direct-retry-test.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.olmDeliveryQueueDao()
            val queue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(database, dao),
            )
            queue.enqueue(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                messageId = "retry-direct",
                prepared = listOf(
                    prepared("bob-phone"),
                    prepared("bob-tablet"),
                ),
                directMessageCommit = DirectOutgoingMessageCommit(
                    ownerUserId = "alice",
                    conversationId = "alice_bob",
                    conversationPeerUserId = "bob",
                    messageId = "retry-direct",
                    senderDeviceId = "alice-phone",
                    sentAtMillis = 50_000L,
                    recipientDeviceCount = 2,
                ),
                conversationId = "alice_bob",
                conversationPeerUserId = "bob",
            )

            val first = queue.claimNext("alice", "alice-phone", 60_000L)!!
            assertTrue(queue.failPermanently(first, 60_001L, "recipient_untrusted"))
            val second = queue.claimNext("alice", "alice-phone", 60_002L)!!
            assertTrue(queue.failPermanently(second, 60_003L, "recipient_untrusted"))

            val trustedRows = dao.getFailedDirectDeliveriesForTrustedDevices(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                recipientUserId = "bob",
                conversationId = "alice_bob",
                trustedRecipientDeviceIds = listOf("bob-phone"),
            )
            assertEquals(1, trustedRows.size)
            assertEquals("bob-phone", trustedRows.single().recipientDeviceId)
            val originalDeliveryId = trustedRows.single().deliveryId
            val originalEnvelope = trustedRows.single().envelope

            assertTrue(queue.retryFailed(trustedRows.single().toDelivery(), 70_000L))
            val requeued = dao.getByDeliveryId(originalDeliveryId)!!
            assertEquals(OlmDeliveryStatus.QUEUED.name, requeued.status)
            assertEquals(originalEnvelope, requeued.envelope)
            assertEquals(
                OlmDeliveryStatus.FAILED.name,
                dao.getByDeliveryId(second.deliveryId)!!.status,
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun prepared(
        deviceId: String,
        recipientUserId: String = "bob",
    ): PreparedOlmDelivery {
        val envelope = JSONObject()
            .put("encryptionVersion", 1)
            .put("algorithm", "olm")
            .put("senderDeviceId", "alice-phone")
            .put("recipientDeviceId", deviceId)
            .put("sessionId", "session-$deviceId")
            .put(
                "ciphertext",
                Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.URL_SAFE or Base64.NO_WRAP),
            )
            .toString()
        return PreparedOlmDelivery(recipientUserId, deviceId, "session-$deviceId", envelope)
    }
}
