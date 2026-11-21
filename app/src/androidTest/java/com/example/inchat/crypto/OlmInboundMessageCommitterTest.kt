package com.example.inchat.crypto

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
            val committer = OlmInboundMessageCommitter(db, db.encryptedOlmMessageDao(), db.olmInboundReceiptDao())

            assertTrue(committer.commit(receipt.receiptId, message(), 200L))
            val encrypted = requireNotNull(
                db.encryptedOlmMessageDao().getMessage("local-user", "alice_local-user", "message-1"),
            )
            assertEquals("decrypted body", EncryptedOlmMessageVault().decrypt(encrypted).text)
            assertFalse(encrypted.textCiphertextBase64.contains("decrypted body"))
            // Existing ChatRepository observes this plaintext DAO; encrypted content must never enter it.
            assertEquals(null, db.messageDao().getMessage("local-user", "alice_local-user", "message-1"))
            assertEquals(
                OlmInboundReceiptStatus.PROCESSED.name,
                db.olmInboundReceiptDao().getByReceiptId(receipt.receiptId)?.status,
            )
        }
    }

    @Test
    fun concurrentCommittersCannotOverwriteTheFirstCommittedMessage() = runBlocking {
        withDatabase("olm-inbound-concurrent-commit.db") { db ->
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "concurrent-commit", envelope(), 100L),
            )
            val receipt = db.olmInboundReceiptDao().getByReceiptId(receiptId("concurrent-commit"))!!
            val firstCommitter = OlmInboundMessageCommitter(db, db.encryptedOlmMessageDao(), db.olmInboundReceiptDao())
            val secondCommitter = OlmInboundMessageCommitter(db, db.encryptedOlmMessageDao(), db.olmInboundReceiptDao())
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)

            try {
                val attempts = listOf(
                    firstCommitter to "first concurrent plaintext",
                    secondCommitter to "second concurrent plaintext",
                ).map { (committer, plaintext) ->
                    pool.submit(Callable {
                        start.await()
                        runBlocking {
                            runCatching {
                                committer.commit(
                                    receipt.receiptId,
                                    message(
                                        messageId = "concurrent-commit",
                                        text = plaintext,
                                    ),
                                    200L,
                                )
                            }
                        }
                    })
                }
                start.countDown()
                val results = attempts.map { it.get(10, TimeUnit.SECONDS) }

                assertEquals(1, results.count { it.getOrNull() == true })
                assertEquals(1, results.count { it.isFailure })
                assertTrue(
                    results.filter { it.isFailure }.all {
                        it.exceptionOrNull() is IllegalStateException
                    },
                )

                val stored = db.encryptedOlmMessageDao().getMessage("local-user", "alice_local-user", "concurrent-commit")
                assertNotNull(stored)
                val restoredText = EncryptedOlmMessageVault().decrypt(requireNotNull(stored)).text
                assertTrue(
                    restoredText == "first concurrent plaintext" ||
                        restoredText == "second concurrent plaintext",
                )
                assertEquals(
                    OlmInboundReceiptStatus.PROCESSED.name,
                    db.olmInboundReceiptDao().getByReceiptId(receipt.receiptId)?.status,
                )
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun mismatchedMessageCannotChangeReceiptOrCache() = runBlocking {
        withDatabase("olm-inbound-atomic-mismatch.db") { db ->
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            ledger.register("alice", "message-2", envelope(), 100L)
            val receipt = db.olmInboundReceiptDao().getByReceiptId(receiptId("message-2"))!!
            val committer = OlmInboundMessageCommitter(db, db.encryptedOlmMessageDao(), db.olmInboundReceiptDao())

            try {
                committer.commit(receipt.receiptId, message(messageId = "wrong-id"), 200L)
                throw AssertionError("Expected message identity mismatch")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("message ID"))
            }
            assertEquals(null, db.encryptedOlmMessageDao().getMessage("local-user", "alice_local-user", "wrong-id"))
            assertEquals(OlmInboundReceiptStatus.PENDING.name, db.olmInboundReceiptDao().getByReceiptId(receipt.receiptId)?.status)
        }
    }


    @Test
    fun secondSenderDeviceCannotOverwriteAnExistingConversationMessageId() = runBlocking {
        withDatabase("olm-inbound-message-id-collision.db") { db ->
            val firstLedger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            assertEquals(
                OlmInboundRegistration.NEW,
                firstLedger.register("alice", "shared-message-id", envelope(), 100L),
            )
            val committer = OlmInboundMessageCommitter(
                db,
                db.encryptedOlmMessageDao(),
                db.olmInboundReceiptDao(),
            )
            assertTrue(
                committer.commit(
                    receiptId("shared-message-id"),
                    message(messageId = "shared-message-id"),
                    200L,
                ),
            )
            val original = requireNotNull(
                db.encryptedOlmMessageDao().getMessage(
                    "local-user",
                    "alice_local-user",
                    "shared-message-id",
                ),
            )

            val secondDevice = "sender-device-two"
            val secondEnvelope = JSONObject(envelope())
                .put("senderDeviceId", secondDevice)
                .put("sessionId", "session-$secondDevice")
                .toString()
            val secondLedger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            assertEquals(
                OlmInboundRegistration.NEW,
                secondLedger.register("alice", "shared-message-id", secondEnvelope, 300L),
            )
            val secondReceiptId = java.security.MessageDigest.getInstance("SHA-256")
                .digest(("alice" + 0.toChar() + secondDevice + 0.toChar() +
                    "shared-message-id" + 0.toChar() + "receiver-device").toByteArray())
                .joinToString("") { "%02x".format(it) }

            try {
                committer.commit(
                    secondReceiptId,
                    message(messageId = "shared-message-id").copy(
                        senderDeviceId = secondDevice,
                        text = "replacement plaintext",
                    ),
                    400L,
                )
                throw AssertionError("A second sender device must not overwrite an existing message identity")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("already exists"))
            }

            val unchanged = requireNotNull(
                db.encryptedOlmMessageDao().getMessage(
                    "local-user",
                    "alice_local-user",
                    "shared-message-id",
                ),
            )
            assertEquals("decrypted body", EncryptedOlmMessageVault().decrypt(original).text)
            assertEquals("decrypted body", EncryptedOlmMessageVault().decrypt(unchanged).text)
            assertEquals(
                OlmInboundReceiptStatus.PENDING.name,
                db.olmInboundReceiptDao().getByReceiptId(secondReceiptId)?.status,
            )
        }
    }

    @Test
    fun processedReceiptCannotBeCommittedAgain() = runBlocking {
        withDatabase("olm-inbound-atomic-duplicate.db") { db ->
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
            ledger.register("alice", "message-3", envelope(), 100L)
            val receipt = db.olmInboundReceiptDao().getByReceiptId(receiptId("message-3"))!!
            val committer = OlmInboundMessageCommitter(db, db.encryptedOlmMessageDao(), db.olmInboundReceiptDao())
            assertTrue(committer.commit(receipt.receiptId, message(messageId = "message-3"), 200L))

            try {
                committer.commit(receipt.receiptId, message(messageId = "message-3", text = "changed"), 300L)
                throw AssertionError("Expected processed receipt to be rejected")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("not pending"))
            }
            assertEquals(
                "decrypted body",
                EncryptedOlmMessageVault().decrypt(
                    requireNotNull(db.encryptedOlmMessageDao().getMessage("local-user", "alice_local-user", "message-3")),
                ).text,
            )
            assertFalse(db.olmInboundReceiptDao().getByReceiptId(receipt.receiptId)?.status == OlmInboundReceiptStatus.PENDING.name)
        }
    }

    @Test
    fun existingEncryptedHistoryPreventsSilentKeystoreKeyRecreation() = runBlocking {
        withDatabase("olm-inbound-missing-key.db") { db ->
            val alias = EncryptedOlmMessageVault.DEFAULT_KEY_ALIAS + "-" + UUID.randomUUID()
            val vault = EncryptedOlmMessageVault(alias)
            try {
                val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), "receiver-device")
                assertEquals(
                    OlmInboundRegistration.NEW,
                    ledger.register("alice", "message-1", envelope(), 100L),
                )
                val firstReceipt = db.olmInboundReceiptDao().getByReceiptId(receiptId())!!
                val committer = OlmInboundMessageCommitter(
                    db,
                    db.encryptedOlmMessageDao(),
                    db.olmInboundReceiptDao(),
                    vault,
                )
                assertTrue(committer.commit(firstReceipt.receiptId, message(), 200L))

                assertEquals(
                    OlmInboundRegistration.NEW,
                    ledger.register("alice", "message-2", envelope(), 300L),
                )
                val secondReceipt = db.olmInboundReceiptDao().getByReceiptId(receiptId("message-2"))!!
                deleteKeystoreAlias(alias)

                var rejected = false
                try {
                    committer.commit(
                        secondReceipt.receiptId,
                        message(messageId = "message-2"),
                        400L,
                    )
                } catch (expected: GeneralSecurityException) {
                    rejected = expected.message.orEmpty().contains("key is missing")
                }

                assertTrue("A missing key must fail closed when encrypted history exists", rejected)
                assertFalse(keystoreAliasExists(alias))
                assertEquals(
                    OlmInboundReceiptStatus.PENDING.name,
                    db.olmInboundReceiptDao().getByReceiptId(secondReceipt.receiptId)?.status,
                )
                assertEquals(
                    null,
                    db.encryptedOlmMessageDao().getMessage("local-user", "alice_local-user", "message-2"),
                )
            } finally {
                deleteKeystoreAlias(alias)
            }
        }
    }

    private fun keystoreAliasExists(alias: String): Boolean {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        return keyStore.containsAlias(alias)
    }

    private fun deleteKeystoreAlias(alias: String) {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
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
    ) = DecryptedOlmMessage(
        ownerUserId = "local-user",
        conversationId = "alice_local-user",
        messageId = messageId,
        conversationPeerUserId = "local-user",
        senderUserId = "alice",
        senderDeviceId = "sender-device",
        recipientUserId = "local-user",
        recipientDeviceId = "receiver-device",
        sentAtMillis = 150L,
        text = text,
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