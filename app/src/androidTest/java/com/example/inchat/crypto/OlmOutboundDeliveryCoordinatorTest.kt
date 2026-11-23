package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OlmOutboundDeliveryCoordinatorTest {
    @Test
    fun preparesEveryRecipientAndSenderDeviceBeforeOneDurableBatchAndSchedulesAfterward() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val queue = OlmDeliveryQueue(store)
        val preparer = FakePreparer("sender-phone")
        val coordinator = OlmOutboundDeliveryCoordinator(
            preparer = preparer,
            queue = queue,
            trustVerifier = trusted(
                trustKey("bob", "recipient-phone"),
                trustKey("bob", "recipient-tablet"),
                trustKey("alice", "sender-tablet"),
            ),
            scheduleDelivery = { owner, device ->
                assertEquals("alice", owner)
                assertEquals("sender-phone", device)
                assertEquals(3, store.rows.size)
                events += "schedule:$owner:$device"
            },
        )

        val inserted = coordinator.enqueueDirectTextMessage(
            senderUserId = "alice",
            senderDeviceId = "sender-phone",
            recipientUserId = "bob",
            messageId = "message-1",
                conversationId = "alice_bob",
            sentAtMillis = 10_000L,
            text = "hello",
            recipientDevices = listOf(bundle("recipient-tablet"), bundle("recipient-phone")),
            senderDevices = listOf(bundle("sender-tablet"), bundle("sender-phone")),
        )

        assertEquals(3, inserted)
        assertEquals(
            listOf(
                "bob::recipient-phone",
                "bob::recipient-tablet",
                "alice::sender-tablet",
            ),
            preparer.payloads.map { it.recipientUserId + "::" + it.recipientDeviceId },
        )
        assertEquals(listOf("insert:3", "schedule:alice:sender-phone"), events)
        assertEquals(3, store.rows.size)
        assertTrue(store.rows.values.all { it.messageId == "message-1" })
        assertTrue(store.rows.values.all { it ->
            val payload = preparer.payloads.first { prepared ->
                prepared.recipientUserId == it.recipientUserId &&
                    prepared.recipientDeviceId == it.recipientDeviceId
            }
            payload.conversationId == "alice_bob" && payload.conversationPeerUserId == "bob"
        })
        assertTrue(store.rows.values.all { it.ownerUserId == "alice" })
        assertTrue(store.rows.values.all { it.senderDeviceId == "sender-phone" })
    }

    @Test
    fun preparationFailureDoesNotInsertPartialFanOutOrScheduleWorker() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val preparer = FakePreparer("sender-phone", failOnDeviceId = "recipient-tablet")
        var scheduled = false
        val coordinator = OlmOutboundDeliveryCoordinator(
            preparer = preparer,
            queue = OlmDeliveryQueue(store),
            trustVerifier = trusted(
                trustKey("bob", "recipient-phone"),
                trustKey("bob", "recipient-tablet"),
                trustKey("alice", "sender-tablet"),
            ),
            scheduleDelivery = { _, _ -> scheduled = true },
        )

        var failed = false
        try {
            coordinator.enqueueDirectTextMessage(
                senderUserId = "alice",
                senderDeviceId = "sender-phone",
                recipientUserId = "bob",
                messageId = "message-2",
                conversationId = "alice_bob",
                sentAtMillis = 20_000L,
                text = "all devices or none",
                recipientDevices = listOf(bundle("recipient-phone"), bundle("recipient-tablet")),
                senderDevices = listOf(bundle("sender-phone"), bundle("sender-tablet")),
            )
        } catch (expected: IllegalStateException) {
            failed = expected.message.orEmpty().contains("preparation failure")
        }

        assertTrue(failed)
        assertEquals(2, preparer.payloads.size)
        assertTrue(store.rows.isEmpty())
        assertTrue(events.isEmpty())
        assertFalse(scheduled)
    }

    @Test
    fun malformedPayloadIsRejectedBeforeAnyPrekeyPreparation() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val preparer = FakePreparer("sender-phone")
        var scheduled = false
        val coordinator = OlmOutboundDeliveryCoordinator(
            preparer = preparer,
            queue = OlmDeliveryQueue(store),
            trustVerifier = trusted(trustKey("bob", "recipient-phone")),
            scheduleDelivery = { _, _ -> scheduled = true },
        )

        var rejected = false
        try {
            coordinator.enqueueDirectTextMessage(
                senderUserId = "alice",
                senderDeviceId = "sender-phone",
                recipientUserId = "bob",
                messageId = "message-3",
                conversationId = "alice_bob",
                sentAtMillis = 30_000L,
                text = "x".repeat(70 * 1024),
                recipientDevices = listOf(bundle("recipient-phone")),
                senderDevices = listOf(bundle("sender-phone")),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(preparer.payloads.isEmpty())
        assertTrue(store.rows.isEmpty())
        assertTrue(events.isEmpty())
        assertFalse(scheduled)
    }

    @Test
    fun rejectsConversationIdMismatchBeforePrekeyPreparationOrQueueing() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val preparer = FakePreparer("sender-phone")
        var scheduled = false
        val coordinator = OlmOutboundDeliveryCoordinator(
            preparer = preparer,
            queue = OlmDeliveryQueue(store),
            trustVerifier = trusted(trustKey("bob", "recipient-phone")),
            scheduleDelivery = { _, _ -> scheduled = true },
        )

        var rejected = false
        try {
            coordinator.enqueueDirectTextMessage(
                senderUserId = "alice",
                senderDeviceId = "sender-phone",
                recipientUserId = "bob",
                messageId = "wrong-conversation",
                conversationId = "alice_mallory",
                sentAtMillis = 35_000L,
                text = "must stay bound to alice and bob",
                recipientDevices = listOf(bundle("recipient-phone")),
                senderDevices = listOf(bundle("sender-phone")),
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(preparer.payloads.isEmpty())
        assertTrue(store.rows.isEmpty())
        assertTrue(events.isEmpty())
        assertFalse(scheduled)
    }

    @Test
    fun schedulingFailureAfterDurableInsertIsRepairedByIdempotentReplay() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val preparer = FakePreparer("sender-phone")
        var scheduleAttempts = 0
        val coordinator = OlmOutboundDeliveryCoordinator(
            preparer = preparer,
            queue = OlmDeliveryQueue(store),
            trustVerifier = trusted(trustKey("bob", "recipient-phone")),
            scheduleDelivery = { owner, device ->
                assertEquals("alice", owner)
                assertEquals("sender-phone", device)
                scheduleAttempts++
                if (scheduleAttempts == 1) {
                    throw IllegalStateException("simulated WorkManager scheduling failure")
                }
            },
        )

        // The durable queue insert succeeds before the scheduling callback fails.
        var schedulingFailureObserved = false
        try {
            coordinator.enqueueDirectTextMessage(
                senderUserId = "alice",
                senderDeviceId = "sender-phone",
                recipientUserId = "bob",
            messageId = "message-replay",
            conversationId = "alice_bob",
                sentAtMillis = 40_000L,
                text = "same authenticated message",
                recipientDevices = listOf(bundle("recipient-phone")),
                senderDevices = listOf(bundle("sender-phone")),
            )
        } catch (expected: IllegalStateException) {
            schedulingFailureObserved = expected.message.orEmpty().contains("scheduling failure")
        }
        assertTrue(schedulingFailureObserved)
        assertEquals(1, store.rows.size)
        val firstEnvelopes = store.rows.mapValues { it.value.envelope }

        // Retry after the durable insert. New Olm ciphertext must not replace
        // the first envelope, but scheduling must be requested again.
        val replayInserted = coordinator.enqueueDirectTextMessage(
            senderUserId = "alice",
            senderDeviceId = "sender-phone",
            recipientUserId = "bob",
            messageId = "message-replay",
                conversationId = "alice_bob",
            sentAtMillis = 40_000L,
            text = "same authenticated message",
            recipientDevices = listOf(bundle("recipient-phone")),
            senderDevices = listOf(bundle("sender-phone")),
        )

        assertEquals(0, replayInserted)
        assertEquals(firstEnvelopes, store.rows.mapValues { it.value.envelope })
        assertEquals("A committed replay must not prepare another Olm session or claim another prekey", 1, preparer.payloads.size)
        assertEquals(2, scheduleAttempts)
        assertEquals(listOf("insert:1"), events)
    }

    @Test
    fun unverifiedOrRotatedDeviceBlocksFanOutBeforePrekeyPreparation() = runBlocking {
        val events = mutableListOf<String>()
        val store = RecordingStore(events)
        val preparer = FakePreparer("sender-phone")
        var scheduled = false
        val originalRecipient = bundle("recipient-phone")
        val rotatedRecipient = originalRecipient.copy(
            ed25519IdentityKey = "rotated-ed25519-recipient-phone",
        )
        val coordinator = OlmOutboundDeliveryCoordinator(
            preparer = preparer,
            queue = OlmDeliveryQueue(store),
            // Only the old key was SAS-verified. The newly published key must
            // be treated as a new, unverified device identity.
            trustVerifier = trusted(
                trustKey("bob", "recipient-phone", originalRecipient.ed25519IdentityKey),
            ),
            scheduleDelivery = { _, _ -> scheduled = true },
        )

        var rejected = false
        try {
            coordinator.enqueueDirectTextMessage(
                senderUserId = "alice",
                senderDeviceId = "sender-phone",
                recipientUserId = "bob",
                messageId = "untrusted-message",
                conversationId = "alice_bob",
                sentAtMillis = 50_000L,
                text = "must not prepare",
                recipientDevices = listOf(rotatedRecipient),
                senderDevices = listOf(bundle("sender-phone")),
            )
        } catch (expected: IllegalStateException) {
            rejected = expected.message.orEmpty().contains("has not been verified")
        }

        assertTrue(rejected)
        assertTrue(preparer.payloads.isEmpty())
        assertTrue(store.rows.isEmpty())
        assertTrue(events.isEmpty())
        assertFalse(scheduled)
    }

    private fun bundle(deviceId: String) = PublicDeviceKeyBundle(
        deviceId = deviceId,
        ed25519IdentityKey = "ed25519-$deviceId",
        curve25519IdentityKey = "curve25519-$deviceId",
        curve25519IdentityKeySignature = "identity-signature-$deviceId",
        fallbackKey = SignedOlmPreKey(
            "fallback",
            "fallback-$deviceId",
            "fallback-public-$deviceId",
            "fallback-signature-$deviceId",
        ),
        oneTimeKeys = listOf(
            SignedOlmPreKey(
                "one_time",
                "otk-$deviceId",
                "otk-public-$deviceId",
                "otk-signature-$deviceId",
            ),
        ),
    )

    private fun trustKey(
        userId: String,
        deviceId: String,
        identityKey: String = "ed25519-$deviceId",
    ): String = "$userId::$deviceId::$identityKey"

    private fun trusted(vararg entries: String): OlmDeviceTrustVerifier =
        FakeTrustVerifier(entries.toSet())

    private class FakeTrustVerifier(
        private val trustedIdentities: Set<String>,
    ) : OlmDeviceTrustVerifier {
        override fun isVerified(
            peerUserId: String,
            peerDeviceId: String,
            peerEd25519IdentityKey: String,
        ): Boolean =
            "$peerUserId::$peerDeviceId::$peerEd25519IdentityKey" in trustedIdentities
    }

    private class FakePreparer(
        private val senderDeviceId: String,
        private val failOnDeviceId: String? = null,
    ) : OlmFirstMessagePreparer {
        val payloads = mutableListOf<OlmAuthenticatedMessagePayload>()
        private var generation = 0

        override suspend fun prepareFirstMessage(
            payload: OlmAuthenticatedMessagePayload,
        ): PreparedOlmDelivery {
            payloads += payload
            if (payload.recipientDeviceId == failOnDeviceId) {
                throw IllegalStateException("simulated preparation failure")
            }
            generation++
            val sessionId = "session-${payload.recipientDeviceId}"
            val envelope = EncryptedOlmEnvelopeCodec.encode(
                EncryptedOlmEnvelope(
                    senderDeviceId = senderDeviceId,
                    recipientDeviceId = payload.recipientDeviceId,
                    sessionId = sessionId,
                    ciphertext = byteArrayOf(generation.toByte(), 2),
                ),
            )
            return PreparedOlmDelivery(
                recipientUserId = payload.recipientUserId,
                recipientDeviceId = payload.recipientDeviceId,
                sessionId = sessionId,
                envelope = envelope,
            )
        }
    }

    private class RecordingStore(
        private val events: MutableList<String>,
    ) : OlmDeliveryQueueStore {
        val rows = linkedMapOf<String, QueuedOlmDeviceDelivery>()
        private val directCommits = linkedMapOf<Triple<String, String, String>, DirectOutgoingMessageCommit>()

        override fun hasDirectMessageCommit(commit: DirectOutgoingMessageCommit): Boolean {
            val key = Triple(commit.ownerUserId, commit.conversationId, commit.messageId)
            val existing = directCommits[key] ?: return false
            require(existing == commit) { "Conflicting direct message commit metadata" }
            return true
        }

        override fun insertDirectMessageIfAbsent(
            deliveries: List<QueuedOlmDeviceDelivery>,
            commit: DirectOutgoingMessageCommit,
        ): Int {
            val key = Triple(commit.ownerUserId, commit.conversationId, commit.messageId)
            val existing = directCommits[key]
            if (existing != null) {
                require(existing == commit) { "Conflicting direct message commit metadata" }
                return 0
            }
            val inserted = insertIfAbsent(deliveries)
            check(inserted == deliveries.size) { "Direct message fan-out was not inserted completely" }
            directCommits[key] = commit
            return inserted
        }

        override fun insertIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>): Int {
            var inserted = 0
            deliveries.forEach { delivery ->
                if (!rows.containsKey(delivery.deliveryId)) {
                    rows[delivery.deliveryId] = delivery
                    inserted++
                }
            }
            events += "insert:$inserted"
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
        ): QueuedOlmDeviceDelivery? = null


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
        ): Boolean = false
    }
}
