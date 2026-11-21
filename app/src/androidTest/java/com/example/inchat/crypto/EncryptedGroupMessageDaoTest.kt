package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.inchat.data.local.LocalChatDatabase
import java.io.Closeable
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedGroupMessageDaoTest {
    private val database: LocalChatDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        LocalChatDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun messageAndReplayLedgerCommitAtomicallyAndDuplicateIndexIsRejected() = runBlockingTest {
        val dao = database.encryptedGroupMessageDao()
        val firstReceipt = receipt(receiptId = "a".repeat(64))
        val firstMessage = message()
        assertTrue(dao.commitDecryptedMessage(firstReceipt, firstMessage))
        assertTrue(
            dao.hasReplay(
                "alice", "group-1", "bob", "bob-phone", "1234567890abcdef", 9L,
            ),
        )

        // Exact same authenticated replay is idempotent.
        assertFalse(dao.commitDecryptedMessage(firstReceipt, firstMessage))

        // Reusing a valid Megolm index with altered receipt metadata is a conflict.
        val replayWithDifferentReceipt = firstReceipt.copy(
            receiptId = "b".repeat(64),
            messageId = "message-replay",
        )
        val conflictingMessage = firstMessage.copy(messageId = "message-replay")
        var replayConflictRejected = false
        try {
            dao.commitDecryptedMessage(replayWithDifferentReceipt, conflictingMessage)
        } catch (_: IllegalStateException) {
            replayConflictRejected = true
        }
        assertTrue(replayConflictRejected)
        assertTrue(dao.getMessage("alice", "group-1", "message-1") != null)
        assertTrue(dao.getMessage("alice", "group-1", "message-replay") == null)
    }

    @Test
    fun messageIdCollisionRollsBackTheNewReplayReceipt() = runBlockingTest {
        val dao = database.encryptedGroupMessageDao()
        val original = message()
        assertTrue(dao.commitDecryptedMessage(receipt(), original))

        val otherIndexReceipt = receipt(
            receiptId = "c".repeat(64),
            messageIndex = 10L,
        )
        val conflictingIdMessage = original.copy(messageIndex = 10L)
        var rejected = false
        try {
            dao.commitDecryptedMessage(otherIndexReceipt, conflictingIdMessage)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertFalse(
            dao.hasReplay(
                "alice", "group-1", "bob", "bob-phone", "1234567890abcdef", 10L,
            ),
        )
    }

    @Test
    fun receiptIdCollisionWithDifferentReplayKeyRollsBackTheSecondCommit() = runBlockingTest {
        val dao = database.encryptedGroupMessageDao()
        val sharedReceiptId = "f".repeat(64)
        val originalReceipt = receipt(receiptId = sharedReceiptId)
        val originalMessage = message()
        assertTrue(dao.commitDecryptedMessage(originalReceipt, originalMessage))

        // A receipt ID is globally unique as well as the Megolm replay key.
        // Reusing it for a different index must fail closed and roll back both
        // attempted inserts, leaving the original message and replay intact.
        val conflictingReceipt = receipt(
            receiptId = sharedReceiptId,
            messageIndex = 11L,
        ).copy(messageId = "message-receipt-collision")
        val conflictingMessage = originalMessage.copy(
            messageId = "message-receipt-collision",
            messageIndex = 11L,
        )

        var rejected = false
        try {
            dao.commitDecryptedMessage(conflictingReceipt, conflictingMessage)
        } catch (_: IllegalStateException) {
            rejected = true
        }

        assertTrue("A receipt ID cannot be rebound to another replay key", rejected)
        assertTrue(dao.getMessage("alice", "group-1", "message-1") != null)
        assertTrue(dao.getMessage("alice", "group-1", "message-receipt-collision") == null)
        assertFalse(
            dao.hasReplay(
                "alice", "group-1", "bob", "bob-phone", "1234567890abcdef", 11L,
            ),
        )
        assertTrue(
            dao.hasReplay(
                "alice", "group-1", "bob", "bob-phone", "1234567890abcdef", 9L,
            ),
        )
    }

    private fun receipt(
        receiptId: String = "d".repeat(64),
        messageIndex: Long = 9L,
    ) = EncryptedGroupMessageReceiptEntity(
        ownerUserId = "alice",
        groupId = "group-1",
        senderUserId = "bob",
        senderDeviceId = "bob-phone",
        megolmSessionId = "1234567890abcdef",
        messageIndex = messageIndex,
        membershipEpoch = 5L,
        messageId = "message-1",
        sentAtMillis = 1_800_000_000_000L,
        receiptId = receiptId,
        ciphertextSha256 = "e".repeat(64),
        receivedAtMillis = 1_800_000_000_000L,
    )

    private fun message() = EncryptedGroupMessageEntity(
        ownerUserId = "alice",
        groupId = "group-1",
        messageId = "message-1",
        membershipEpoch = 5L,
        senderUserId = "bob",
        senderDeviceId = "bob-phone",
        megolmSessionId = "1234567890abcdef",
        messageIndex = 9L,
        sentAtMillis = 1_800_000_000_000L,
        contentEncryptionVersion = 1,
        textNonceBase64 = "AAAAAAAAAAAAAAAA",
        textCiphertextBase64 = "AAAAAAAAAAAAAAAAAAAAAA",
    )

    private fun runBlockingTest(block: suspend () -> Unit) {
        kotlinx.coroutines.runBlocking { block() }
    }
}
