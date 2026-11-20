package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

        val replayWithDifferentReceipt = firstReceipt.copy(
            receiptId = "b".repeat(64),
            messageId = "message-replay",
        )
        val conflictingMessage = firstMessage.copy(messageId = "message-replay")
        assertFalse(dao.commitDecryptedMessage(replayWithDifferentReceipt, conflictingMessage))
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
        messageId = "message-1",
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
