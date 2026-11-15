package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseOlmInboxReceiverTest {
    @Test
    fun storesCiphertextBeforeRemovingRemoteDelivery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-receiver-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val encoded = envelope(1)
            val item = FirebaseOlmInboxItem("a".repeat(64), "alice", "sender-device", "message-1", encoded)
            val transport = FakeTransport(mutableListOf(item))
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device", clock = { 100L })

            val result = receiver.receivePending()

            assertEquals(1, result.stored)
            assertEquals(0, result.conflicts)
            assertTrue(transport.items.isEmpty())
            assertEquals(
                OlmInboundRegistration.PENDING_RETRY,
                ledger.register("alice", "message-1", encoded, 101L),
            )
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun conflictingCiphertextIsNotAcknowledged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbox-conflict-receiver-test.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            ledger.register("alice", "message-2", envelope(1), 100L)
            val item = FirebaseOlmInboxItem("b".repeat(64), "alice", "sender-device", "message-2", envelope(2))
            val transport = FakeTransport(mutableListOf(item))
            val receiver = FirebaseOlmInboxReceiver(transport, ledger, "receiver-device", clock = { 101L })

            val result = receiver.receivePending()

            assertEquals(1, result.conflicts)
            assertEquals(1, transport.items.size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    private class FakeTransport(
        val items: MutableList<FirebaseOlmInboxItem>,
    ) : OlmInboxTransport {
        override suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem> = items.toList()

        override suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String) {
            items.removeAll { it.deliveryId == deliveryId }
        }
    }

    private fun envelope(byte: Int): String = JSONObject()
        .put("encryptionVersion", 1)
        .put("algorithm", "olm")
        .put("senderDeviceId", "sender-device")
        .put("recipientDeviceId", "receiver-device")
        .put("sessionId", "session-sender")
        .put("ciphertext", Base64.encodeToString(byteArrayOf(1, 2, byte.toByte()), Base64.URL_SAFE or Base64.NO_WRAP))
        .toString()
}
