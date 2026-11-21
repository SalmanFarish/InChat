package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
    private val repository = EncryptedGroupMessageRepository(
        dao = database.encryptedGroupMessageDao(),
        vault = EncryptedGroupMessageVault(keyAlias),
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
        assertNull(repository.getMessage("mallory", "group-123", "message-123"))
        assertNull(repository.getMessage("alice", "another-group", "message-123"))
        assertEquals(
            listOf(message),
            repository.observeMessages("alice", "group-123").first(),
        )
        assertTrue(repository.observeMessages("mallory", "group-123").first().isEmpty())
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
    fun observeMessagesUsesStableTimestampAndMessageIdOrdering() = runBlocking {
        val laterId = message().copy(
            messageId = "message-z",
            messageIndex = 19L,
            sentAtMillis = 1_800_000_000_100L,
            text = "Later message",
        )
        val firstById = message().copy(
            messageId = "message-a",
            messageIndex = 18L,
            sentAtMillis = 1_800_000_000_000L,
            text = "First message",
        )

        // Deliberately commit in reverse display order. The DAO must provide a
        // deterministic timeline independent of delivery/commit arrival order.
        val laterCiphertext = "later ciphertext".toByteArray(Charsets.UTF_8)
        val firstCiphertext = "first ciphertext".toByteArray(Charsets.UTF_8)
        assertTrue(repository.commitStagedMessage(laterId, receipt(laterId, laterCiphertext), laterCiphertext))
        assertTrue(repository.commitStagedMessage(firstById, receipt(firstById, firstCiphertext), firstCiphertext))

        assertEquals(
            listOf("message-a", "message-z"),
            repository.observeMessages("alice", "group-123").first().map { it.messageId },
        )
        assertTrue(repository.observeMessages("mallory", "group-123").first().isEmpty())
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
