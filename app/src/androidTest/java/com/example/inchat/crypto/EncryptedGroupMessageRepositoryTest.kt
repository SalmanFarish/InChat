package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import com.example.inchat.data.model.ReplyTo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedGroupMessageRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(
        context,
        com.example.inchat.data.local.LocalChatDatabase::class.java,
    ).allowMainThreadQueries().build()
    private val keyAlias = EncryptedGroupMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
    private val pendingKeyAlias = EncryptedGroupPendingMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
    private var activeAccount = "alice"
    private val repository = EncryptedGroupMessageRepository(
        dao = database.encryptedGroupMessageDao(),
        vault = EncryptedGroupMessageVault(keyAlias),
        pendingVault = EncryptedGroupPendingMessageVault(pendingKeyAlias),
        authenticatedUserId = { activeAccount },
    )

    @After
    fun closeDatabase() {
        database.close()
        deleteKey()
    }

    @Test
    fun commitValidatesCiphertextDigestAndSupportsScopedEncryptedReads() = runBlocking {
        val message = message()
        val ciphertext = "authenticated Megolm ciphertext".toByteArray(Charsets.UTF_8)
        val receipt = receipt(message, ciphertext)

        assertTrue(repository.commitStagedMessage(message, receipt, ciphertext))
        assertFalse(
            "An exact duplicate receipt must remain idempotent",
            repository.commitStagedMessage(message, receipt, ciphertext),
        )

        assertEquals(message, repository.getMessage("alice", "group-123", "message-123"))
        assertRejected { repository.getMessage("mallory", "group-123", "message-123") }
        assertNull(repository.getMessage("alice", "another-group", "message-123"))
        assertEquals(
            listOf(message),
            repository.observeMessages("alice", "group-123").first(),
        )
        assertRejected { repository.observeMessages("mallory", "group-123").first() }
    }

    @Test
    fun ciphertextDigestMismatchIsRejectedBeforeAnyRoomWrite() = runBlocking {
        val message = message().copy(messageId = "message-bad-digest")
        val ciphertext = "actual staged ciphertext".toByteArray(Charsets.UTF_8)
        val receipt = receipt(message, ciphertext).copy(
            receiptId = sha256("bad-digest-receipt"),
            ciphertextSha256 = "0".repeat(64),
        )

        var rejected = false
        try {
            repository.commitStagedMessage(message, receipt, ciphertext)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue("A mismatched staged ciphertext digest must fail closed", rejected)
        assertNull(repository.getMessage("alice", "group-123", message.messageId))
        assertTrue(repository.observeMessages("alice", "group-123").first().isEmpty())
    }


    @Test
    fun conflictingReplayIndexOrMessageIdentityRollsBackBothRoomRows() = runBlocking {
        val original = message().copy(messageId = "replay-original")
        val originalCiphertext = "original authenticated ciphertext".toByteArray(Charsets.UTF_8)
        val originalReceipt = receipt(original, originalCiphertext)
        assertTrue(repository.commitStagedMessage(original, originalReceipt, originalCiphertext))

        val conflictingReplay = originalReceipt.copy(
            receiptId = sha256("different-receipt"),
            ciphertextSha256 = sha256("different ciphertext"),
        )
        var replayRejected = false
        try {
            repository.commitStagedMessage(original, conflictingReplay, "different ciphertext".toByteArray())
        } catch (_: IllegalStateException) {
            replayRejected = true
        }
        assertTrue("A replay-index collision with changed authenticated metadata must fail", replayRejected)

        val messageIdCollision = original.copy(
            messageIndex = original.messageIndex + 1L,
            text = "must not replace the original",
        )
        val collisionCiphertext = "different Megolm index ciphertext".toByteArray(Charsets.UTF_8)
        var messageRejected = false
        try {
            repository.commitStagedMessage(
                messageIdCollision,
                receipt(messageIdCollision, collisionCiphertext),
                collisionCiphertext,
            )
        } catch (_: IllegalStateException) {
            messageRejected = true
        }
        assertTrue("A message-ID collision must fail closed", messageRejected)

        assertTrue(
            "Failed message-ID insertion must roll back its newly inserted replay row",
            !database.encryptedGroupMessageDao().hasReplay(
                original.ownerUserId,
                original.groupId,
                original.senderUserId,
                original.senderDeviceId,
                original.megolmSessionId,
                messageIdCollision.messageIndex,
            ),
        )
        assertEquals(
            original,
            repository.getMessage(original.ownerUserId, original.groupId, original.messageId),
        )
        assertEquals(listOf(original), repository.observeMessages(original.ownerUserId, original.groupId).first())
    }

    @Test
    fun observeMessagesUsesStableTimestampAndMessageIdOrdering() = runBlocking {
        val latest = message().copy(
            messageId = "message-z",
            messageIndex = 20L,
            sentAtMillis = 1_800_000_000_100L,
            text = "Latest message",
        )
        val sameTimeSecondById = message().copy(
            messageId = "message-b",
            messageIndex = 19L,
            sentAtMillis = 1_800_000_000_000L,
            text = "Second message at the same timestamp",
        )
        val sameTimeFirstById = message().copy(
            messageId = "message-a",
            messageIndex = 18L,
            sentAtMillis = 1_800_000_000_000L,
            text = "First message at the same timestamp",
        )

        // Commit in reverse display order. The timeline must be deterministic
        // by sent timestamp, then message ID when timestamps are equal.
        val records = listOf(latest, sameTimeSecondById, sameTimeFirstById)
        records.forEach { record ->
            val ciphertext = "ciphertext-${record.messageId}".toByteArray(Charsets.UTF_8)
            assertTrue(repository.commitStagedMessage(record, receipt(record, ciphertext), ciphertext))
        }

        assertEquals(
            listOf("message-a", "message-b", "message-z"),
            repository.observeMessages("alice", "group-123").first().map { it.messageId },
        )
        assertRejected { repository.observeMessages("mallory", "group-123").first() }
    }

    @Test
    fun membershipGatedReadsRejectNonMembersAndInvalidSnapshots() = runBlocking {
        val message = message()
        val ciphertext = "membership-gated ciphertext".toByteArray(Charsets.UTF_8)
        assertTrue(repository.commitStagedMessage(message, receipt(message, ciphertext), ciphertext))

        val currentMembership = GroupCryptoMembershipSnapshot(
            groupId = "group-123",
            epoch = 4L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        )
        assertEquals(
            listOf(message),
            repository.observeMessagesForMember("alice", "group-123", flowOf(currentMembership)).first(),
        )
        assertEquals(
            message,
            repository.getMessageForMember("alice", currentMembership, message.messageId),
        )

        val nonMemberSnapshot = currentMembership.copy(
            members = mapOf("bob" to "admin", "carol" to "member"),
        )
        assertEquals(
            emptyList<DecryptedGroupMessage>(),
            repository.observeMessagesForMember("alice", "group-123", flowOf(nonMemberSnapshot)).first(),
        )
        assertRejected {
            repository.getMessageForMember("mallory", currentMembership, message.messageId)
        }
        assertEquals(
            emptyList<DecryptedGroupMessage>(),
            repository.observeMessagesForMember(
                "alice",
                "group-123",
                flowOf(currentMembership.copy(epoch = 0L)),
            ).first(),
        )
    }


    @Test
    fun authenticatedAccountSwitchRevokesExistingTimelineAndFutureCommits() = runBlocking {
        val message = message().copy(messageId = "account-switch-message")
        val ciphertext = "account-switch ciphertext".toByteArray(Charsets.UTF_8)
        assertTrue(repository.commitStagedMessage(message, receipt(message, ciphertext), ciphertext))

        val existingFlow = repository.observeMessages("alice", "group-123")
        assertEquals(listOf(message), existingFlow.first())

        activeAccount = "mallory"
        assertRejected {
            existingFlow.first()
        }
        assertRejected {
            repository.getMessage("alice", "group-123", message.messageId)
        }

        val nextMessage = message.copy(messageId = "after-account-switch")
        val nextCiphertext = "must not commit after account switch".toByteArray(Charsets.UTF_8)
        assertRejected {
            repository.commitStagedMessage(
                nextMessage,
                receipt(nextMessage, nextCiphertext),
                nextCiphertext,
            )
        }
        assertNull(
            database.encryptedGroupMessageDao().getMessage(
                "alice",
                "group-123",
                nextMessage.messageId,
            ),
        )
    }

    @Test
    fun authenticatedAccountSwitchRevokesMembershipGatedTimelineAndReads() = runBlocking {
        val message = message().copy(messageId = "membership-account-switch")
        val ciphertext = "membership account-switch ciphertext".toByteArray(Charsets.UTF_8)
        assertTrue(repository.commitStagedMessage(message, receipt(message, ciphertext), ciphertext))

        val membership = GroupCryptoMembershipSnapshot(
            groupId = "group-123",
            epoch = 4L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        )
        val liveMembership = MutableStateFlow<GroupCryptoMembershipSnapshot?>(membership)
        val existingFlow = repository.observeMessagesForMember(
            "alice",
            "group-123",
            liveMembership,
        )
        assertEquals(listOf(message), existingFlow.first())
        assertEquals(
            message,
            repository.getMessageForMember("alice", membership, message.messageId),
        )

        activeAccount = "mallory"
        assertRejected { existingFlow.first() }
        assertRejected {
            repository.getMessageForMember("alice", membership, message.messageId)
        }
    }

    @Test
    fun membershipGatedTimelineRejectsAccountSwitchDuringDecryption() = runBlocking {
        val message = message().copy(messageId = "membership-switch-during-decrypt")
        val ciphertext = "membership account switch during decrypt".toByteArray(Charsets.UTF_8)
        assertTrue(repository.commitStagedMessage(message, receipt(message, ciphertext), ciphertext))

        val membership = GroupCryptoMembershipSnapshot(
            groupId = "group-123",
            epoch = 4L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        )
        var authReads = 0
        val guardedRepository = EncryptedGroupMessageRepository(
            dao = database.encryptedGroupMessageDao(),
            vault = EncryptedGroupMessageVault(keyAlias),
            authenticatedUserId = {
                authReads += 1
                if (authReads <= 2) "alice" else "mallory"
            },
        )

        assertEquals(
            emptyList<DecryptedGroupMessage>(),
            guardedRepository.observeMessagesForMember(
                "alice",
                "group-123",
                flowOf(membership),
            ).first(),
        )
    }

    @Test
    fun membershipRevocationDuringDecryptCannotEmitStaleTimeline() = runBlocking {
        val message = message().copy(messageId = "membership-revoked-during-decrypt")
        val ciphertext = "membership revocation during decrypt".toByteArray(Charsets.UTF_8)
        assertTrue(repository.commitStagedMessage(message, receipt(message, ciphertext), ciphertext))

        val membership = GroupCryptoMembershipSnapshot(
            groupId = "group-123",
            epoch = 4L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        )
        val liveMembership = MutableStateFlow<GroupCryptoMembershipSnapshot?>(membership)
        val decryptStarted = CompletableDeferred<Unit>()
        val finishDecrypt = CompletableDeferred<Unit>()
        val testVault = EncryptedGroupMessageVault(keyAlias)
        val guardedRepository = EncryptedGroupMessageRepository(
            dao = database.encryptedGroupMessageDao(),
            vault = testVault,
            ioDispatcher = Dispatchers.Unconfined,
            authenticatedUserId = { activeAccount },
            decryptGroupMessages = { records ->
                decryptStarted.complete(Unit)
                // Model a native/Keystore decrypt that cannot stop immediately
                // when its collecting coroutine is cancelled.
                withContext(NonCancellable) { finishDecrypt.await() }
                records.map(testVault::decrypt)
            },
        )

        val observation = async {
            guardedRepository.observeMessagesForMember(
                "alice",
                "group-123",
                liveMembership,
            ).first()
        }
        decryptStarted.await()
        liveMembership.value = membership.copy(
            epoch = 5L,
            members = mapOf("bob" to "admin", "carol" to "member"),
        )
        yield()
        finishDecrypt.complete(Unit)

        assertEquals(emptyList<DecryptedGroupMessage>(), observation.await())
    }

    @Test
    fun oneShotMemberReadRejectsRevocationDuringDecrypt() = runBlocking {
        val message = message().copy(messageId = "one-shot-membership-revoked")
        val ciphertext = "one-shot membership revocation".toByteArray(Charsets.UTF_8)
        assertTrue(repository.commitStagedMessage(message, receipt(message, ciphertext), ciphertext))

        val membership = GroupCryptoMembershipSnapshot(
            groupId = "group-123",
            epoch = 4L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        )
        val liveMembership = MutableStateFlow<GroupCryptoMembershipSnapshot?>(membership)
        val decryptStarted = CompletableDeferred<Unit>()
        val finishDecrypt = CompletableDeferred<Unit>()
        val testVault = EncryptedGroupMessageVault(keyAlias)
        val guardedRepository = EncryptedGroupMessageRepository(
            dao = database.encryptedGroupMessageDao(),
            vault = testVault,
            ioDispatcher = Dispatchers.Unconfined,
            authenticatedUserId = { activeAccount },
            decryptGroupMessages = { records ->
                decryptStarted.complete(Unit)
                withContext(NonCancellable) { finishDecrypt.await() }
                records.map(testVault::decrypt)
            },
        )

        val read = async {
            guardedRepository.getMessageForMember(
                ownerUserId = "alice",
                groupId = "group-123",
                membershipSnapshots = liveMembership,
                messageId = message.messageId,
            )
        }
        decryptStarted.await()
        liveMembership.value = membership.copy(
            epoch = 5L,
            members = mapOf("bob" to "admin", "carol" to "member"),
        )
        yield()
        finishDecrypt.complete(Unit)

        assertRejected { read.await() }
    }

    @Test
    fun oneShotMemberReadRejectsAccountSwitchDuringDecrypt() = runBlocking {
        val message = message().copy(messageId = "one-shot-account-switch")
        val ciphertext = "one-shot account switch".toByteArray(Charsets.UTF_8)
        assertTrue(repository.commitStagedMessage(message, receipt(message, ciphertext), ciphertext))

        val membership = GroupCryptoMembershipSnapshot(
            groupId = "group-123",
            epoch = 4L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        )
        val testVault = EncryptedGroupMessageVault(keyAlias)
        val guardedRepository = EncryptedGroupMessageRepository(
            dao = database.encryptedGroupMessageDao(),
            vault = testVault,
            ioDispatcher = Dispatchers.Unconfined,
            authenticatedUserId = { activeAccount },
            decryptGroupMessages = { records ->
                records.map(testVault::decrypt).also { activeAccount = "mallory" }
            },
        )

        assertRejected {
            guardedRepository.getMessageForMember(
                ownerUserId = "alice",
                groupId = "group-123",
                membershipSnapshots = flowOf(membership),
                messageId = message.messageId,
            )
        }
    }

    @Test
    fun membershipRemovalRevokesTheNextEncryptedTimelineRead() = runBlocking {
        val message = message()
        val ciphertext = "membership-revocation ciphertext".toByteArray(Charsets.UTF_8)
        assertTrue(repository.commitStagedMessage(message, receipt(message, ciphertext), ciphertext))

        val membership = GroupCryptoMembershipSnapshot(
            groupId = "group-123",
            epoch = 4L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        )
        val liveMembership = MutableStateFlow<GroupCryptoMembershipSnapshot?>(membership)
        assertEquals(
            listOf(message),
            repository.observeMessagesForMember("alice", "group-123", liveMembership).first(),
        )

        val observedSnapshots = mutableListOf<List<DecryptedGroupMessage>>()
        val revokedSnapshotEmitted = CompletableDeferred<Unit>()
        val restoredSnapshotEmitted = CompletableDeferred<Unit>()
        val observation = launch {
            repository.observeMessagesForMember("alice", "group-123", liveMembership)
                .take(3)
                .collect { snapshot ->
                    observedSnapshots += snapshot
                    if (snapshot.isEmpty()) revokedSnapshotEmitted.complete(Unit)
                    if (observedSnapshots.size == 3) restoredSnapshotEmitted.complete(Unit)
                }
        }
        yield()
        liveMembership.value = membership.copy(
            epoch = 5L,
            members = mapOf("bob" to "admin", "carol" to "member"),
        )
        revokedSnapshotEmitted.await()
        liveMembership.value = membership
        restoredSnapshotEmitted.await()
        observation.join()

        assertEquals(
            listOf(listOf(message), emptyList(), listOf(message)),
            observedSnapshots,
        )
    }

    @Test
    fun committedOutgoingIntentIsRestoredToTimelineAndBothRecoveryRowsAreCleared() = runBlocking {
        val outgoing = outgoingMessage("recover-committed")
        assertTrue(repository.savePendingOutgoingIntent(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
            outgoing.sentAtMillis, outgoing.text,
        ))
        assertEquals(1L, database.olmDeliveryQueueDao()
            .insertOutgoingGroupCommitIfAbsent(commitMarker(outgoing)))

        assertEquals(1, repository.recoverCommittedOutgoingIntents("alice", "group-123"))

        assertEquals(outgoing, repository.getMessage("alice", "group-123", outgoing.messageId))
        assertNull(database.encryptedGroupMessageDao()
            .getPendingMessage("alice", "group-123", outgoing.messageId))
        assertNull(database.encryptedGroupMessageDao()
            .getOutgoingGroupCommit("alice", "group-123", outgoing.messageId))
        assertEquals(0, repository.recoverCommittedOutgoingIntents("alice", "group-123"))
    }

    @Test
    fun oneConflictingCommittedIntentDoesNotBlockRecoveryOfLaterMessages() = runBlocking {
        val damaged = outgoingMessage("recover-damaged-first").copy(sentAtMillis = 1_800_000_000_100L)
        val valid = outgoingMessage("recover-valid-after-damage").copy(sentAtMillis = 1_800_000_000_200L)

        assertTrue(repository.savePendingOutgoingIntent(
            damaged.ownerUserId, damaged.groupId, damaged.messageId,
            damaged.sentAtMillis, damaged.text,
        ))
        assertTrue(repository.savePendingOutgoingIntent(
            valid.ownerUserId, valid.groupId, valid.messageId,
            valid.sentAtMillis, valid.text,
        ))
        assertEquals(1L, database.olmDeliveryQueueDao()
            .insertOutgoingGroupCommitIfAbsent(commitMarker(damaged).copy(
                sentAtMillis = damaged.sentAtMillis + 1L,
            )))
        assertEquals(1L, database.olmDeliveryQueueDao()
            .insertOutgoingGroupCommitIfAbsent(commitMarker(valid)))

        assertRejected {
            repository.recoverCommittedOutgoingIntents("alice", "group-123")
        }

        assertNull(repository.getMessage("alice", "group-123", damaged.messageId))
        assertTrue(database.encryptedGroupMessageDao()
            .getPendingMessage("alice", "group-123", damaged.messageId) != null)
        assertTrue(database.encryptedGroupMessageDao()
            .getOutgoingGroupCommit("alice", "group-123", damaged.messageId) != null)

        assertEquals(valid, repository.getMessage("alice", "group-123", valid.messageId))
        assertNull(database.encryptedGroupMessageDao()
            .getPendingMessage("alice", "group-123", valid.messageId))
        assertNull(database.encryptedGroupMessageDao()
            .getOutgoingGroupCommit("alice", "group-123", valid.messageId))
    }

    @Test
    fun pendingOutgoingIntentFlowShowsEncryptedIntentUntilAtomicTimelineCommit() = runBlocking {
        val outgoing = outgoingMessage("pending-live-timeline")
        val pendingFlow = repository.observePendingOutgoingIntents("alice", "group-123")

        assertTrue(pendingFlow.first().isEmpty())
        assertTrue(repository.savePendingOutgoingIntent(
            outgoing.ownerUserId,
            outgoing.groupId,
            outgoing.messageId,
            outgoing.sentAtMillis,
            outgoing.text,
        ))
        assertEquals(
            listOf(
                PendingGroupMessageIntent(
                    ownerUserId = outgoing.ownerUserId,
                    groupId = outgoing.groupId,
                    messageId = outgoing.messageId,
                    sentAtMillis = outgoing.sentAtMillis,
                    plaintext = outgoing.text,
                ),
            ),
            pendingFlow.first(),
        )

        // Acknowledged key distribution is represented by the durable commit
        // marker. Publishing the encrypted timeline echo removes the pending
        // intent in the same Room transaction, and the live flow follows it.
        assertEquals(
            1L,
            database.olmDeliveryQueueDao()
                .insertOutgoingGroupCommitIfAbsent(commitMarker(outgoing)),
        )
        assertTrue(repository.saveOutgoingMessageAndClearPendingIntent(outgoing))

        assertTrue(pendingFlow.first().isEmpty())
        assertEquals(
            outgoing,
            repository.getMessage("alice", "group-123", outgoing.messageId),
        )
    }

    @Test
    fun pendingEncryptedGroupReplySurvivesLocalAcceptanceAndTimelineCommit() = runBlocking {
        val reply = ReplyTo(
            messageId = "quoted-message-456",
            senderId = "bob",
            senderNickname = "Bob",
            text = "Quoted encrypted group text",
        )
        val encodedContent = EncryptedMessageContentCodec.encode(
            text = "My encrypted group reply",
            replyTo = reply,
            currentUserId = "alice",
            currentUserNickname = "Alice",
        )
        val outgoing = outgoingMessage("group-reply-pending").copy(text = encodedContent)

        assertTrue(
            repository.savePendingOutgoingIntent(
                outgoing.ownerUserId,
                outgoing.groupId,
                outgoing.messageId,
                outgoing.sentAtMillis,
                outgoing.text,
            ),
        )
        assertTrue(
            "The accepted message must remain discoverable in the durable local outbox",
            repository.isPendingOutgoingIntent("alice", "group-123", outgoing.messageId),
        )
        val pending = repository.pendingOutgoingIntents("alice")
            .single { it.messageId == outgoing.messageId }
        assertEquals(reply, EncryptedMessageContentCodec.decode(pending.plaintext).replyTo)

        assertEquals(
            1L,
            database.olmDeliveryQueueDao()
                .insertOutgoingGroupCommitIfAbsent(commitMarker(outgoing)),
        )
        repository.saveOutgoingMessageAndClearPendingIntent(outgoing)

        assertFalse(
            repository.isPendingOutgoingIntent("alice", "group-123", outgoing.messageId),
        )
        val committed = requireNotNull(
            repository.getMessage("alice", "group-123", outgoing.messageId),
        )
        assertEquals(reply, EncryptedMessageContentCodec.decode(committed.text).replyTo)
    }

    @Test
    fun pendingOutgoingIntentFlowRejectsAccountSwitchAndDoesNotLeakOtherGroupRows() = runBlocking {
        val outgoing = outgoingMessage("pending-account-scope")
        assertTrue(repository.savePendingOutgoingIntent(
            outgoing.ownerUserId,
            outgoing.groupId,
            outgoing.messageId,
            outgoing.sentAtMillis,
            outgoing.text,
        ))

        assertTrue(repository.observePendingOutgoingIntents("alice", "another-group").first().isEmpty())
        activeAccount = "mallory"
        assertRejected {
            repository.observePendingOutgoingIntents("alice", "group-123").first()
        }
    }

    @Test
    fun pendingIntentWithoutCommitMarkerIsNeverRecoveredOrDeleted() = runBlocking {
        val outgoing = outgoingMessage("recover-uncommitted")
        assertTrue(repository.savePendingOutgoingIntent(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
            outgoing.sentAtMillis, outgoing.text,
        ))

        assertEquals(0, repository.recoverCommittedOutgoingIntents("alice", "group-123"))
        assertNull(repository.getMessage("alice", "group-123", outgoing.messageId))
        assertTrue(database.encryptedGroupMessageDao()
            .getPendingMessage("alice", "group-123", outgoing.messageId) != null)
    }

    @Test
    fun committedMarkerWithoutPendingCiphertextIsRetainedForRetry() = runBlocking {
        val outgoing = outgoingMessage("recover-marker-only")
        assertEquals(1L, database.olmDeliveryQueueDao()
            .insertOutgoingGroupCommitIfAbsent(commitMarker(outgoing)))

        assertEquals(0, repository.recoverCommittedOutgoingIntents("alice", "group-123"))
        assertNull(repository.getMessage("alice", "group-123", outgoing.messageId))
        assertTrue(database.encryptedGroupMessageDao()
            .getOutgoingGroupCommit("alice", "group-123", outgoing.messageId) != null)
    }

    @Test
    fun conflictingPendingCiphertextCannotBeClearedByMatchingExistingEcho() = runBlocking {
        val outgoing = outgoingMessage("recover-pending-content-conflict")
        assertTrue(repository.saveOutgoingMessage(outgoing))
        assertTrue(repository.savePendingOutgoingIntent(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
            outgoing.sentAtMillis, outgoing.text,
        ))
        assertEquals(1L, database.olmDeliveryQueueDao()
            .insertOutgoingGroupCommitIfAbsent(commitMarker(outgoing)))

        val dao = database.encryptedGroupMessageDao()
        val storedPending = requireNotNull(dao.getPendingMessage(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
        ))
        assertEquals(1, dao.deletePendingMessage(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
        ))
        assertEquals(1L, dao.insertPendingMessage(
            storedPending.copy(textCiphertextBase64 = "AAAA"),
        ))

        assertRejected {
            repository.saveOutgoingMessageAndClearPendingIntent(outgoing)
        }

        assertEquals(outgoing, repository.getMessage(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
        ))
        assertTrue("Corrupt pending intent must remain for explicit recovery",
            dao.getPendingMessage(outgoing.ownerUserId, outgoing.groupId, outgoing.messageId) != null)
        assertTrue("Commit marker must remain paired with the unresolved intent",
            dao.getOutgoingGroupCommit(outgoing.ownerUserId, outgoing.groupId, outgoing.messageId) != null)
    }

    @Test
    fun outgoingTimelineInsertRollsBackWhenPendingIntentDisappears() = runBlocking {
        val outgoing = outgoingMessage("recover-missing-pending")
        assertTrue(repository.savePendingOutgoingIntent(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
            outgoing.sentAtMillis, outgoing.text,
        ))
        assertEquals(1L, database.olmDeliveryQueueDao()
            .insertOutgoingGroupCommitIfAbsent(commitMarker(outgoing)))

        // Simulate corruption/interruption that removes the pending ciphertext
        // before the atomic timeline-publication transaction starts.
        assertEquals(1, database.encryptedGroupMessageDao().deletePendingMessage(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
        ))

        var rejected = false
        try {
            repository.saveOutgoingMessageAndClearPendingIntent(outgoing)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue("Missing pending intent must abort the Room transaction", rejected)
        assertNull(database.encryptedGroupMessageDao().getMessage(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
        ))
        assertTrue(
            "The durable commit marker must survive the rolled-back timeline insert",
            database.encryptedGroupMessageDao().getOutgoingGroupCommit(
                outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
            ) != null,
        )
    }

    @Test
    fun conflictingExistingTimelineEchoKeepsPendingIntentAndCommitMarker() = runBlocking {
        val outgoing = outgoingMessage("recover-conflict")
        val conflictingEcho = outgoing.copy(text = "Different content under the same message ID")
        assertTrue(repository.saveOutgoingMessage(conflictingEcho))
        assertTrue(repository.savePendingOutgoingIntent(
            outgoing.ownerUserId, outgoing.groupId, outgoing.messageId,
            outgoing.sentAtMillis, outgoing.text,
        ))
        assertEquals(1L, database.olmDeliveryQueueDao()
            .insertOutgoingGroupCommitIfAbsent(commitMarker(outgoing)))

        assertRejected { repository.recoverCommittedOutgoingIntents("alice", "group-123") }

        assertEquals(conflictingEcho, repository.getMessage("alice", "group-123", outgoing.messageId))
        assertTrue(database.encryptedGroupMessageDao()
            .getPendingMessage("alice", "group-123", outgoing.messageId) != null)
        assertTrue(database.encryptedGroupMessageDao()
            .getOutgoingGroupCommit("alice", "group-123", outgoing.messageId) != null)
    }


    @Test
    fun deleteMessageForMeRemovesOnlyTheSelectedEncryptedGroupRow() = runBlocking {
        val first = message().copy(messageId = "delete-group-one", messageIndex = 18L)
        val second = message().copy(messageId = "keep-group-two", messageIndex = 19L)
        assertTrue(repository.commitStagedMessage(
            first,
            receipt(first, "delete first".toByteArray()),
            "delete first".toByteArray(),
        ))
        assertTrue(repository.commitStagedMessage(
            second,
            receipt(second, "keep second".toByteArray()),
            "keep second".toByteArray(),
        ))

        assertEquals(1, repository.deleteMessageForMe("alice", "group-123", first.messageId))
        assertNull(repository.getMessage("alice", "group-123", first.messageId))
        assertEquals(second, repository.getMessage("alice", "group-123", second.messageId))
        assertEquals(listOf(second), repository.observeMessages("alice", "group-123").first())
        assertRejected {
            repository.deleteMessageForMe("mallory", "group-123", second.messageId)
        }
    }

    @Test
    fun clearConversationForMeRemovesAllEncryptedGroupRowsOnlyForAuthenticatedOwner() = runBlocking {
        val first = message().copy(messageId = "clear-group-one", messageIndex = 20L)
        val second = message().copy(messageId = "clear-group-two", messageIndex = 21L)
        assertTrue(repository.commitStagedMessage(
            first,
            receipt(first, "clear first".toByteArray()),
            "clear first".toByteArray(),
        ))
        assertTrue(repository.commitStagedMessage(
            second,
            receipt(second, "clear second".toByteArray()),
            "clear second".toByteArray(),
        ))

        assertEquals(2, repository.deleteConversationForMe("alice", "group-123"))
        assertTrue(repository.observeMessages("alice", "group-123").first().isEmpty())
        assertRejected {
            repository.deleteConversationForMe("mallory", "group-123")
        }
    }

    private suspend fun assertRejected(block: suspend () -> Any?) {
        try {
            block()
            throw AssertionError("Expected membership-gated encrypted read to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed authorization behavior.
        }
    }

    private fun outgoingMessage(messageId: String) = DecryptedGroupMessage(
        ownerUserId = "alice",
        groupId = "group-123",
        messageId = messageId,
        membershipEpoch = 4L,
        senderUserId = "alice",
        senderDeviceId = "alice-phone",
        megolmSessionId = "abcdef1234567890",
        messageIndex = 21L,
        sentAtMillis = 1_800_000_000_100L,
        text = "Committed outgoing message that must be restored exactly.",
    )

    private fun commitMarker(message: DecryptedGroupMessage) =
        EncryptedGroupOutgoingCommitEntity(
            ownerUserId = message.ownerUserId,
            groupId = message.groupId,
            messageId = message.messageId,
            senderDeviceId = message.senderDeviceId,
            membershipEpoch = message.membershipEpoch,
            megolmSessionId = message.megolmSessionId,
            messageIndex = message.messageIndex,
            sentAtMillis = message.sentAtMillis,
            recipientDeviceCount = 2,
        )

    private fun message() = DecryptedGroupMessage(
        ownerUserId = "alice",
        groupId = "group-123",
        messageId = "message-123",
        membershipEpoch = 4L,
        senderUserId = "bob",
        senderDeviceId = "bob-phone",
        megolmSessionId = "1234567890abcdef",
        messageIndex = 17L,
        sentAtMillis = 1_800_000_000_000L,
        text = "Repository reads must decrypt only within the requested account and group.",
    )

    private fun receipt(
        message: DecryptedGroupMessage,
        ciphertext: ByteArray,
    ) = EncryptedGroupMessageReceiptEntity(
        ownerUserId = message.ownerUserId,
        groupId = message.groupId,
        senderUserId = message.senderUserId,
        senderDeviceId = message.senderDeviceId,
        megolmSessionId = message.megolmSessionId,
        messageIndex = message.messageIndex,
        membershipEpoch = message.membershipEpoch,
        messageId = message.messageId,
        sentAtMillis = message.sentAtMillis,
        receiptId = sha256("receipt-${message.messageId}"),
        ciphertextSha256 = sha256(ciphertext),
        receivedAtMillis = 1_800_000_000_001L,
    )

    private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun deleteKey() {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (store.containsAlias(keyAlias)) store.deleteEntry(keyAlias)
        if (store.containsAlias(pendingKeyAlias)) store.deleteEntry(pendingKeyAlias)
    }
}
