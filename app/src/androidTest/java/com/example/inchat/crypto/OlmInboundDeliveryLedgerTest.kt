package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmInboundDeliveryLedgerTest {
    @Test
    fun pendingAndProcessedReceiptsSurviveDatabaseReopenAndDeduplicate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-receipts-test.db"
        context.deleteDatabase(name)
        val first = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val envelope = envelope("receiver-device", "sender-device", 7)
        try {
            val ledger = OlmInboundDeliveryLedger(first.olmInboundReceiptDao(), "receiver-device")
            assertEquals(OlmInboundRegistration.NEW, ledger.register("alice", "message-123", envelope, 1_000))
            assertEquals(OlmInboundRegistration.PENDING_RETRY, ledger.register("alice", "message-123", envelope, 2_000))
            assertTrue(ledger.markProcessed("alice", "message-123", "sender-device"))
        } finally { first.close() }

        val second = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = OlmInboundDeliveryLedger(second.olmInboundReceiptDao(), "receiver-device")
            assertEquals(
                OlmInboundRegistration.DUPLICATE_PROCESSED,
                ledger.register("alice", "message-123", envelope, 3_000),
            )
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun reusedMessageIdWithDifferentCiphertextIsRejectedAsConflict() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-conflict-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "message-456", envelope("receiver-device", "sender-device", 1), 10),
            )
            assertEquals(
                OlmInboundRegistration.CONFLICT,
                ledger.register("alice", "message-456", envelope("receiver-device", "sender-device", 2), 11),
            )
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test
    fun envelopeForAnotherDeviceIsRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-wrong-device-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            try {
                ledger.register("alice", "message-789", envelope("other-device", "sender-device", 1), 10)
                throw AssertionError("Expected wrong-recipient envelope to be rejected")
            } catch (_: IllegalArgumentException) { /* Expected. */ }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    private fun envelope(recipient: String, sender: String, ciphertextByte: Int): String =
        JSONObject()
            .put("encryptionVersion", 1)
            .put("algorithm", "olm")
            .put("senderDeviceId", sender)
            .put("recipientDeviceId", recipient)
            .put("sessionId", "session-$sender")
            .put("ciphertext", Base64.encodeToString(byteArrayOf(1, 2, ciphertextByte.toByte()), Base64.URL_SAFE or Base64.NO_WRAP))
            .toString()
}
