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
    fun uploadedDeliveryIsPersistedAndReclaimedForExactRetryAfterAcknowledgementWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-uploaded-retry-test.db"
        context.deleteDatabase(name)
        val firstDatabase = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var deliveryId = ""
        try {
            val firstQueue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(firstDatabase, firstDatabase.olmDeliveryQueueDao()),
            )
            firstQueue.enqueue("alice", "sender-device", "relay-accepted", listOf(prepared("device-phone")))
            val claimed = firstQueue.claimNext("alice", "sender-device", 10_000L)!!
            deliveryId = claimed.deliveryId
            assertTrue(firstQueue.markUploaded(claimed, 10_000L))
            val uploaded = firstDatabase.olmDeliveryQueueDao().getByDeliveryId(deliveryId)
            assertEquals(OlmDeliveryStatus.UPLOADED.name, uploaded?.status)
            assertEquals(910_000L, uploaded?.nextAttemptAtMillis)
            assertEquals(0L, uploaded?.leaseUntilMillis)
        } finally {
            firstDatabase.close()
        }

        val reopenedDatabase = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val reopenedQueue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(reopenedDatabase, reopenedDatabase.olmDeliveryQueueDao()),
            )
            assertNull(reopenedQueue.claimNext("alice", "sender-device", 909_999L))
            val retry = reopenedQueue.claimNext("alice", "sender-device", 910_000L)
            assertEquals(deliveryId, retry?.deliveryId)
            assertEquals(2, retry?.attemptCount)
            assertEquals(OlmDeliveryStatus.IN_FLIGHT, retry?.status)
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
                        delivery = firstClaim,
                        nowMillis = 10_000L,
                        errorCode = "relay_upload_failed",
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
    fun duplicateEnqueueCannotReplaceCiphertextOrResetRetryState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-immutable-duplicate-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = db.olmDeliveryQueueDao()
            val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, dao))
            val original = prepared("device-phone")
            assertEquals(
                1,
                queue.enqueue("alice", "sender-device", "immutable-message", listOf(original)),
            )

            val firstClaim = queue.claimNext("alice", "sender-device", 10_000L)!!
            assertTrue(queue.retry(firstClaim, 10_000L, "temporary_relay_failure"))
            val originalRow = dao.getByDeliveryId(firstClaim.deliveryId)!!
            assertEquals(OlmDeliveryStatus.QUEUED.name, originalRow.status)
            assertEquals(12_000L, originalRow.nextAttemptAtMillis)
            assertEquals(1, originalRow.attemptCount)

            // A resumed producer may prepare the same message again and produce
            // different ciphertext. The established delivery ID must keep the
            // first durable envelope and retry metadata unchanged.
            val regenerated = original.copy(
                envelope = JSONObject(original.envelope)
                    .put(
                        "ciphertext",
                        Base64.encodeToString(
                            byteArrayOf(9, 8, 7),
                            Base64.URL_SAFE or Base64.NO_WRAP,
                        ),
                    )
                    .toString(),
            )
            assertTrue(regenerated.envelope != original.envelope)

            // The same enqueue batch also contains a previously unseen device.
            // The existing phone copy must be ignored while the tablet is inserted.
            assertEquals(
                1,
                queue.enqueue(
                    "alice",
                    "sender-device",
                    "immutable-message",
                    listOf(regenerated, prepared("device-tablet")),
                ),
            )

            val preserved = dao.getByDeliveryId(firstClaim.deliveryId)!!
            assertEquals(original.envelope, preserved.envelope)
            assertEquals(OlmDeliveryStatus.QUEUED.name, preserved.status)
            assertEquals(1, preserved.attemptCount)
            assertEquals(12_000L, preserved.nextAttemptAtMillis)
            assertEquals("temporary_relay_failure", preserved.lastError)

            val newlyInserted = dao.findNextDue("alice", "sender-device", 10_000L)
            assertNotNull(newlyInserted)
            assertEquals("device-tablet", newlyInserted?.recipientDeviceId)
            assertEquals("immutable-message", newlyInserted?.messageId)
            assertFalse(firstClaim.deliveryId == newlyInserted?.deliveryId)

            // Replaying the complete producer batch again is a no-op.
            assertEquals(
                0,
                queue.enqueue(
                    "alice",
                    "sender-device",
                    "immutable-message",
                    listOf(regenerated, prepared("device-tablet")),
                ),
            )
            val stillPreserved = dao.getByDeliveryId(firstClaim.deliveryId)!!
            assertEquals(original.envelope, stillPreserved.envelope)
            assertEquals(12_000L, stillPreserved.nextAttemptAtMillis)
            assertEquals(1, stillPreserved.attemptCount)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun duplicateEnqueueCannotResurrectTerminalFailedDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-terminal-duplicate-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = db.olmDeliveryQueueDao()
            val queue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(db, dao),
                maxAttempts = 1,
            )
            val original = prepared("device-phone")
            assertEquals(
                1,
                queue.enqueue("alice", "sender-device", "terminal-message", listOf(original)),
            )
            val claim = queue.claimNext("alice", "sender-device", 10_000L)!!
            assertTrue(queue.retry(claim, 10_000L, "permanent_relay_failure"))

            val failed = dao.getByDeliveryId(claim.deliveryId)!!
            assertEquals(OlmDeliveryStatus.FAILED.name, failed.status)
            assertEquals(1, failed.attemptCount)
            assertEquals("permanent_relay_failure", failed.lastError)

            val regenerated = original.copy(
                envelope = JSONObject(original.envelope)
                    .put(
                        "ciphertext",
                        Base64.encodeToString(
                            byteArrayOf(6, 5, 4),
                            Base64.URL_SAFE or Base64.NO_WRAP,
                        ),
                    )
                    .toString(),
            )
            assertEquals(
                0,
                queue.enqueue("alice", "sender-device", "terminal-message", listOf(regenerated)),
            )

            val stillFailed = dao.getByDeliveryId(claim.deliveryId)!!
            assertEquals(original.envelope, stillFailed.envelope)
            assertEquals(OlmDeliveryStatus.FAILED.name, stillFailed.status)
            assertEquals(1, stillFailed.attemptCount)
            assertEquals("permanent_relay_failure", stillFailed.lastError)
            assertNull(queue.claimNext("alice", "sender-device", Long.MAX_VALUE))
            assertEquals(stillFailed, dao.getByDeliveryId(claim.deliveryId))
        } finally {
            db.close()
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
                    claimed.copy(ownerUserId = "mallory"),
                    10_001L,
                ),
            )
            assertFalse(
                queue.acknowledge(
                    claimed.copy(senderDeviceId = "other-device"),
                    10_001L,
                ),
            )
            assertFalse(
                queue.retry(
                    claimed.copy(ownerUserId = "mallory"),
                    10_000L,
                    "wrong_account_retry",
                ),
            )
            assertFalse(
                queue.retry(
                    claimed.copy(senderDeviceId = "other-device"),
                    10_000L,
                    "wrong_device_retry",
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
                    claimed,
                    10_001L,
                ),
            )
            assertNull(queue.claimNext("alice", "sender-device", 20_000L))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun staleWorkerCannotChangeOrAcknowledgeAReclaimedRoomLease() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-queue-stale-lease-fence-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val queue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()),
                leaseDurationMillis = 100L,
            )
            queue.enqueue(
                "alice",
                "sender-device",
                "stale-room-lease",
                listOf(prepared("device-phone")),
            )
            val staleClaim = queue.claimNext("alice", "sender-device", 1_000L)!!
            val currentClaim = queue.claimNext("alice", "sender-device", 1_100L)!!
            assertEquals(1, staleClaim.attemptCount)
            assertEquals(2, currentClaim.attemptCount)

            // The old worker supplies a pre-expiry clock value. The DB generation
            // check, not just local lease-time validation, must reject its writes.
            assertFalse(queue.retry(staleClaim, 1_050L, "stale_worker_failure"))
            assertFalse(queue.acknowledge(staleClaim, 1_050L))

            val persisted = db.olmDeliveryQueueDao().getByDeliveryId(currentClaim.deliveryId)
            assertEquals(OlmDeliveryStatus.IN_FLIGHT.name, persisted?.status)
            assertEquals(2, persisted?.attemptCount)
            assertEquals(currentClaim.leaseUntilMillis, persisted?.leaseUntilMillis)
            assertNull(persisted?.lastError)

            assertTrue(queue.retry(currentClaim, 1_100L, "current_worker_failure"))
            val retried = db.olmDeliveryQueueDao().getByDeliveryId(currentClaim.deliveryId)
            assertEquals(OlmDeliveryStatus.QUEUED.name, retried?.status)
            assertEquals("current_worker_failure", retried?.lastError)
            assertEquals(5_100L, retried?.nextAttemptAtMillis)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun recoveryStateStoreCombinesQueueRowsAndDurableAcknowledgements() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-group-key-recovery-state-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val owner = "alice"
            val senderDevice = "sender-device"
            val distributionId = "group-key-7"
            val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()))
            assertEquals(
                2,
                queue.enqueue(
                    owner,
                    senderDevice,
                    distributionId,
                    listOf(
                        prepared("alice-tablet", senderDevice).copy(recipientUserId = owner),
                        prepared("bob-phone", senderDevice),
                    ),
                ),
            )
            val metadata = OlmGroupKeyDistributionRecord(
                ownerUserId = owner,
                senderDeviceId = senderDevice,
                distributionId = distributionId,
                groupId = "group-1",
                membershipEpoch = 7L,
                megolmSessionId = "1234567890abcdef",
                targetDevices = listOf(
                    owner to "alice-tablet",
                    "bob" to "bob-phone",
                    "carol" to "carol-phone",
                ),
            )
            db.olmGroupKeyDistributionMetadataStore().persistIfAbsent(metadata)
            kotlinx.coroutines.runBlocking {
                RoomOlmDeliveryAcknowledgementRecorder(db, db.olmDeliveryReceiptDao()).record(
                    OlmDeliveryAcknowledgement(
                        deliveryId = "c".repeat(64),
                        recipientUserId = "carol",
                        recipientDeviceId = "carol-phone",
                        messageId = distributionId,
                        senderUserId = owner,
                        senderDeviceId = senderDevice,
                    ),
                    1234L,
                )
            }

            val state = RoomOlmGroupKeyDistributionRecoveryStateStore(
                queueDao = db.olmDeliveryQueueDao(),
                receiptDao = db.olmDeliveryReceiptDao(),
            ).read(metadata.toEntity())

            assertEquals(
                setOf(owner to "alice-tablet", "bob" to "bob-phone"),
                state.queuedTargets,
            )
            assertEquals(setOf("carol" to "carol-phone"), state.acknowledgedTargets)
            assertTrue(state.failedTargets.isEmpty())
            val plan = OlmGroupKeyDistributionRecoveryPlanner.plan(
                metadata = metadata.toEntity(),
                queuedTargets = state.queuedTargets,
                acknowledgedTargets = state.acknowledgedTargets,
                failedTargets = state.failedTargets,
            )
            assertTrue(plan.missingTargets.isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun terminalFailedTargetIsNotMistakenForMissingRecoveryWork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-group-key-failed-recovery-target-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val distributionId = "group-key-failed"
            val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()))
            queue.enqueue(
                "alice",
                "sender-device",
                distributionId,
                listOf(prepared("bob-phone")),
            )
            val metadata = OlmGroupKeyDistributionRecord(
                ownerUserId = "alice",
                senderDeviceId = "sender-device",
                distributionId = distributionId,
                groupId = "group-1",
                membershipEpoch = 7L,
                megolmSessionId = "1234567890abcdef",
                targetDevices = listOf("bob" to "bob-phone"),
            ).toEntity()
            db.olmGroupKeyDistributionMetadataStore().persistIfAbsent(
                OlmGroupKeyDistributionRecord(
                    ownerUserId = metadata.ownerUserId,
                    senderDeviceId = metadata.senderDeviceId,
                    distributionId = metadata.distributionId,
                    groupId = metadata.groupId,
                    membershipEpoch = metadata.membershipEpoch,
                    megolmSessionId = metadata.megolmSessionId,
                    targetDevices = metadata.targetDevicePairs(),
                ),
            )
            db.openHelper.writableDatabase.execSQL(
                "UPDATE olm_device_deliveries SET status = 'FAILED' WHERE messageId = ?",
                arrayOf(distributionId),
            )

            val state = RoomOlmGroupKeyDistributionRecoveryStateStore(
                db.olmDeliveryQueueDao(),
                db.olmDeliveryReceiptDao(),
            ).read(metadata)
            val plan = OlmGroupKeyDistributionRecoveryPlanner.plan(
                metadata,
                state.queuedTargets,
                state.acknowledgedTargets,
                state.failedTargets,
            )

            assertTrue(state.queuedTargets.isEmpty())
            assertEquals(setOf("bob" to "bob-phone"), state.failedTargets)
            assertTrue(plan.missingTargets.isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun recoveryStateReaderRejectsOutboxTargetOutsideSavedDistribution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-group-key-unexpected-recovery-target-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val distributionId = "group-key-unexpected"
            val queue = OlmDeliveryQueue(RoomOlmDeliveryQueueStore(db, db.olmDeliveryQueueDao()))
            queue.enqueue(
                "alice",
                "sender-device",
                distributionId,
                listOf(prepared("mallory-phone")),
            )
            val metadata = OlmGroupKeyDistributionRecord(
                ownerUserId = "alice",
                senderDeviceId = "sender-device",
                distributionId = distributionId,
                groupId = "group-1",
                membershipEpoch = 7L,
                megolmSessionId = "1234567890abcdef",
                targetDevices = listOf("bob" to "bob-phone"),
            ).toEntity()
            db.olmGroupKeyDistributionMetadataStore().persistIfAbsent(
                OlmGroupKeyDistributionRecord(
                    ownerUserId = metadata.ownerUserId,
                    senderDeviceId = metadata.senderDeviceId,
                    distributionId = metadata.distributionId,
                    groupId = metadata.groupId,
                    membershipEpoch = metadata.membershipEpoch,
                    megolmSessionId = metadata.megolmSessionId,
                    targetDevices = metadata.targetDevicePairs(),
                ),
            )

            val state = RoomOlmGroupKeyDistributionRecoveryStateStore(
                db.olmDeliveryQueueDao(),
                db.olmDeliveryReceiptDao(),
            ).read(metadata)
            var rejected = false
            try {
                OlmGroupKeyDistributionRecoveryPlanner.plan(
                    metadata,
                    state.queuedTargets,
                    state.acknowledgedTargets,
                    state.failedTargets,
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
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
