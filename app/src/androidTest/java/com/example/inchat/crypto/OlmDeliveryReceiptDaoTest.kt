package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OlmDeliveryReceiptDaoTest {

    @Test
    fun recipientAcknowledgementPersistsOnlyScopedDeliveryMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-delivery-receipt-test.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

        try {
            val recorder = RoomOlmDeliveryAcknowledgementRecorder(
                database = database,
                dao = database.olmDeliveryReceiptDao(),
            )
            val acknowledgement = OlmDeliveryAcknowledgement(
                deliveryId = "a".repeat(64),
                recipientUserId = "bob",
                recipientDeviceId = "bob-phone",
                messageId = "message-1",
                senderUserId = "alice",
                senderDeviceId = "alice-phone",
            )

            recorder.record(acknowledgement, 12_345L)
            recorder.record(acknowledgement, 99_999L)

            val persisted = database.olmDeliveryReceiptDao()
                .getByDeliveryId(acknowledgement.deliveryId)
            assertEquals("alice", persisted?.ownerUserId)
            assertEquals("alice-phone", persisted?.senderDeviceId)
            assertEquals("bob", persisted?.recipientUserId)
            assertEquals("bob-phone", persisted?.recipientDeviceId)
            assertEquals("message-1", persisted?.messageId)
            assertEquals(12_345L, persisted?.acknowledgedAtMillis)
            assertNull(database.openHelper.writableDatabase.query(
                "PRAGMA table_info(olm_delivery_receipts)"
            ).use { cursor ->
                val names = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(1))
                }
                names.firstOrNull { it.equals("envelope", ignoreCase = true) ||
                    it.equals("ciphertext", ignoreCase = true) ||
                    it.equals("text", ignoreCase = true) }
            })

            assertEquals(
                listOf("message-1"),
                database.olmDeliveryReceiptDao().getDeliveredMessageIds(
                    ownerUserId = "alice",
                    senderDeviceId = "alice-phone",
                    recipientUserId = "bob",
                ),
            )
            assertEquals(
                emptyList<String>(),
                database.olmDeliveryReceiptDao().getDeliveredMessageIds(
                    ownerUserId = "mallory",
                    senderDeviceId = "alice-phone",
                    recipientUserId = "bob",
                ),
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun conflictingExistingDeliveryIdIsRejectedInsteadOfSilentlyAccepted() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-delivery-receipt-conflict-test.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = database.olmDeliveryReceiptDao()
            dao.insertIfAbsent(
                OlmDeliveryReceiptEntity(
                    deliveryId = "c".repeat(64),
                    ownerUserId = "alice",
                    senderDeviceId = "alice-phone",
                    messageId = "message-original",
                    recipientUserId = "bob",
                    recipientDeviceId = "bob-phone",
                    acknowledgedAtMillis = 10L,
                ),
            )
            val recorder = RoomOlmDeliveryAcknowledgementRecorder(database, dao)
            var rejected = false
            try {
                recorder.record(
                    OlmDeliveryAcknowledgement(
                        deliveryId = "c".repeat(64),
                        recipientUserId = "mallory",
                        recipientDeviceId = "mallory-phone",
                        messageId = "message-forged",
                        senderUserId = "alice",
                        senderDeviceId = "alice-phone",
                    ),
                    20L,
                )
            } catch (expected: IllegalStateException) {
                rejected = expected.message.orEmpty().contains("conflicts with an existing")
            }
            assertEquals(true, rejected)
            val persisted = dao.getByDeliveryId("c".repeat(64))
            assertEquals("message-original", persisted?.messageId)
            assertEquals("bob", persisted?.recipientUserId)
            assertEquals("bob-phone", persisted?.recipientDeviceId)
            assertEquals(10L, persisted?.acknowledgedAtMillis)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun senderCompanionAcknowledgementPersistsWithoutMarkingRemoteRecipientDelivered() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-delivery-receipt-self-device-test.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

        try {
            RoomOlmDeliveryAcknowledgementRecorder(
                database = database,
                dao = database.olmDeliveryReceiptDao(),
            ).record(
                OlmDeliveryAcknowledgement(
                    deliveryId = "b".repeat(64),
                    recipientUserId = "alice",
                    recipientDeviceId = "alice-tablet",
                    messageId = "message-2",
                    senderUserId = "alice",
                    senderDeviceId = "alice-phone",
                ),
                12_345L,
            )

            assertEquals(
                "alice",
                database.olmDeliveryReceiptDao().getByDeliveryId("b".repeat(64))?.recipientUserId,
            )
            assertEquals(
                listOf("message-2"),
                database.olmDeliveryReceiptDao().getDeliveredMessageIds(
                    ownerUserId = "alice",
                    senderDeviceId = "alice-phone",
                    recipientUserId = "alice",
                ),
            )
            assertEquals(
                emptyList<String>(),
                database.olmDeliveryReceiptDao().getDeliveredMessageIds(
                    ownerUserId = "alice",
                    senderDeviceId = "alice-phone",
                    recipientUserId = "bob",
                ),
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun concurrentDuplicateAcknowledgementsCommitOneImmutableReceipt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-delivery-receipt-concurrent-idempotency-test.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = database.olmDeliveryReceiptDao()
            val recorder = RoomOlmDeliveryAcknowledgementRecorder(database, dao)
            val acknowledgement = OlmDeliveryAcknowledgement(
                deliveryId = "d".repeat(64),
                recipientUserId = "bob",
                recipientDeviceId = "bob-phone",
                messageId = "concurrent-message",
                senderUserId = "alice",
                senderDeviceId = "alice-phone",
            )

            kotlinx.coroutines.coroutineScope {
                listOf(12_345L, 99_999L).map { timestamp ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        recorder.record(acknowledgement, timestamp)
                    }
                }.forEach { it.await() }
            }

            val persisted = dao.getByDeliveryId(acknowledgement.deliveryId)
            assertEquals("alice", persisted?.ownerUserId)
            assertEquals("alice-phone", persisted?.senderDeviceId)
            assertEquals("concurrent-message", persisted?.messageId)
            assertEquals("bob", persisted?.recipientUserId)
            assertEquals("bob-phone", persisted?.recipientDeviceId)
            assertTrue(persisted != null && persisted.acknowledgedAtMillis in listOf(12_345L, 99_999L))
            assertEquals(
                listOf("concurrent-message"),
                dao.getDeliveredMessageIds("alice", "alice-phone", "bob"),
            )
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

}
