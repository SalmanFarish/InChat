package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
