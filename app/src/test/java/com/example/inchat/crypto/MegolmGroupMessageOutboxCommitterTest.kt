package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MegolmGroupMessageOutboxCommitterTest {
    @Test
    fun finalizesNativeCiphertextOnlyAfterRoomOutboxContainsEveryDelivery() {
        val store = FakeStore()
        val staged = stagedMessage()
        var finalized = false
        val committer = committer(
            store = store,
            membershipSnapshots = listOf(membership(7L), membership(7L)),
            finalize = {
                assertEquals(1, store.rows.size)
                assertEquals(staged.messageId, it)
                finalized = true
                true
            },
        )

        val result = kotlinx.coroutines.runBlocking {
            committer.commit(staged, keyDistribution())
        }

        assertEquals(staged.messageId, result.messageId)
        assertEquals(7L, result.membershipEpoch)
        assertEquals(1, result.queuedDeviceDeliveries)
        assertEquals(1, result.recipientDeviceCount)
        assertTrue(finalized)
        assertEquals("bob-phone", store.rows.single().recipientDeviceId)
    }

    @Test
    fun retryAfterOutboxCommitAndNativeFinalizeFailureDoesNotDuplicateDeliveries() {
        val store = FakeStore()
        val staged = stagedMessage()
        var finalizeAttempts = 0
        val committer = committer(
            store = store,
            membershipSnapshots = listOf(
                membership(7L), membership(7L),
                membership(7L), membership(7L),
            ),
            finalize = {
                assertEquals(staged.messageId, it)
                finalizeAttempts += 1
                finalizeAttempts > 1
            },
        )

        var firstAttemptFailed = false
        try {
            kotlinx.coroutines.runBlocking {
                committer.commit(staged, keyDistribution())
            }
        } catch (_: IllegalStateException) {
            firstAttemptFailed = true
        }

        assertTrue("A failed native finalize must be surfaced for recovery", firstAttemptFailed)
        assertEquals("The first attempt must leave its durable delivery queued", 1, store.rows.size)

        val recovered = kotlinx.coroutines.runBlocking {
            committer.commit(staged, keyDistribution())
        }

        assertEquals(staged.messageId, recovered.messageId)
        assertEquals("An idempotent retry inserts no replacement delivery", 0, recovered.queuedDeviceDeliveries)
        assertEquals("Retry must reuse the original delivery identity", 1, store.rows.size)
        assertEquals(2, finalizeAttempts)
    }

    @Test
    fun membershipChangeDuringOlmPreparationLeavesNativeCiphertextStaged() {
        val store = FakeStore()
        var finalizeCalled = false
        val committer = committer(
            store = store,
            membershipSnapshots = listOf(membership(7L), membership(8L)),
            finalize = { finalizeCalled = true; true },
        )

        var rejected = false
        try {
            kotlinx.coroutines.runBlocking {
                committer.commit(stagedMessage(), keyDistribution())
            }
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(store.rows.isEmpty())
        assertFalse(finalizeCalled)
    }

    @Test
    fun roleMapChangeAtSameEpochLeavesNativeCiphertextStaged() {
        val store = FakeStore()
        var finalizeCalled = false
        val changedRoles = membership(7L).copy(
            members = mapOf("alice" to "member", "bob" to "admin"),
        )
        val committer = committer(
            store = store,
            membershipSnapshots = listOf(membership(7L), changedRoles),
            finalize = { finalizeCalled = true; true },
        )

        var rejected = false
        try {
            kotlinx.coroutines.runBlocking {
                committer.commit(stagedMessage(), keyDistribution())
            }
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(store.rows.isEmpty())
        assertFalse(finalizeCalled)
    }

    @Test
    fun trustedDeviceRosterChangeAfterPreparationLeavesNativeCiphertextStaged() {
        val store = FakeStore()
        var finalizeCalled = false
        val committer = committer(
            store = store,
            membershipSnapshots = listOf(membership(7L), membership(7L)),
            finalize = { finalizeCalled = true; true },
            currentTrustedTargets = setOf("bob" to "bob-new-phone"),
        )

        var rejected = false
        try {
            kotlinx.coroutines.runBlocking {
                committer.commit(stagedMessage(), keyDistribution())
            }
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(store.rows.isEmpty())
        assertFalse(finalizeCalled)
    }

    @Test
    fun failureToCommitOlmOutboxDoesNotFinalizeNativeCiphertext() {
        val store = FakeStore()
        var finalizeCalled = false
        val committer = committer(
            store = store,
            membershipSnapshots = listOf(membership(7L), membership(7L)),
            finalize = { finalizeCalled = true; true },
            invalidEnvelope = true,
        )

        var rejected = false
        try {
            kotlinx.coroutines.runBlocking {
                committer.commit(stagedMessage(), keyDistribution())
            }
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertTrue(store.rows.isEmpty())
        assertFalse(finalizeCalled)
    }

    @Test
    fun accountSwitchDuringFinalMembershipRefreshPreventsOutboxCommit() {
        val store = FakeStore()
        var currentUser = "alice"
        var finalizeCalled = false
        val committer = committer(
            store = store,
            membershipSnapshots = listOf(membership(7L), membership(7L)),
            finalize = { finalizeCalled = true; true },
            authenticatedUser = { currentUser },
            onLatestMembershipRead = { currentUser = "mallory" },
        )

        var rejected = false
        try {
            kotlinx.coroutines.runBlocking {
                committer.commit(stagedMessage(), keyDistribution())
            }
        } catch (expected: IllegalArgumentException) {
            rejected = expected.message.orEmpty().contains("identity changed before outbox commit")
        }

        assertTrue("Account replacement must block the final outbox commit", rejected)
        assertTrue(store.rows.isEmpty())
        assertFalse(finalizeCalled)
    }

    @Test
    fun deviceIdentityChangeDuringFinalMembershipRefreshPreventsOutboxCommit() {
        val store = FakeStore()
        var currentDevice = "alice-phone"
        var finalizeCalled = false
        val committer = committer(
            store = store,
            membershipSnapshots = listOf(membership(7L), membership(7L)),
            finalize = { finalizeCalled = true; true },
            localDevice = { currentDevice },
            onLatestMembershipRead = { currentDevice = "replacement-device" },
        )

        var rejected = false
        try {
            kotlinx.coroutines.runBlocking {
                committer.commit(stagedMessage(), keyDistribution())
            }
        } catch (expected: IllegalArgumentException) {
            rejected = expected.message.orEmpty().contains("identity changed before outbox commit")
        }

        assertTrue("Device replacement must block the final outbox commit", rejected)
        assertTrue(store.rows.isEmpty())
        assertFalse(finalizeCalled)
    }

    private fun committer(
        store: FakeStore,
        membershipSnapshots: List<GroupCryptoMembershipSnapshot>,
        finalize: (String) -> Boolean,
        invalidEnvelope: Boolean = false,
        currentTrustedTargets: Set<Pair<String, String>> = setOf("bob" to "bob-phone"),
        authenticatedUser: () -> String = { "alice" },
        localDevice: () -> String = { "alice-phone" },
        onLatestMembershipRead: () -> Unit = {},
    ) : MegolmGroupMessageOutboxCommitter = run {
        val snapshots = membershipSnapshots.toMutableList()
        MegolmGroupMessageOutboxCommitter(
        membershipForGroup = { groupId ->
            assertEquals("group-1", groupId)
            if (snapshots.size == 1) onLatestMembershipRead()
            if (snapshots.isEmpty()) null else snapshots.removeAt(0)
        },
        authenticatedUserId = authenticatedUser,
        localDeviceId = localDevice,
        distributionPreparer = OlmGroupMessageDistributionPreparing { membership, messageId, encrypted, sentAt, expectedTargets ->
            assertEquals(setOf("bob" to "bob-phone"), expectedTargets)
            PreparedOlmGroupMessageDistribution(
                messageId = messageId,
                groupId = membership.groupId,
                membershipEpoch = membership.epoch,
                megolmSessionId = encrypted.sessionId,
                deliveries = listOf(
                    PreparedOlmGroupMessageDelivery(
                        recipientUserId = "bob",
                        recipientDeviceId = "bob-phone",
                        sessionId = "session-bob",
                        envelope = if (invalidEnvelope) {
                            "not-json"
                        } else {
                            EncryptedOlmEnvelopeCodec.encode(
                                EncryptedOlmEnvelope(
                                    senderDeviceId = "alice-phone",
                                    recipientDeviceId = "bob-phone",
                                    sessionId = "session-bob",
                                    ciphertext = ByteArray(32) { it.toByte() },
                                ),
                            )
                        },
                    ),
                ),
                skippedUntrustedDeviceCount = 0,
            ).also {
                assertEquals(1234L, sentAt)
                assertEquals("1234567890abcdef", encrypted.sessionId)
            }
        },
        currentTrustedTargetDevices = { membership ->
            assertEquals("group-1", membership.groupId)
            currentTrustedTargets
        },
        outboxCoordinator = OlmGroupMessageOutboxCoordinator(
            queue = OlmDeliveryQueue(store),
            authenticatedUserId = authenticatedUser,
            localDeviceId = localDevice,
            keyDistributionReadiness = OlmGroupKeyDistributionReadinessVerifier { _, _, _, keys, messages ->
                assertEquals(keys.groupId, messages.groupId)
                assertEquals(keys.membershipEpoch, messages.membershipEpoch)
                assertEquals(keys.megolmSessionId, messages.megolmSessionId)
            },
            scheduleDelivery = { _, _ -> },
        ),
        finalizeStagedMessage = finalize,
        )
    }

    private fun stagedMessage() = StagedOutboundMegolmMessage(
        ownerUserId = "alice",
        groupId = "group-1",
        membershipEpoch = 7L,
        messageId = "message-1",
        senderUserId = "alice",
        senderDeviceId = "alice-phone",
        sentAtMillis = 1234L,
        encryptedMessage = EncryptedMegolmMessage(
            sessionId = "1234567890abcdef",
            messageIndex = 3L,
            ciphertext = ByteArray(72) { it.toByte() },
        ),
    )

    private fun membership(epoch: Long) = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = epoch,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    private fun keyDistribution() = OlmGroupKeyDistributionReadinessToken(
        ownerUserId = "alice",
        senderDeviceId = "alice-phone",
        distributionId = "group-key-7",
        groupId = "group-1",
        membershipEpoch = 7L,
        megolmSessionId = "1234567890abcdef",
        targetDevices = setOf("bob" to "bob-phone"),
    )

    private class FakeStore : OlmDeliveryQueueStore {
        val rows = mutableListOf<QueuedOlmDeviceDelivery>()

        override fun insertIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>): Int {
            val newRows = deliveries.filter { candidate ->
                rows.none { it.deliveryId == candidate.deliveryId }
            }
            rows += newRows
            return newRows.size
        }

        override fun hasMatchingDelivery(acknowledgement: OlmDeliveryAcknowledgement) = false
        override fun removeAcknowledgedDelivery(acknowledgement: OlmDeliveryAcknowledgement) = false
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
        ) = false

        override fun updateInFlight(
            deliveryId: String,
            messageId: String,
            ownerUserId: String,
            senderDeviceId: String,
            expectedAttemptCount: Int,
            expectedLeaseUntilMillis: Long,
            nowMillis: Long,
            update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
        ) = false
    }
}
