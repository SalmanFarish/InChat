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
import org.junit.Assert.assertNull
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
        assertEquals(1, firstQueue.enqueue("alice", "sender-device", "persistent-message", listOf(prepared("device-phone"))))
        firstDatabase.close()

        val secondDatabase = Room.databaseBuilder(
            context,
            LocalChatDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        val secondQueue = OlmDeliveryQueue(
            RoomOlmDeliveryQueueStore(secondDatabase, secondDatabase.olmDeliveryQueueDao()),
        )
        val claimed = secondQueue.claimNext("alice", "sender-device", 10_000)
        assertNotNull(claimed)
        assertEquals("persistent-message", claimed?.messageId)
        assertEquals(1, claimed?.attemptCount)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, claimed?.status)
        secondDatabase.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun inFlightDeliverySurvivesDatabaseReopenAndIsReclaimedAfterLeaseExpiry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-inflight-recovery-test.db"
        context.deleteDatabase(name)

        val firstDatabase = Room.databaseBuilder(
            context,
            LocalChatDatabase::class.java,
            name,
        ).allowMainThreadQueries().build()
        val firstQueue = OlmDeliveryQueue(
            RoomOlmDeliveryQueueStore(firstDatabase, firstDatabase.olmDeliveryQueueDao()),
            leaseDurationMillis = 100L,
        )
        firstQueue.enqueue("alice", "sender-device", "interrupted-message", listOf(prepared("device-phone")))
        val interrupted = firstQueue.claimNext("alice", "sender-device", 1_000L)!!
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, interrupted.status)
        assertEquals(1, interrupted.attemptCount)
        firstDatabase.close()

        val reopenedDatabase = Room.databaseBuilder(
            context,
            LocalChatDatabase::class.java,
            name,
        ).allowMainThreadQueries().build()
        try {
            val reopenedQueue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(reopenedDatabase, reopenedDatabase.olmDeliveryQueueDao()),
                leaseDurationMillis = 100L,
            )

            assertNull(reopenedQueue.claimNext("alice", "sender-device", 1_099L))

            val recovered = reopenedQueue.claimNext("alice", "sender-device", 1_100L)
            assertNotNull(recovered)
            assertEquals(interrupted.deliveryId, recovered?.deliveryId)
            assertEquals(interrupted.envelope, recovered?.envelope)
            assertEquals(2, recovered?.attemptCount)
            assertEquals(OlmDeliveryStatus.IN_FLIGHT, recovered?.status)
        } finally {
            reopenedDatabase.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun retryBackoffSurvivesDatabaseReopenAndReclaimsTheSameDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-retry-persistence-test.db"
        context.deleteDatabase(name)
        var deliveryId = ""

        try {
            val firstDatabase = Room.databaseBuilder(
                context,
                LocalChatDatabase::class.java,
                name,
            ).allowMainThreadQueries().build()
            try {
                val firstQueue = OlmDeliveryQueue(
                    RoomOlmDeliveryQueueStore(firstDatabase, firstDatabase.olmDeliveryQueueDao()),
                )
                assertEquals(
                    1,
                    firstQueue.enqueue(
                        "alice",
                        "sender-device",
                        "retry-after-restart",
                        listOf(prepared("device-phone")),
                    ),
                )
                val firstClaim = firstQueue.claimNext("alice", "sender-device", 10_000L)!!
                deliveryId = firstClaim.deliveryId
                assertEquals(1, firstClaim.attemptCount)
                assertTrue(
                    firstQueue.retry(
                        deliveryId = firstClaim.deliveryId,
                        messageId = firstClaim.messageId,
                        nowMillis = 10_000L,
                        errorCode = "relay_upload_failed",
                        ownerUserId = "alice",
                        senderDeviceId = "sender-device",
                    ),
                )
                val queued = firstDatabase.olmDeliveryQueueDao().getByDeliveryId(deliveryId)
                assertEquals(OlmDeliveryStatus.QUEUED.name, queued?.status)
                assertEquals(12_000L, queued?.nextAttemptAtMillis)
                assertEquals("relay_upload_failed", queued?.lastError)
            } finally {
                firstDatabase.close()
            }

            val reopenedDatabase = Room.databaseBuilder(
                context,
                LocalChatDatabase::class.java,
                name,
            ).allowMainThreadQueries().build()
            try {
                val reopenedQueue = OlmDeliveryQueue(
                    RoomOlmDeliveryQueueStore(reopenedDatabase, reopenedDatabase.olmDeliveryQueueDao()),
                )
                assertNull(reopenedQueue.claimNext("alice", "sender-device", 11_999L))

                val recovered = reopenedQueue.claimNext("alice", "sender-device", 12_000L)
                assertNotNull(recovered)
                assertEquals(deliveryId, recovered?.deliveryId)
                assertEquals("retry-after-restart", recovered?.messageId)
                assertEquals(2, recovered?.attemptCount)
                assertEquals(OlmDeliveryStatus.IN_FLIGHT, recovered?.status)
                assertEquals("relay_upload_failed", recovered?.lastError)
            } finally {
                reopenedDatabase.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
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
            queue.enqueue("alice", "sender-device", "ack-message", listOf(prepared("device-phone")))
            val queued = db.olmDeliveryQueueDao().findNextDue("alice", "sender-device", 10_000)!!
            val acknowledgement = OlmDeliveryAcknowledgement(
                deliveryId = queued.deliveryId,
                recipientUserId = queued.recipientUserId,
                recipientDeviceId = queued.recipientDeviceId,
                messageId = queued.messageId,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
            )

            assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(messageId = "wrong-message")))
            assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(recipientUserId = "wrong-user")))
            assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(recipientDeviceId = "wrong-device")))
            assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(senderUserId = "wrong-user")))
            assertFalse(queue.hasMatchingDelivery(acknowledgement.copy(senderDeviceId = "wrong-sender-device")))
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

    @Test
    fun claimsRemainIsolatedAcrossAccountsAndSenderDevices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-scope-isolation-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()))
            assertEquals(
                1,
                queue.enqueue(
                    "alice",
                    "sender-device",
                    "same-message-id",
                    listOf(prepared("device-phone", "sender-device")),
                ),
            )
            assertEquals(
                1,
                queue.enqueue(
                    "alice",
                    "sender-device-b",
                    "same-message-id",
                    listOf(prepared("device-phone", "sender-device-b")),
                ),
            )
            assertEquals(
                1,
                queue.enqueue(
                    "bob",
                    "sender-device",
                    "same-message-id",
                    listOf(prepared("device-phone", "sender-device")),
                ),
            )

            val aliceDeviceA = queue.claimNext("alice", "sender-device", 10_000L)
            assertNotNull(aliceDeviceA)
            assertEquals("alice", aliceDeviceA?.ownerUserId)
            assertEquals("sender-device", aliceDeviceA?.senderDeviceId)
            assertNull(queue.claimNext("alice", "sender-device", 10_000L))

            val aliceDeviceB = queue.claimNext("alice", "sender-device-b", 10_000L)
            assertNotNull(aliceDeviceB)
            assertEquals("alice", aliceDeviceB?.ownerUserId)
            assertEquals("sender-device-b", aliceDeviceB?.senderDeviceId)

            val bobDeviceA = queue.claimNext("bob", "sender-device", 10_000L)
            assertNotNull(bobDeviceA)
            assertEquals("bob", bobDeviceA?.ownerUserId)
            assertEquals("sender-device", bobDeviceA?.senderDeviceId)

            assertFalse(aliceDeviceA?.deliveryId == aliceDeviceB?.deliveryId)
            assertFalse(aliceDeviceA?.deliveryId == bobDeviceA?.deliveryId)
            assertFalse(aliceDeviceB?.deliveryId == bobDeviceA?.deliveryId)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun wrongAccountOrDeviceCannotAcknowledgeOrRetryAnotherRoomLease() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-scope-transition-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = db.olmDeliveryQueueDao()
            val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, dao))
            queue.enqueue(
                "alice",
                "sender-device",
                "scope-transition-message",
                listOf(prepared("device-phone")),
            )
            val claimed = queue.claimNext("alice", "sender-device", 10_000L)!!
            assertEquals(1, claimed.attemptCount)

            assertFalse(
                queue.acknowledge(
                    claimed.deliveryId,
                    claimed.messageId,
                    "mallory",
                    "sender-device",
                ),
            )
            assertFalse(
                queue.acknowledge(
                    claimed.deliveryId,
                    claimed.messageId,
                    "alice",
                    "other-device",
                ),
            )
            assertFalse(
                queue.retry(
                    claimed.deliveryId,
                    claimed.messageId,
                    10_000L,
                    "wrong_account_retry",
                    "mallory",
                    "sender-device",
                ),
            )
            assertFalse(
                queue.retry(
                    claimed.deliveryId,
                    claimed.messageId,
                    10_000L,
                    "wrong_device_retry",
                    "alice",
                    "other-device",
                ),
            )

            val unchanged = dao.getByDeliveryId(claimed.deliveryId)
            assertEquals(OlmDeliveryStatus.IN_FLIGHT.name, unchanged?.status)
            assertEquals(1, unchanged?.attemptCount)
            assertEquals(claimed.leaseUntilMillis, unchanged?.leaseUntilMillis)
            assertEquals(0L, unchanged?.nextAttemptAtMillis)
            assertNull(unchanged?.lastError)

            assertTrue(
                queue.acknowledge(
                    claimed.deliveryId,
                    claimed.messageId,
                    "alice",
                    "sender-device",
                ),
            )
            assertNull(queue.claimNext("alice", "sender-device", 20_000L))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private fun prepared(deviceId: String, senderDeviceId: String = "sender-device"): PreparedOlmDelivery {
        val envelope = JSONObject()
            .put("encryptionVersion", 1)
            .put("algorithm", "olm")
            .put("senderDeviceId", senderDeviceId)
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
