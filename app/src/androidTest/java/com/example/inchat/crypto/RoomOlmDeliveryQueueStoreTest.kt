package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomOlmDeliveryQueueStoreTest {
    @Test
    fun ciphertextOutboxSurvivesDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "olm-queue-persistence-test.db"
        context.deleteDatabase(databaseName)

        val firstDatabase = Room.databaseBuilder(
            context,
            LocalChatDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        val firstQueue = OlmDeliveryQueue(
            RoomOlmDeliveryQueueStore(firstDatabase, firstDatabase.olmDeliveryQueueDao()),
        )
        assertEquals(1, firstQueue.enqueue("persistent-message", listOf(prepared("device-phone"))))
        firstDatabase.close()

        val secondDatabase = Room.databaseBuilder(
            context,
            LocalChatDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        val secondQueue = OlmDeliveryQueue(
            RoomOlmDeliveryQueueStore(secondDatabase, secondDatabase.olmDeliveryQueueDao()),
        )
        val claimed = secondQueue.claimNext(10_000)
        assertNotNull(claimed)
        assertEquals("persistent-message", claimed?.messageId)
        assertEquals(1, claimed?.attemptCount)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, claimed?.status)
        secondDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun acknowledgementDeletesOnlyTheExactQueuedDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-acknowledgement-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()))
            queue.enqueue("ack-message", listOf(prepared("device-phone")))
            val queued = db.olmDeliveryQueueDao().findNextDue(10_000)!!
            val acknowledgement = OlmDeliveryAcknowledgement(
                deliveryId = queued.deliveryId,
                recipientUserId = queued.recipientUserId,
                recipientDeviceId = queued.recipientDeviceId,
                messageId = queued.messageId,
            )

            assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(messageId = "wrong-message")))
            assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(recipientUserId = "wrong-user")))
            assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(recipientDeviceId = "wrong-device")))
            assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(deliveryId = "0".repeat(64))))
            assertFalse(queue.removeAcknowledgedDelivery(acknowledgement.copy(messageId = "wrong-message")))
            assertTrue(queue.hasMatchingDelivery(acknowledgement))

            // An ack can only remove the exact delivery, even while it is still QUEUED.
            assertTrue(queue.removeAcknowledgedDelivery(acknowledgement))
            assertFalse(queue.hasMatchingDelivery(acknowledgement))
            assertFalse(queue.removeAcknowledgedDelivery(acknowledgement))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun prepared(deviceId: String): PreparedOlmDelivery {
        val envelope = JSONObject()
            .put("encryptionVersion", 1)
            .put("algorithm", "olm")
            .put("senderDeviceId", "sender-device")
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
