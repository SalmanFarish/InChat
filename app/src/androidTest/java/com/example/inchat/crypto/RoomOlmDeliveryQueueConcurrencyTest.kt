package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RoomOlmDeliveryQueueConcurrencyTest {
    @Test
    fun competingClaimsCannotLeaseTheSameDeviceDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-concurrency-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()))
            queue.enqueue("msg-concurrent", listOf(prepared("device-a"), prepared("device-b")))
            val pool = Executors.newFixedThreadPool(2)
            val gate = CountDownLatch(1)
            val futures = (1..2).map {
                pool.submit<QueuedOlmDeviceDelivery?> {
                    gate.await()
                    queue.claimNext(5_000)
                }
            }
            gate.countDown()
            val claimed = futures.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            pool.shutdownNow()
            assertEquals(2, claimed.map { it.deliveryId }.distinct().size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun expiredFinalAttemptIsMarkedFailedAndNotClaimedAgain() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-attempt-limit-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val queue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()),
                maxAttempts = 1,
                leaseDurationMillis = 100,
            )
            queue.enqueue("msg-final", listOf(prepared("device-final")))
            val first = queue.claimNext(1_000)!!
            assertNull(queue.claimNext(1_100))
            val stored = db.olmDeliveryQueueDao().getByDeliveryId(first.deliveryId)
            assertEquals(OlmDeliveryStatus.FAILED.name, stored?.status)
            assertEquals("attempt_limit_reached", stored?.lastError)
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
            .put("ciphertext", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.URL_SAFE or Base64.NO_WRAP))
            .toString()
        return PreparedOlmDelivery("bob", deviceId, "session-$deviceId", envelope)
    }
}
