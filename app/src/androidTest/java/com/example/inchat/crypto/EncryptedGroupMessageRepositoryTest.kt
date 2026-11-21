package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.flow.first
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
    private var activeAccount = "alice"
    private val repository = EncryptedGroupMessageRepository(
        dao = database.encryptedGroupMessageDao(),
        vault = EncryptedGroupMessageVault(keyAlias),
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
        assertRejected {
            repository.observeMessagesForMember("alice", "group-123", flowOf(nonMemberSnapshot))
        }
        assertRejected {
            repository.getMessageForMember("mallory", currentMembership, message.messageId)
        }
        assertRejected {
            repository.observeMessagesForMember(
                "alice",
                "group-123",
                flowOf(currentMembership.copy(epoch = 0L)),
            )
        }
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

        liveMembership.value = membership.copy(
            epoch = 5L,
            members = mapOf("bob" to "admin", "carol" to "member"),
        )
        assertRejected {
            repository.observeMessagesForMember("alice", "group-123", liveMembership).first()
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
    }
}
