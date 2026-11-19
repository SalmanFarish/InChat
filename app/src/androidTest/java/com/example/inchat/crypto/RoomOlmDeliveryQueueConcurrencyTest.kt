package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
            queue.enqueue("alice", "sender-device", "msg-concurrent", listOf(prepared("device-a"), prepared("device-b")))
            val pool = Executors.newFixedThreadPool(2)
            val gate = CountDownLatch(1)
            val futures = (1..2).map {
                pool.submit<QueuedOlmDeviceDelivery?> {
                    gate.await()
                    queue.claimNext("alice", "sender-device", 5_000)
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
    fun competingClaimsCannotLeaseOneDeliveryTwice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-single-claim-concurrency-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()))
        try {
            queue.enqueue(
                "alice",
                "sender-device",
                "msg-single-claim",
                listOf(prepared("device-only")),
            )
            val pool = Executors.newFixedThreadPool(2)
            val gate = CountDownLatch(1)
            try {
                val futures = (1..2).map {
                    pool.submit<QueuedOlmDeviceDelivery?> {
                        gate.await()
                        queue.claimNext("alice", "sender-device", 5_000)
                    }
                }
                gate.countDown()
                val claims = futures.map { it.get(10, TimeUnit.SECONDS) }
                val successfulClaims = claims.filterNotNull()

                assertEquals(1, successfulClaims.size)
                assertEquals(1, successfulClaims.single().attemptCount)
                assertNull(queue.claimNext("alice", "sender-device", 5_000))
                val persisted = db.olmDeliveryQueueDao()
                    .getByDeliveryId(successfulClaims.single().deliveryId)
                assertEquals(OlmDeliveryStatus.IN_FLIGHT.name, persisted?.status)
                assertEquals(1, persisted?.attemptCount)
            } finally {
                pool.shutdownNow()
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun concurrentWorkersUploadEachClaimedRoomDeliveryExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-worker-concurrency-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()))
        val uploadsStarted = CountDownLatch(2)
        val releaseUploads = CountDownLatch(1)
        val uploadedDeliveryIds = ConcurrentHashMap.newKeySet<String>()
        try {
            queue.enqueue(
                "alice",
                "sender-device",
                "msg-worker-concurrent",
                listOf(prepared("device-a"), prepared("device-b")),
            )

            val transport = object : OlmOutgoingDeliveryTransport {
                override suspend fun upload(
                    delivery: QueuedOlmDeviceDelivery,
                    senderDeviceId: String,
                ): FirebaseOlmUploadResult {
                    assertEquals("sender-device", senderDeviceId)
                    assertEquals(OlmDeliveryStatus.IN_FLIGHT, delivery.status)
                    assertTrue(
                        "A delivery must be uploaded by only one active worker",
                        uploadedDeliveryIds.add(delivery.deliveryId),
                    )
                    uploadsStarted.countDown()
                    check(releaseUploads.await(10, TimeUnit.SECONDS)) {
                        "Concurrent upload barrier was not released"
                    }
                    return FirebaseOlmUploadResult.STORED
                }

                override suspend fun readMyDeliveryAcknowledgements(
                    senderDeviceId: String,
                ): List<OlmDeliveryAcknowledgement> = emptyList()

                override suspend fun removeMyDeliveryAcknowledgement(
                    senderDeviceId: String,
                    deliveryId: String,
                ) = Unit
            }

            val runner = OlmDeliveryWorkerRunner(
                queue = queue,
                transport = transport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                clock = { 5_000L },
            )
            val pool = Executors.newFixedThreadPool(2)
            try {
                val futures = (1..2).map {
                    pool.submit<OlmDeliveryWorkerRunResult> {
                        runBlocking { runner.run() }
                    }
                }

                assertTrue(
                    "Both workers should hold distinct active leases before either upload completes",
                    uploadsStarted.await(10, TimeUnit.SECONDS),
                )
                releaseUploads.countDown()
                val results = futures.map { it.get(10, TimeUnit.SECONDS) }

                assertEquals(2, results.sumOf { it.uploaded })
                assertTrue(results.all { !it.retryRequired })
                assertEquals(2, uploadedDeliveryIds.size)
                uploadedDeliveryIds.forEach { deliveryId ->
                    val persisted = db.olmDeliveryQueueDao().getByDeliveryId(deliveryId)
                    assertEquals(OlmDeliveryStatus.IN_FLIGHT.name, persisted?.status)
                    assertEquals(1, persisted?.attemptCount)
                }
            } finally {
                releaseUploads.countDown()
                pool.shutdownNow()
            }
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun lateWorkerFailureCannotReleaseAReclaimedRoomLease() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-worker-stale-failure-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val queue = OlmDeliveryQueue(
            RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()),
            leaseDurationMillis = 100L,
        )
        val firstWorkerClock = AtomicLong(1_000L)
        val firstUploadStarted = CountDownLatch(1)
        val releaseFirstUpload = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            queue.enqueue(
                "alice",
                "sender-device",
                "msg-stale-worker",
                listOf(prepared("device-only")),
            )

            val firstTransport = object : OlmOutgoingDeliveryTransport {
                override suspend fun upload(
                    delivery: QueuedOlmDeviceDelivery,
                    senderDeviceId: String,
                ): FirebaseOlmUploadResult {
                    assertEquals(1, delivery.attemptCount)
                    firstUploadStarted.countDown()
                    check(releaseFirstUpload.await(10, TimeUnit.SECONDS)) {
                        "First upload barrier was not released"
                    }
                    throw IllegalStateException("late first-attempt failure")
                }

                override suspend fun readMyDeliveryAcknowledgements(
                    senderDeviceId: String,
                ): List<OlmDeliveryAcknowledgement> = emptyList()

                override suspend fun removeMyDeliveryAcknowledgement(
                    senderDeviceId: String,
                    deliveryId: String,
                ) = Unit
            }
            val firstRunner = OlmDeliveryWorkerRunner(
                queue = queue,
                transport = firstTransport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                clock = { firstWorkerClock.get() },
            )
            val firstFuture = pool.submit<OlmDeliveryWorkerRunResult> {
                runBlocking { firstRunner.run() }
            }

            assertTrue(
                "The first worker should hold its lease while the upload is suspended",
                firstUploadStarted.await(10, TimeUnit.SECONDS),
            )

            val secondTransport = object : OlmOutgoingDeliveryTransport {
                override suspend fun upload(
                    delivery: QueuedOlmDeviceDelivery,
                    senderDeviceId: String,
                ): FirebaseOlmUploadResult {
                    assertEquals(2, delivery.attemptCount)
                    return FirebaseOlmUploadResult.STORED
                }

                override suspend fun readMyDeliveryAcknowledgements(
                    senderDeviceId: String,
                ): List<OlmDeliveryAcknowledgement> = emptyList()

                override suspend fun removeMyDeliveryAcknowledgement(
                    senderDeviceId: String,
                    deliveryId: String,
                ) = Unit
            }
            val secondResult = pool.submit<OlmDeliveryWorkerRunResult> {
                runBlocking {
                    OlmDeliveryWorkerRunner(
                        queue = queue,
                        transport = secondTransport,
                        senderUserId = "alice",
                        senderDeviceId = "sender-device",
                        clock = { 1_100L },
                    ).run()
                }
            }.get(10, TimeUnit.SECONDS)

            assertEquals(1, secondResult.uploaded)
            assertFalse(secondResult.retryRequired)
            val reclaimed = db.olmDeliveryQueueDao().getByDeliveryId(
                OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()))
                    .let { queueForIdentity -> 
                        // The delivery ID is stable for this sender/message/device tuple.
                        queueForIdentity.claimNext("alice", "sender-device", 1_100L - 1L)
                            ?.deliveryId
                    }
            )
            // Read the current row by its message identity; the second worker's
            // successful upload must still own attempt 2's unexpired lease.
            val current = db.olmDeliveryQueueDao().findNextDue("alice", "sender-device", 1_100L)
            assertEquals(null, current)
            assertEquals("msg-stale-worker", reclaimed?.messageId)
            assertEquals(2, reclaimed?.attemptCount)
            assertEquals(1_200L, reclaimed?.leaseUntilMillis)

            // Simulate a late first worker with a stale clock. Its local lease
            // check passes, but Room's attempt/lease generation fence rejects it.
            firstWorkerClock.set(1_050L)
            releaseFirstUpload.countDown()
            val firstResult = firstFuture.get(10, TimeUnit.SECONDS)
            assertEquals(0, firstResult.uploaded)
            assertTrue(firstResult.retryRequired)

            val persisted = db.olmDeliveryQueueDao().getByDeliveryId(reclaimed!!.deliveryId)
            assertEquals(OlmDeliveryStatus.IN_FLIGHT.name, persisted?.status)
            assertEquals(2, persisted?.attemptCount)
            assertEquals(1_200L, persisted?.leaseUntilMillis)
            assertEquals(null, persisted?.lastError)
        } finally {
            releaseFirstUpload.countDown()
            pool.shutdownNow()
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
            queue.enqueue("alice", "sender-device", "msg-final", listOf(prepared("device-final")))
            val first = queue.claimNext("alice", "sender-device", 1_000)!!
            assertNull(queue.claimNext("alice", "sender-device", 1_100))
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
