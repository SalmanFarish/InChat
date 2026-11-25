package com.example.inchat.crypto

import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmDeliveryWorkerRunnerTest {
    @Test
    fun processesExactAcknowledgementsBeforeUploadingOnlyTheActiveAccountDevice() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "already-received", listOf(prepared("sender-device", "phone")))
        queue.enqueue("alice", "sender-device", "still-pending", listOf(prepared("sender-device", "tablet")))
        queue.enqueue("mallory", "other-device", "other-account", listOf(prepared("other-device", "other-phone")))

        val acknowledgedRow = store.rows.values.first { it.messageId == "already-received" }
        val events = mutableListOf<String>()
        val transport = FakeTransport(
            events = events,
            acknowledgements = mutableListOf(acknowledgedRow.toAcknowledgement()),
        )

        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
        ).run()

        assertEquals(1, result.acknowledged)
        assertEquals(1, result.uploaded)
        assertFalse(result.retryRequired)
        assertEquals(
            listOf(
                "read-acks",
                "delete-ack:" + acknowledgedRow.deliveryId,
                "upload:still-pending",
            ),
            events,
        )
        assertFalse(store.rows.containsKey(acknowledgedRow.deliveryId))
        assertTrue(store.rows.values.any { it.messageId == "other-account" })
        assertTrue(store.rows.values.any { it.messageId == "still-pending" })
    }

    @Test
    fun recipientWakeupIsDispatchedBeforeConversationPreviewSync() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue(
            "alice",
            "sender-device",
            "wake-before-preview",
            listOf(prepared("sender-device", "phone")),
        )
        val events = mutableListOf<String>()
        val runner = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(events, mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
            notifyEncryptedRecipient = { events += "notify-recipient" },
            syncEncryptedDirectPreview = { events += "sync-preview" },
        )

        val result = runner.run()

        assertEquals(1, result.uploaded)
        assertEquals(
            listOf("read-acks", "upload:wake-before-preview", "notify-recipient", "sync-preview"),
            events,
        )
    }

    @Test
    fun staleGroupMembershipIsQuarantinedWithoutBlockingDirectDeliveries() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "stale-group", listOf(prepared("sender-device", "phone")))
        queue.enqueue("alice", "sender-device", "direct-message", listOf(prepared("sender-device", "tablet")))
        val events = mutableListOf<String>()
        val transport = FakeTransport(events, mutableListOf())

        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
            validateBeforeUpload = { it.messageId != "stale-group" },
        ).run()

        val stale = store.rows.values.single { it.messageId == "stale-group" }
        val direct = store.rows.values.single { it.messageId == "direct-message" }
        assertEquals(OlmDeliveryStatus.FAILED, stale.status)
        assertEquals("group_membership_epoch_stale", stale.lastError)
        assertEquals(OlmDeliveryStatus.UPLOADED, direct.status)
        assertEquals(1, result.uploaded)
        assertFalse(result.retryRequired) // The stale row is terminally quarantined and the direct row uploaded.
        assertEquals(listOf("read-acks", "upload:direct-message"), events)
    }

    @Test
    fun acknowledgementDeletionFailurePreservesRowsAndBlocksFurtherUploads() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "already-received", listOf(prepared("sender-device", "phone")))
        queue.enqueue("alice", "sender-device", "still-pending", listOf(prepared("sender-device", "tablet")))

        val acknowledgedRow = store.rows.values.first { it.messageId == "already-received" }
        val acknowledgement = acknowledgedRow.toAcknowledgement()
        val remoteAcknowledgements = mutableListOf(acknowledgement)
        val events = mutableListOf<String>()
        val transport = FakeTransport(
            events = events,
            acknowledgements = remoteAcknowledgements,
            failAcknowledgementRemoval = true,
        )

        var deletionFailureObserved = false
        try {
            OlmDeliveryWorkerRunner(
                queue = queue,
                transport = transport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                recordAcknowledgement = { _, _ -> },
                clock = { 10_000L },
            ).run()
        } catch (expected: IllegalStateException) {
            deletionFailureObserved = expected.message.orEmpty().contains("acknowledgement deletion")
        }

        assertTrue(deletionFailureObserved)
        assertEquals(listOf("read-acks", "delete-ack:" + acknowledgedRow.deliveryId), events)
        assertEquals(listOf(acknowledgement), remoteAcknowledgements)
        assertEquals(2, store.rows.size)
        assertEquals(OlmDeliveryStatus.QUEUED, store.rows.getValue(acknowledgedRow.deliveryId).status)
        assertTrue(store.rows.values.any { it.messageId == "still-pending" })
        assertFalse(events.any { it.startsWith("upload:") })
    }

    @Test
    fun acknowledgementDeletionRecoveryCompletesBeforeRemainingUploads() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "already-received", listOf(prepared("sender-device", "phone")))
        queue.enqueue("alice", "sender-device", "still-pending", listOf(prepared("sender-device", "tablet")))

        val acknowledgedRow = store.rows.values.first { it.messageId == "already-received" }
        val acknowledgement = acknowledgedRow.toAcknowledgement()
        val remoteAcknowledgements = mutableListOf(acknowledgement)
        val events = mutableListOf<String>()
        val uploadedDeliveryIds = mutableListOf<String>()
        val transport = FakeTransport(
            events = events,
            acknowledgements = remoteAcknowledgements,
            uploadedDeliveryIds = uploadedDeliveryIds,
            failAcknowledgementRemoval = true,
        )
        val runner = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
        )

        try {
            runner.run()
            throw AssertionError("Expected the first acknowledgement deletion to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("acknowledgement deletion"))
        }

        assertEquals(2, store.rows.size)
        assertEquals(listOf(acknowledgement), remoteAcknowledgements)
        assertTrue(uploadedDeliveryIds.isEmpty())

        transport.failAcknowledgementRemoval = false
        val recovered = runner.run()

        assertEquals(1, recovered.acknowledged)
        assertEquals(1, recovered.uploaded)
        assertFalse(recovered.retryRequired)
        assertTrue(remoteAcknowledgements.isEmpty())
        assertFalse(store.rows.containsKey(acknowledgedRow.deliveryId))
        assertEquals(listOf("still-pending"), uploadedDeliveryIds.map { id ->
            store.rows.values.first { it.deliveryId == id }.messageId
        })
        assertEquals(
            listOf(
                "read-acks",
                "delete-ack:" + acknowledgedRow.deliveryId,
                "read-acks",
                "delete-ack:" + acknowledgedRow.deliveryId,
                "upload:still-pending",
            ),
            events,
        )
    }

    @Test
    fun acknowledgementReadFailureBlocksUploadsAndRecoversOnNextPass() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "already-received", listOf(prepared("sender-device", "phone")))
        queue.enqueue("alice", "sender-device", "still-pending", listOf(prepared("sender-device", "tablet")))

        val acknowledgedRow = store.rows.values.first { it.messageId == "already-received" }
        val acknowledgement = acknowledgedRow.toAcknowledgement()
        val remoteAcknowledgements = mutableListOf(acknowledgement)
        val events = mutableListOf<String>()
        val uploadedDeliveryIds = mutableListOf<String>()
        val transport = FakeTransport(
            events = events,
            acknowledgements = remoteAcknowledgements,
            uploadedDeliveryIds = uploadedDeliveryIds,
            failAcknowledgementRead = true,
        )
        val runner = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
        )

        try {
            runner.run()
            throw AssertionError("Expected acknowledgement read to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("acknowledgement read failure"))
        }

        assertEquals(listOf("read-acks"), events)
        assertEquals(2, store.rows.size)
        assertEquals(listOf(acknowledgement), remoteAcknowledgements)
        assertTrue(uploadedDeliveryIds.isEmpty())

        transport.failAcknowledgementRead = false
        val recovered = runner.run()

        assertEquals(1, recovered.acknowledged)
        assertEquals(1, recovered.uploaded)
        assertFalse(recovered.retryRequired)
        assertTrue(remoteAcknowledgements.isEmpty())
        assertFalse(store.rows.containsKey(acknowledgedRow.deliveryId))
        assertEquals(
            listOf("still-pending"),
            uploadedDeliveryIds.map { id ->
                store.rows.values.first { it.deliveryId == id }.messageId
            },
        )
        assertEquals(
            listOf(
                "read-acks",
                "read-acks",
                "delete-ack:" + acknowledgedRow.deliveryId,
                "upload:still-pending",
            ),
            events,
        )
    }

    @Test
    fun interruptedUploadIsReclaimedAfterLeaseExpiryWithTheSameDeliveryId() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100L)
        queue.enqueue("alice", "sender-device", "interrupted-upload", listOf(prepared("sender-device", "phone")))

        // Simulate process death after the queue claim. The row is still IN_FLIGHT,
        // but the remote write's completion was never observed by this process.
        val interruptedClaim = queue.claimNext("alice", "sender-device", 1_000L)!!
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, interruptedClaim.status)
        assertEquals(1, interruptedClaim.attemptCount)

        // The relay accepted the ciphertext, but the process died before it could
        // observe the write completion or process a recipient acknowledgement.
        val uploadedDeliveryIds = mutableListOf<String>()
        assertEquals(
            FirebaseOlmUploadResult.STORED,
            FakeTransport(
                events = mutableListOf(),
                acknowledgements = mutableListOf(),
                uploadedDeliveryIds = uploadedDeliveryIds,
            ).upload(interruptedClaim, "sender-device"),
        )

        val beforeLeaseExpiry = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(mutableListOf(), mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 1_099L },
        ).run()
        assertEquals(0, beforeLeaseExpiry.uploaded)
        assertFalse(beforeLeaseExpiry.retryRequired)

        val recovered = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(                events = mutableListOf(),
                acknowledgements = mutableListOf(),
                uploadedDeliveryIds = uploadedDeliveryIds,
            ),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 1_100L },
        ).run()

        assertEquals(1, recovered.uploaded)
        assertFalse(recovered.retryRequired)
        assertEquals(
            listOf(interruptedClaim.deliveryId, interruptedClaim.deliveryId),
            uploadedDeliveryIds,
        )
        val stored = store.rows.getValue(interruptedClaim.deliveryId)
        assertEquals(2, stored.attemptCount)
        assertEquals(OlmDeliveryStatus.UPLOADED, stored.status)
    }

    @Test
    fun cancellationDuringUploadKeepsLeaseAndReclaimsSameDeliveryAfterExpiry() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100L)
        queue.enqueue(
            "alice",
            "sender-device",
            "cancelled-upload",
            listOf(prepared("sender-device", "phone")),
        )
        val original = store.rows.values.single()

        val cancellingTransport = object : OlmOutgoingDeliveryTransport {
            override suspend fun upload(
                delivery: QueuedOlmDeviceDelivery,
                senderDeviceId: String,
            ): FirebaseOlmUploadResult {
                assertEquals(original.deliveryId, delivery.deliveryId)
                assertEquals(OlmDeliveryStatus.IN_FLIGHT, delivery.status)
                throw CancellationException("worker stopped")
            }

            override suspend fun readMyDeliveryAcknowledgements(
                senderDeviceId: String,
            ): List<OlmDeliveryAcknowledgement> = emptyList()

            override suspend fun removeMyDeliveryAcknowledgement(
                senderDeviceId: String,
                deliveryId: String,
            ) = Unit
        }

        var cancellationPropagated = false
        try {
            OlmDeliveryWorkerRunner(
                queue = queue,
                transport = cancellingTransport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                recordAcknowledgement = { _, _ -> },
                clock = { 1_000L },
            ).run()
        } catch (cancelled: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        val interrupted = store.rows.getValue(original.deliveryId)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, interrupted.status)
        assertEquals(1, interrupted.attemptCount)

        val beforeLeaseExpiry = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(mutableListOf(), mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 1_099L },
        ).run()
        assertEquals(0, beforeLeaseExpiry.uploaded)
        assertFalse(beforeLeaseExpiry.retryRequired)

        val uploadedIds = mutableListOf<String>()
        val recovered = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(
                events = mutableListOf(),
                acknowledgements = mutableListOf(),
                uploadedDeliveryIds = uploadedIds,
            ),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 1_100L },
        ).run()

        assertEquals(1, recovered.uploaded)
        assertEquals(listOf(original.deliveryId), uploadedIds)
        assertEquals(2, store.rows.getValue(original.deliveryId).attemptCount)
        assertEquals(OlmDeliveryStatus.UPLOADED, store.rows.getValue(original.deliveryId).status)
    }

    @Test
    fun fullBatchRequestsAnotherPassAndNextPassDrainsOnlyRemainingScopedRows() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "batch-first", listOf(prepared("sender-device", "phone-1")))
        queue.enqueue("alice", "sender-device", "batch-second", listOf(prepared("sender-device", "phone-2")))
        queue.enqueue("alice", "sender-device", "batch-third", listOf(prepared("sender-device", "phone-3")))
        queue.enqueue("alice", "retired-device", "retired-device-row", listOf(prepared("retired-device", "phone-4")))

        val firstPassEvents = mutableListOf<String>()
        val firstPass = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(firstPassEvents, mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
            maxDeliveriesPerRun = 2,
        ).run()

        assertEquals(2, firstPass.uploaded)
        assertTrue(firstPass.retryRequired)
        assertEquals(
            listOf("read-acks", "upload:batch-first", "upload:batch-second"),
            firstPassEvents,
        )
        assertEquals(
            OlmDeliveryStatus.QUEUED,
            store.rows.values.single { it.messageId == "batch-third" }.status,
        )
        assertTrue(store.rows.values.any { it.messageId == "retired-device-row" })

        val secondPassEvents = mutableListOf<String>()
        val secondPass = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(secondPassEvents, mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_001L },
            maxDeliveriesPerRun = 2,
        ).run()

        assertEquals(1, secondPass.uploaded)
        assertFalse(secondPass.retryRequired)
        assertEquals(
            listOf("read-acks", "upload:batch-third"),
            secondPassEvents,
        )
        assertTrue(store.rows.values.any { it.messageId == "retired-device-row" })
        assertEquals(
            3,
            store.rows.values.count { it.ownerUserId == "alice" && it.senderDeviceId == "sender-device" },
        )
    }

    @Test
    fun uploadFailureAtAttemptLimitMarksDeliveryFailedAndPreventsFurtherUploads() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, maxAttempts = 1)
        queue.enqueue("alice", "sender-device", "retry-terminal", listOf(prepared("sender-device", "phone")))

        val events = mutableListOf<String>()
        val attemptedDeliveryIds = mutableListOf<String>()
        val transport = FakeTransport(
            events = events,
            acknowledgements = mutableListOf(),
            failUploads = true,
            uploadedDeliveryIds = attemptedDeliveryIds,
        )
        val runner = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
        )

        val failedAttempt = runner.run()

        assertEquals(0, failedAttempt.uploaded)
        assertTrue(failedAttempt.retryRequired)
        val failedRow = store.rows.values.single()
        assertEquals(OlmDeliveryStatus.FAILED, failedRow.status)
        assertEquals(1, failedRow.attemptCount)
        assertEquals("relay_upload_failed", failedRow.lastError)

        val nextPass = runner.run()

        assertEquals(0, nextPass.uploaded)
        assertFalse(nextPass.retryRequired)
        assertEquals(
            listOf(
                "read-acks",
                "upload:retry-terminal",
                "read-acks",
            ),
            events,
        )
        assertEquals(listOf(failedRow.deliveryId), attemptedDeliveryIds)
        assertEquals(failedRow, store.rows.values.single())
    }

    @Test
    fun uploadAcceptedBeforeCompletionFailureRetriesSameDeliveryIdAfterBackoff() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "ambiguous-upload", listOf(prepared("sender-device", "phone")))

        var nowMillis = 10_000L
        var failCompletionOnce = true
        val events = mutableListOf<String>()
        val attemptedDeliveryIds = mutableListOf<String>()
        val relayStoredDeliveryIds = mutableListOf<String>()
        val transport = object : OlmOutgoingDeliveryTransport {
            override suspend fun upload(
                delivery: QueuedOlmDeviceDelivery,
                senderDeviceId: String,
            ): FirebaseOlmUploadResult {
                assertEquals("sender-device", senderDeviceId)
                assertEquals(OlmDeliveryStatus.IN_FLIGHT, delivery.status)
                events += "upload:" + delivery.messageId
                attemptedDeliveryIds += delivery.deliveryId

                // The relay commits the immutable item, but this process loses the
                // write completion. Retrying must address the same idempotency key.
                if (delivery.deliveryId !in relayStoredDeliveryIds) {
                    relayStoredDeliveryIds += delivery.deliveryId
                }
                if (failCompletionOnce) {
                    failCompletionOnce = false
                    throw IllegalStateException("upload completion lost after relay accepted ciphertext")
                }
                return FirebaseOlmUploadResult.STORED
            }

            override suspend fun readMyDeliveryAcknowledgements(
                senderDeviceId: String,
            ): List<OlmDeliveryAcknowledgement> {
                events += "read-acks"
                return emptyList()
            }

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
            recordAcknowledgement = { _, _ -> },            clock = { nowMillis },
        )

        val firstPass = runner.run()
        val firstRow = store.rows.values.single()
        assertEquals(0, firstPass.uploaded)
        assertTrue(firstPass.retryRequired)
        assertEquals(OlmDeliveryStatus.QUEUED, firstRow.status)
        assertEquals(12_000L, firstRow.nextAttemptAtMillis)
        assertEquals(listOf(firstRow.deliveryId), relayStoredDeliveryIds)

        nowMillis = 11_999L
        val beforeBackoffExpires = runner.run()
        assertEquals(0, beforeBackoffExpires.uploaded)
        assertFalse(beforeBackoffExpires.retryRequired)
        assertEquals(listOf(firstRow.deliveryId), attemptedDeliveryIds)

        nowMillis = 12_000L
        val recovered = runner.run()
        val recoveredRow = store.rows.values.single()

        assertEquals(1, recovered.uploaded)
        assertFalse(recovered.retryRequired)
        assertEquals(
            listOf(firstRow.deliveryId, firstRow.deliveryId),
            attemptedDeliveryIds,
        )
        assertEquals(listOf(firstRow.deliveryId), relayStoredDeliveryIds)
        assertEquals(2, recoveredRow.attemptCount)
        assertEquals(OlmDeliveryStatus.UPLOADED, recoveredRow.status)
        assertEquals(
            listOf(
                "read-acks",
                "upload:ambiguous-upload",
                "read-acks",
                "read-acks",
                "upload:ambiguous-upload",
            ),
            events,
        )
    }

    @Test
    fun unmatchedAcknowledgementDoesNotBlockScopedUploadOrDeleteRemoteAck() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "still-pending", listOf(prepared("sender-device", "tablet")))

        val row = store.rows.values.single()
        val unmatched = row.toAcknowledgement().copy(recipientDeviceId = "different-device")
        val remoteAcknowledgements = mutableListOf(unmatched)
        val events = mutableListOf<String>()
        val uploadedDeliveryIds = mutableListOf<String>()
        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(
                events = events,
                acknowledgements = remoteAcknowledgements,
                uploadedDeliveryIds = uploadedDeliveryIds,
            ),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
        ).run()

        assertEquals(0, result.acknowledged)
        assertEquals(1, result.unmatchedAcknowledgements)
        assertEquals(1, result.uploaded)
        assertFalse(result.retryRequired)
        assertEquals(listOf(unmatched), remoteAcknowledgements)
        assertEquals(listOf("read-acks", "upload:still-pending"), events)
        assertEquals(listOf(row.deliveryId), uploadedDeliveryIds)
        assertEquals(OlmDeliveryStatus.UPLOADED, store.rows.getValue(row.deliveryId).status)
    }

    @Test
    fun partialBatchFailureKeepsEarlierLeaseAndRetriesOnlyFailedDelivery() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "batch-first", listOf(prepared("sender-device", "phone")))
        queue.enqueue("alice", "sender-device", "batch-second", listOf(prepared("sender-device", "tablet")))

        var nowMillis = 10_000L
        var failSecondAttemptOnce = true
        val events = mutableListOf<String>()
        val attemptedDeliveryIds = mutableListOf<String>()
        val storedDeliveryIds = mutableListOf<String>()
        val transport = object : OlmOutgoingDeliveryTransport {
            override suspend fun upload(
                delivery: QueuedOlmDeviceDelivery,
                senderDeviceId: String,
            ): FirebaseOlmUploadResult {
                assertEquals("sender-device", senderDeviceId)
                events += "upload:" + delivery.messageId
                attemptedDeliveryIds += delivery.deliveryId
                if (delivery.messageId == "batch-second" && failSecondAttemptOnce) {
                    failSecondAttemptOnce = false
                    throw IllegalStateException("temporary second delivery failure")
                }
                storedDeliveryIds += delivery.deliveryId
                return FirebaseOlmUploadResult.STORED
            }

            override suspend fun readMyDeliveryAcknowledgements(
                senderDeviceId: String,
            ): List<OlmDeliveryAcknowledgement> {
                events += "read-acks"
                return emptyList()
            }

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
            recordAcknowledgement = { _, _ -> },
            clock = { nowMillis },
            maxDeliveriesPerRun = 2,
        )

        val firstPass = runner.run()
        val firstRow = store.rows.values.single { it.messageId == "batch-first" }
        val secondRow = store.rows.values.single { it.messageId == "batch-second" }
        assertEquals(1, firstPass.uploaded)
        assertTrue(firstPass.retryRequired)
        assertEquals(OlmDeliveryStatus.UPLOADED, firstRow.status)
        assertEquals(OlmDeliveryStatus.QUEUED, secondRow.status)
        assertEquals("relay_upload_failed", secondRow.lastError)
        assertEquals(12_000L, secondRow.nextAttemptAtMillis)
        assertEquals(listOf(firstRow.deliveryId), storedDeliveryIds)

        nowMillis = 11_999L
        val beforeBackoffExpires = runner.run()
        assertEquals(0, beforeBackoffExpires.uploaded)
        assertFalse(beforeBackoffExpires.retryRequired)
        assertEquals(listOf(firstRow.deliveryId, secondRow.deliveryId), attemptedDeliveryIds)

        nowMillis = 12_000L
        val recovered = runner.run()
        val recoveredSecondRow = store.rows.values.single { it.messageId == "batch-second" }
        assertEquals(1, recovered.uploaded)
        assertFalse(recovered.retryRequired)
        assertEquals(
            listOf(firstRow.deliveryId, secondRow.deliveryId, secondRow.deliveryId),
            attemptedDeliveryIds,
        )
        assertEquals(listOf(firstRow.deliveryId, secondRow.deliveryId), storedDeliveryIds)
        assertEquals(OlmDeliveryStatus.UPLOADED, firstRow.status)
        assertEquals(OlmDeliveryStatus.UPLOADED, recoveredSecondRow.status)
        assertEquals(2, recoveredSecondRow.attemptCount)
        assertEquals(
            listOf(
                "read-acks",
                "upload:batch-first",
                "upload:batch-second",
                "read-acks",
                "read-acks",
                "upload:batch-second",
            ),
            events,
        )
    }

    @Test
    fun lateUploadFailureCannotReleaseLeaseReclaimedByAnotherWorker() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100L)
        queue.enqueue(
            "alice",
            "sender-device",
            "late-upload-failure",
            listOf(prepared("sender-device", "phone")),
        )

        var nowMillis = 1_000L
        var reclaimed: QueuedOlmDeviceDelivery? = null
        val transport = object : OlmOutgoingDeliveryTransport {
            override suspend fun upload(
                delivery: QueuedOlmDeviceDelivery,
                senderDeviceId: String,
            ): FirebaseOlmUploadResult {
                assertEquals(1, delivery.attemptCount)
                nowMillis = 1_100L
                reclaimed = queue.claimNext("alice", "sender-device", nowMillis)
                assertEquals(2, reclaimed?.attemptCount)

                // The first worker's clock moves backwards before its upload fails.
                // Its stale attempt must not release the second worker's lease.
                nowMillis = 1_050L
                throw IllegalStateException("late upload failure")
            }

            override suspend fun readMyDeliveryAcknowledgements(
                senderDeviceId: String,
            ): List<OlmDeliveryAcknowledgement> = emptyList()

            override suspend fun removeMyDeliveryAcknowledgement(
                senderDeviceId: String,
                deliveryId: String,
            ) = Unit
        }

        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { nowMillis },
        ).run()

        val current = requireNotNull(reclaimed)
        val persisted = store.rows.getValue(current.deliveryId)
        assertEquals(0, result.uploaded)
        assertTrue(result.retryRequired)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, persisted.status)
        assertEquals(2, persisted.attemptCount)
        assertEquals(current.leaseUntilMillis, persisted.leaseUntilMillis)
        assertEquals(null, persisted.lastError)
    }

    @Test
    fun successfulUploadAfterLeaseReclaimDoesNotMarkAnotherWorkersClaimUploaded() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100L)
        queue.enqueue(
            "alice",
            "sender-device",
            "late-success",
            listOf(prepared("sender-device", "phone")),
        )

        var nowMillis = 1_000L
        var reclaimed: QueuedOlmDeviceDelivery? = null
        val transport = object : OlmOutgoingDeliveryTransport {
            override suspend fun upload(
                delivery: QueuedOlmDeviceDelivery,
                senderDeviceId: String,
            ): FirebaseOlmUploadResult {
                assertEquals(1, delivery.attemptCount)
                nowMillis = 1_100L
                reclaimed = queue.claimNext("alice", "sender-device", nowMillis)
                assertEquals(2, reclaimed?.attemptCount)
                // The original network write succeeds only after its lease has
                // expired and another worker has acquired the next generation.
                nowMillis = 1_101L
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

        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { nowMillis },
        ).run()

        val currentClaim = requireNotNull(reclaimed)
        val persisted = store.rows.getValue(currentClaim.deliveryId)
        assertEquals(1, result.uploaded)
        assertTrue(result.retryRequired)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, persisted.status)
        assertEquals(2, persisted.attemptCount)
        assertEquals(currentClaim.leaseUntilMillis, persisted.leaseUntilMillis)
        assertEquals(null, persisted.lastError)
    }

    @Test
    fun failedUploadAfterLeaseReclaimCannotRetryOrOverwriteNewerClaim() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store, leaseDurationMillis = 100L)
        queue.enqueue(
            "alice",
            "sender-device",
            "late-failure",
            listOf(prepared("sender-device", "phone")),
        )

        var nowMillis = 1_000L
        var reclaimed: QueuedOlmDeviceDelivery? = null
        val transport = object : OlmOutgoingDeliveryTransport {
            override suspend fun upload(
                delivery: QueuedOlmDeviceDelivery,
                senderDeviceId: String,
            ): FirebaseOlmUploadResult {
                assertEquals(1, delivery.attemptCount)
                nowMillis = 1_100L
                reclaimed = queue.claimNext("alice", "sender-device", nowMillis)
                assertEquals(2, reclaimed?.attemptCount)
                nowMillis = 1_101L
                throw IllegalStateException("late network failure")
            }

            override suspend fun readMyDeliveryAcknowledgements(
                senderDeviceId: String,
            ): List<OlmDeliveryAcknowledgement> = emptyList()

            override suspend fun removeMyDeliveryAcknowledgement(
                senderDeviceId: String,
                deliveryId: String,
            ) = Unit
        }

        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { nowMillis },
        ).run()

        val currentClaim = requireNotNull(reclaimed)
        val persisted = store.rows.getValue(currentClaim.deliveryId)
        assertEquals(0, result.uploaded)
        assertTrue(result.retryRequired)
        assertEquals(OlmDeliveryStatus.IN_FLIGHT, persisted.status)
        assertEquals(2, persisted.attemptCount)
        assertEquals(currentClaim.leaseUntilMillis, persisted.leaseUntilMillis)
        assertEquals(null, persisted.lastError)
    }

    @Test
    fun failedUploadPersistsRetryStateInTheScopedOutbox() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue("alice", "sender-device", "retry-me", listOf(prepared("sender-device", "phone")))
        val transport = FakeTransport(mutableListOf(), mutableListOf(), failUploads = true)

        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
        ).run()

        val row = store.rows.values.single()
        assertTrue(result.retryRequired)
        assertEquals(OlmDeliveryStatus.QUEUED, row.status)
        assertEquals("relay_upload_failed", row.lastError)
        assertEquals(12_000L, row.nextAttemptAtMillis)
    }

    @Test
    fun directPreviewFailureRetriesDurableDeliveryBeforeDispatchingPush() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue(
            ownerUserId = "alice",
            senderDeviceId = "sender-device",
            messageId = "preview-retry",
            prepared = listOf(prepared("sender-device", "bob-phone")),
        )

        var previewAttempts = 0
        var pushAttempts = 0
        val uploadedIds = mutableListOf<String>()
        val transport = FakeTransport(
            events = mutableListOf(),
            acknowledgements = mutableListOf(),
            uploadedDeliveryIds = uploadedIds,
        )

        val firstPass = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
            syncEncryptedDirectPreview = {
                previewAttempts++
                throw IllegalStateException("temporary preview endpoint failure")
            },
            notifyEncryptedRecipient = { pushAttempts++ },
        ).run()

        val queued = store.rows.values.single()
        assertEquals(0, firstPass.uploaded)
        assertTrue(firstPass.retryRequired)
        assertEquals(1, previewAttempts)
        assertEquals(0, pushAttempts)
        assertEquals(OlmDeliveryStatus.QUEUED, queued.status)
        assertEquals("direct_preview_sync_failed", queued.lastError)
        assertEquals(12_000L, queued.nextAttemptAtMillis)

        val recoveredPass = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 12_000L },
            syncEncryptedDirectPreview = { previewAttempts++ },
            notifyEncryptedRecipient = { pushAttempts++ },
        ).run()

        val delivered = store.rows.values.single()
        assertEquals(1, recoveredPass.uploaded)
        assertFalse(recoveredPass.retryRequired)
        assertEquals(2, previewAttempts)
        assertEquals(1, pushAttempts)
        assertEquals(2, uploadedIds.size)
        assertEquals(uploadedIds[0], uploadedIds[1])
        assertEquals(OlmDeliveryStatus.UPLOADED, delivered.status)
        assertEquals(null, delivered.lastError)
    }

    @Test
    fun groupPreviewFailureRetriesOnlyCommittedGroupMessagesBeforeDispatchingPush() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue(
            ownerUserId = "alice",
            senderDeviceId = "sender-device",
            messageId = "group-preview-retry",
            prepared = listOf(prepared("sender-device", "bob-phone")),
            groupId = "group-123",
            membershipEpoch = 5L,
            conversationId = "group-123",
        )

        var previewAttempts = 0
        var pushAttempts = 0
        val uploadedIds = mutableListOf<String>()
        val transport = FakeTransport(
            events = mutableListOf(),
            acknowledgements = mutableListOf(),
            uploadedDeliveryIds = uploadedIds,
        )

        val firstPass = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
            syncEncryptedGroupPreview = {
                previewAttempts++
                throw IllegalStateException("temporary group preview endpoint failure")
            },
            notifyEncryptedRecipient = { pushAttempts++ },
        ).run()

        val queued = store.rows.values.single()
        assertEquals(0, firstPass.uploaded)
        assertTrue(firstPass.retryRequired)
        assertEquals(1, previewAttempts)
        assertEquals(0, pushAttempts)
        assertEquals(OlmDeliveryStatus.QUEUED, queued.status)
        assertEquals("group_preview_sync_failed", queued.lastError)
        assertEquals(12_000L, queued.nextAttemptAtMillis)

        val recoveredPass = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 12_000L },
            syncEncryptedGroupPreview = { previewAttempts++ },
            notifyEncryptedRecipient = { pushAttempts++ },
        ).run()

        val delivered = store.rows.values.single()
        assertEquals(1, recoveredPass.uploaded)
        assertFalse(recoveredPass.retryRequired)
        assertEquals(2, previewAttempts)
        assertEquals(1, pushAttempts)
        assertEquals(2, uploadedIds.size)
        assertEquals(uploadedIds[0], uploadedIds[1])
        assertEquals(OlmDeliveryStatus.UPLOADED, delivered.status)
        assertEquals(null, delivered.lastError)
    }

    @Test
    fun failedEncryptedPushDispatchDoesNotRetryAnAlreadyStoredRelayDelivery() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue(
            ownerUserId = "alice",
            senderDeviceId = "sender-device",
            messageId = "retry-push",
            prepared = listOf(prepared("sender-device", "bob-phone")),
        )

        var pushAttempts = 0
        val uploadedIds = mutableListOf<String>()
        val transport = FakeTransport(
            events = mutableListOf(),
            acknowledgements = mutableListOf(),
            uploadedDeliveryIds = uploadedIds,
        )
        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
            notifyEncryptedRecipient = {
                pushAttempts++
                throw IllegalStateException("temporary push endpoint failure")
            },
        ).run()

        val stored = store.rows.values.single()
        assertEquals(1, result.uploaded)
        assertFalse(result.retryRequired)
        assertEquals(1, pushAttempts)
        assertEquals(1, result.notificationDispatchFailures)
        assertEquals(listOf(stored.deliveryId), uploadedIds)
        assertEquals(1, stored.attemptCount)
        assertEquals(OlmDeliveryStatus.UPLOADED, stored.status)
        assertEquals(null, stored.lastError)
    }

    @Test
    fun encryptedPushIsSentOnlyToRecipientAccountDevicesNotSenderSecondaryDevices() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue(
            ownerUserId = "alice",
            senderDeviceId = "sender-device",
            messageId = "fanout-message",
            prepared = listOf(
                prepared("sender-device", "bob-phone", recipientUserId = "bob"),
                prepared("sender-device", "alice-tablet", recipientUserId = "alice"),
            ),
        )

        val notifications = mutableListOf<Pair<String, String>>()
        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = FakeTransport(mutableListOf(), mutableListOf()),
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
            notifyEncryptedRecipient = { delivery ->
                notifications += delivery.recipientUserId to delivery.recipientDeviceId
            },
        ).run()

        assertEquals(2, result.uploaded)
        assertFalse(result.retryRequired)
        assertEquals(listOf("bob" to "bob-phone"), notifications)
        assertEquals(2, store.rows.values.count { it.status == OlmDeliveryStatus.UPLOADED })
    }

    @Test
    fun receiptPersistenceFailureBlocksFurtherUploadsAndRetainsAcknowledgedCiphertext() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue(
            "alice",
            "sender-device",
            "already-received",
            listOf(prepared("sender-device", "phone")),
        )
        queue.enqueue(
            "alice",
            "sender-device",
            "still-pending",
            listOf(prepared("sender-device", "tablet")),
        )

        val acknowledgedRow = store.rows.values.first { it.messageId == "already-received" }
        val transportEvents = mutableListOf<String>()
        val transport = FakeTransport(
            events = transportEvents,
            acknowledgements = mutableListOf(acknowledgedRow.toAcknowledgement()),
        )

        var persistenceFailureObserved = false
        try {
            OlmDeliveryWorkerRunner(
                queue = queue,
                transport = transport,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                clock = { 10_000L },
                recordAcknowledgement = { _, _ ->
                    throw IllegalStateException("temporary Room receipt write failure")
                },
            ).run()
        } catch (expected: IllegalStateException) {
            persistenceFailureObserved = expected.message.orEmpty().contains("receipt write failure")
        }

        assertTrue(persistenceFailureObserved)
        assertEquals(
            listOf("read-acks", "delete-ack:" + acknowledgedRow.deliveryId),
            transportEvents,
        )
        assertEquals(2, store.rows.size)
        assertTrue(store.rows.containsKey(acknowledgedRow.deliveryId))
        assertTrue(store.rows.values.any { it.messageId == "still-pending" })
        assertFalse(transportEvents.any { it.startsWith("upload:") })
    }

    @Test
    fun expiredMailboxDeliveryIsQuarantinedInsteadOfRetriedWithAnExpiredId() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue(
            "alice",
            "sender-device",
            "expired-delivery",
            listOf(prepared("sender-device", "phone")),
        )
        val events = mutableListOf<String>()
        val attemptedIds = mutableListOf<String>()
        val transport = FakeTransport(
            events = events,
            acknowledgements = mutableListOf(),
            uploadedDeliveryIds = attemptedIds,
            uploadFailure = OlmMailboxHttpException(
                statusCode = 410,
                retryable = false,
                message = "delivery expired",
            ),
        )
        val runner = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
        )

        val first = runner.run()
        val failed = store.rows.values.single()
        assertEquals(0, first.uploaded)
        assertFalse(first.retryRequired)
        assertEquals(OlmDeliveryStatus.FAILED, failed.status)
        assertEquals("relay_delivery_expired", failed.lastError)
        assertEquals(1, failed.attemptCount)

        val next = runner.run()
        assertFalse(next.retryRequired)
        assertEquals(listOf(failed.deliveryId), attemptedIds)
        assertEquals(1, events.count { it.startsWith("upload:") })
    }

    @Test
    fun retryableMailboxFailureKeepsExponentialBackoffRecovery() = runBlocking {
        val store = InMemoryQueueStore()
        val queue = OlmDeliveryQueue(store)
        queue.enqueue(
            "alice",
            "sender-device",
            "temporary-mailbox-failure",
            listOf(prepared("sender-device", "phone")),
        )
        val transport = FakeTransport(
            events = mutableListOf(),
            acknowledgements = mutableListOf(),
            uploadFailure = OlmMailboxHttpException(
                statusCode = 503,
                retryable = true,
                message = "mailbox unavailable",
            ),
        )

        val result = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            recordAcknowledgement = { _, _ -> },
            clock = { 10_000L },
        ).run()

        val queued = store.rows.values.single()
        assertEquals(0, result.uploaded)
        assertTrue(result.retryRequired)
        assertEquals(OlmDeliveryStatus.QUEUED, queued.status)
        assertEquals("relay_upload_failed", queued.lastError)
        assertEquals(12_000L, queued.nextAttemptAtMillis)
    }

    private fun prepared(
        senderDeviceId: String,
        recipientDeviceId: String,
        recipientUserId: String = "bob",
    ): PreparedOlmDelivery {
        val envelope = JSONObject()
            .put("encryptionVersion", 1)
            .put("algorithm", "olm")
            .put("senderDeviceId", senderDeviceId)
            .put("recipientDeviceId", recipientDeviceId)
            .put("sessionId", "session-" + recipientDeviceId)
            .put("ciphertext", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.URL_SAFE or Base64.NO_WRAP))
            .toString()
        return PreparedOlmDelivery(recipientUserId, recipientDeviceId, "session-" + recipientDeviceId, envelope)
    }

    private fun QueuedOlmDeviceDelivery.toAcknowledgement() = OlmDeliveryAcknowledgement(
        deliveryId = deliveryId,
        recipientUserId = recipientUserId,
        recipientDeviceId = recipientDeviceId,
        messageId = messageId,
        senderUserId = requireNotNull(ownerUserId),
        senderDeviceId = requireNotNull(senderDeviceId),
    )

    private class InMemoryQueueStore : OlmDeliveryQueueStore {
        val rows = linkedMapOf<String, QueuedOlmDeviceDelivery>()

        override fun insertIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>): Int {
            var inserted = 0
            deliveries.forEach { if (!rows.containsKey(it.deliveryId)) {
                rows[it.deliveryId] = it
                inserted++
            } }
            return inserted
        }

        override fun hasMatchingDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean =
            rows[acknowledgement.deliveryId]?.let { row ->
                row.messageId == acknowledgement.messageId &&
                    row.recipientUserId == acknowledgement.recipientUserId &&
                    row.recipientDeviceId == acknowledgement.recipientDeviceId &&
                    row.ownerUserId == acknowledgement.senderUserId &&
                    row.senderDeviceId == acknowledgement.senderDeviceId
            } ?: false

        override fun removeAcknowledgedDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean {
            if (!hasMatchingDelivery(acknowledgement)) return false
            rows.remove(acknowledgement.deliveryId)
            return true
        }

        override fun claimNextDue(
            ownerUserId: String,
            senderDeviceId: String,
            nowMillis: Long,
            leaseDurationMillis: Long,
            maxAttempts: Int,
        ): QueuedOlmDeviceDelivery? {
            val candidate = rows.values.filter {
                it.ownerUserId == ownerUserId && it.senderDeviceId == senderDeviceId &&
                    ((it.status == OlmDeliveryStatus.QUEUED && it.nextAttemptAtMillis <= nowMillis) ||
                        (it.status == OlmDeliveryStatus.UPLOADED && it.nextAttemptAtMillis <= nowMillis) ||
                        (it.status == OlmDeliveryStatus.IN_FLIGHT && it.leaseUntilMillis <= nowMillis))
            }.minByOrNull { it.nextAttemptAtMillis } ?: return null
            if (candidate.attemptCount >= maxAttempts) {
                rows[candidate.deliveryId] = candidate.copy(
                    status = OlmDeliveryStatus.FAILED,
                    leaseUntilMillis = 0,
                    lastError = "attempt_limit_reached",
                )
                return null
            }
            return candidate.copy(
                status = OlmDeliveryStatus.IN_FLIGHT,
                attemptCount = candidate.attemptCount + 1,
                leaseUntilMillis = nowMillis + leaseDurationMillis,
            ).also { rows[it.deliveryId] = it }
        }


        override fun retryFailed(
            deliveryId: String,
            messageId: String,
            ownerUserId: String,
            senderDeviceId: String,
            nowMillis: Long,
        ): Boolean {
            val row = rows[deliveryId] ?: return false
            if (row.messageId != messageId || row.ownerUserId != ownerUserId ||
                row.senderDeviceId != senderDeviceId || row.status != OlmDeliveryStatus.FAILED
            ) return false
            rows[deliveryId] = row.copy(
                status = OlmDeliveryStatus.QUEUED,
                attemptCount = 0,
                nextAttemptAtMillis = nowMillis,
                leaseUntilMillis = 0L,
                lastError = null,
            )
            return true
        }

        override fun updateInFlight(
            deliveryId: String,
            messageId: String,
            ownerUserId: String,
            senderDeviceId: String,
            expectedAttemptCount: Int,
            expectedLeaseUntilMillis: Long,
            nowMillis: Long,
            update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
        ): Boolean {
            val row = rows[deliveryId] ?: return false
            if (row.messageId != messageId || row.ownerUserId != ownerUserId ||
                row.senderDeviceId != senderDeviceId || row.status != OlmDeliveryStatus.IN_FLIGHT ||
                row.attemptCount != expectedAttemptCount ||
                row.leaseUntilMillis != expectedLeaseUntilMillis ||
                row.leaseUntilMillis <= nowMillis
            ) return false
            rows[deliveryId] = update(row)
            return true
        }
    }

    private class FakeTransport(
        private val events: MutableList<String>,
        private val acknowledgements: MutableList<OlmDeliveryAcknowledgement>,
        private val failUploads: Boolean = false,
        private val uploadedDeliveryIds: MutableList<String> = mutableListOf(),
        var failAcknowledgementRemoval: Boolean = false,
        var failAcknowledgementRead: Boolean = false,
        private val uploadFailure: Exception? = null,
    ) : OlmOutgoingDeliveryTransport {
        override suspend fun upload(
            delivery: QueuedOlmDeviceDelivery,
            senderDeviceId: String,
        ): FirebaseOlmUploadResult {
            events += "upload:" + delivery.messageId
            uploadedDeliveryIds += delivery.deliveryId
            check(delivery.senderDeviceId == senderDeviceId)
            uploadFailure?.let { throw it }
            if (failUploads) throw IllegalStateException("temporary relay failure")
            return FirebaseOlmUploadResult.STORED
        }

        override suspend fun readMyDeliveryAcknowledgements(
            senderDeviceId: String,
        ): List<OlmDeliveryAcknowledgement> {
            events += "read-acks"
            if (failAcknowledgementRead) {
                throw IllegalStateException("temporary acknowledgement read failure")
            }
            return acknowledgements.toList()
        }

        override suspend fun removeMyDeliveryAcknowledgement(senderDeviceId: String, deliveryId: String) {
            events += "delete-ack:" + deliveryId
            if (failAcknowledgementRemoval) {
                throw IllegalStateException("temporary acknowledgement deletion failure")
            }
            acknowledgements.removeAll { it.deliveryId == deliveryId }
        }
    }
}