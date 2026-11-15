package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.CachedMessageEntity
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmInboundMessageCommitterTest {
    @Test
    fun messageAndReceiptAreCommittedTogether() = runBlocking {
        withDatabase("olm-inbound-atomic-success.db") { db ->
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            assertEquals(OlmInboundRegistration.NEW, ledger.register("alice", "message-1", envelope(), 100L))
            val receipt = db.olmInboundReceiptDao().getByReceiptId(receiptId())!!
            val committer = OlmInboundMessageCommitter(db, db.messageDao(), db.olmInboundReceiptDao())

            assertTrue(committer.commit(receipt.receiptId, message(), 200L))
            assertNotNull(db.messageDao().getMessage("local-user", "alice", "message-1"))
            assertEquals(
                OlmInboundReceiptStatus.PROCESSED.name,
                db.olmInboundReceiptDao().getByReceiptId(receipt.receiptId)?.status,
            )
        }
    }

    @Test
    fun mismatchedMessageCannotChangeReceiptOrCache() = runBlocking {
        withDatabase("olm-inbound-atomic-mismatch.db") { db ->
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            ledger.register("alice", "message-2", envelope(), 100L)
            val receipt = db.olmInboundReceiptDao().getByReceiptId(receiptId("message-2"))!!
            val committer = OlmInboundMessageCommitter(db, db.messageDao(), db.olmInboundReceiptDao())

            try {
                committer.commit(receipt.receiptId, message(messageId = "wrong-id"), 200L)
                throw AssertionError("Expected message identity mismatch")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("message ID"))
            }
            assertEquals(null, db.messageDao().getMessage("local-user", "alice", "wrong-id"))
            assertEquals(OlmInboundReceiptStatus.PENDING.name, db.olmInboundReceiptDao().getByReceiptId(receipt.receiptId)?.status)
        }
    }

    @Test
    fun processedReceiptCannotBeCommittedAgain() = runBlocking {
        withDatabase("olm-inbound-atomic-duplicate.db") { db ->
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            ledger.register("alice", "message-3", envelope(), 100L)
            val receipt = db.olmInboundReceiptDao().getByReceiptId(receiptId("message-3"))!!
            val committer = OlmInboundMessageCommitter(db, db.messageDao(), db.olmInboundReceiptDao())
            assertTrue(committer.commit(receipt.receiptId, message(messageId = "message-3"), 200L))

            try {
                committer.commit(receipt.receiptId, message(messageId = "message-3", text = "changed"), 300L)
                throw AssertionError("Expected processed receipt to be rejected")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("not pending"))
            }
            assertEquals("decrypted body", db.messageDao().getMessage("local-user", "alice", "message-3")?.text)
            assertFalse(db.olmInboundReceiptDao().getByReceiptId(receipt.receiptId)?.status == OlmInboundReceiptStatus.PENDING.name)
        }
    }

    private suspend fun withDatabase(name: String, block: suspend (LocalChatDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try { block(db) } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun message(
        messageId: String = "message-1",
        text: String = "decrypted body",
    ) = CachedMessageEntity(
        ownerUserId = "local-user", chatId = "alice", messageId = messageId,
        senderId = "alice", senderNickname = "Alice", text = text, timestamp = 150L,
        replyMessageId = "", replySenderId = "", replySenderNickname = "", replyText = "",
        reactionsJson = "{}", edited = false, attachmentType = "", attachmentStorageKey = "",
        attachmentFileName = "", attachmentMimeType = "", attachmentSizeBytes = 0L,
        attachmentViewedAt = 0L, isPending = false, systemEvent = "",
    )

    private fun envelope(): String = JSONObject()
        .put("encryptionVersion", 1).put("algorithm", "olm")
        .put("senderDeviceId", "sender-device").put("recipientDeviceId", "receiver-device")
        .put("sessionId", "session-sender-device")
        .put("ciphertext", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.URL_SAFE or Base64.NO_WRAP))
        .toString()

    private fun receiptId(messageId: String = "message-1"): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("alice\u0000sender-device\u0000$messageId\u0000receiver-device".toByteArray())
            .joinToString("") { "%02x".format(it) }
}