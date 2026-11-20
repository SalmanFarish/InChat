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

    private fun prepared(deviceId: String): PreparedOlmDelivery {
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
        return PreparedOlmDelivery("bob", deviceId, "session-$deviceId", envelope)
    }
}
