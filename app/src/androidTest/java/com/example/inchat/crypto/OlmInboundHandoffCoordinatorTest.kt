package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.CachedMessageEntity
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class OlmInboundHandoffCoordinatorTest {
    @Test
    fun coordinatorCommitsMessageAndRecoversFinalizeAfterRoomCommit() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val localBundle = DeviceIdentityRepository(context).ensurePublicKeyBundle()
        val senderDeviceId = "handoff-sender-$suffix"
        val senderStoreId = "device-handoff-sender-$suffix"
        val senderStore = CryptoStateStore(context, senderStoreId)
        val senderJson = JSONObject(
            NativeCryptoBridge.nativeEnsureDeviceIdentity(senderStore, senderDeviceId)
                ?: error("Sender identity should be created")
        )
        val senderBundle = parseBundle(senderDeviceId, senderJson)
        val senderSessionId = requireNotNull(
            NativeCryptoBridge.nativeCreateOutboundOlmSession(
                senderStore,
                senderDeviceId,
                localBundle.curve25519IdentityKey,
                requireNotNull(localBundle.oneTimeKeys.firstOrNull()).publicKey,
            )
        )
        val ciphertext = requireNotNull(
            NativeCryptoBridge.nativeEncryptOlmMessage(
                senderStore, senderDeviceId, senderSessionId, "handoff plaintext"
            )
        )
        val encodedEnvelope = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = senderDeviceId,
                recipientDeviceId = localBundle.deviceId,
                sessionId = senderSessionId,
                ciphertext = ciphertext,
            )
        )

        val databaseName = "olm-inbound-handoff-$suffix.db"
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), localBundle.deviceId)
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", "handoff-$suffix", encodedEnvelope, 100L),
            )
            val receiptId = sha256(
                "alice\u0000$senderDeviceId\u0000handoff-$suffix\u0000${localBundle.deviceId}"
            )
            val receipt = requireNotNull(db.olmInboundReceiptDao().getByReceiptId(receiptId))
            val repository = OlmSessionRepository(context)

            // Simulate process recreation after native prepare and Room commit,
            // but before native finalize. The next coordinator pass must only finalize.
            val staged = repository.prepareInboundSessionFromEnvelope(
                receiptId, senderBundle, receipt.envelope
            )
            assertEquals("handoff plaintext", staged.plaintext)
            val message = cachedMessage(
                localUserId = "local-user",
                senderUserId = "alice",
                messageId = receipt.messageId,
                plaintext = staged.plaintext,
            )
            assertTrue(
                OlmInboundMessageCommitter(db, db.messageDao(), db.olmInboundReceiptDao())
                    .commit(receiptId, message, 200L)
            )

            val reopenedRepository = OlmSessionRepository(context)
            val coordinator = OlmInboundHandoffCoordinator(
                database = db,
                receiptDao = db.olmInboundReceiptDao(),
                sessionRepository = reopenedRepository,
                messageDao = db.messageDao(),
            )
            assertEquals(
                OlmInboundHandoffResult.ALREADY_COMMITTED,
                coordinator.process(
                    receiptId,
                    senderBundle,
                    OlmInboundCiphertextKind.PRE_KEY,
                ) { error("A committed receipt must not rebuild or insert its message") },
            )
            assertEquals(
                "handoff plaintext",
                db.messageDao().getMessage("local-user", "alice", receipt.messageId)?.text,
            )
            assertEquals(
                OlmInboundReceiptStatus.PROCESSED.name,
                db.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )
            assertTrue(
                "The staged plaintext should be removed after recovery",
                repository.finalizeInboundDecryption(receiptId),
            )
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }


    @Test
    fun coordinatorRetriesMappingFailureThenCommitsExactlyOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val localBundle = DeviceIdentityRepository(context).ensurePublicKeyBundle()
        val senderDeviceId = "handoff-retry-sender-$suffix"
        val senderStore = CryptoStateStore(context, "device-handoff-retry-sender-$suffix")
        val senderJson = JSONObject(
            NativeCryptoBridge.nativeEnsureDeviceIdentity(senderStore, senderDeviceId)
                ?: error("Sender identity should be created")
        )
        val senderBundle = parseBundle(senderDeviceId, senderJson)
        val sessionId = requireNotNull(
            NativeCryptoBridge.nativeCreateOutboundOlmSession(
                senderStore,
                senderDeviceId,
                localBundle.curve25519IdentityKey,
                requireNotNull(localBundle.oneTimeKeys.firstOrNull()).publicKey,
            )
        )
        val wire = requireNotNull(
            NativeCryptoBridge.nativeEncryptOlmMessage(
                senderStore, senderDeviceId, sessionId, "retryable handoff plaintext"
            )
        )
        val envelope = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = senderDeviceId,
                recipientDeviceId = localBundle.deviceId,
                sessionId = sessionId,
                ciphertext = wire,
            )
        )
        val databaseName = "olm-inbound-handoff-retry-$suffix.db"
        val db = Room.databaseBuilder(context, LocalChatDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val messageId = "handoff-retry-$suffix"
            val ledger = OlmInboundDeliveryLedger(db.olmInboundReceiptDao(), localBundle.deviceId)
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", messageId, envelope, 300L),
            )
            val receiptId = sha256(
                "alice\u0000$senderDeviceId\u0000$messageId\u0000${localBundle.deviceId}"
            )
            val receipt = requireNotNull(db.olmInboundReceiptDao().getByReceiptId(receiptId))
            val repository = OlmSessionRepository(context)
            val coordinator = OlmInboundHandoffCoordinator(
                database = db,
                receiptDao = db.olmInboundReceiptDao(),
                sessionRepository = repository,
                messageDao = db.messageDao(),
                clock = { 400L },
            )

            var mappingFailed = false
            try {
                coordinator.process(
                    receiptId,
                    senderBundle,
                    OlmInboundCiphertextKind.PRE_KEY,
                ) { error("Simulated invalid authenticated payload metadata") }
            } catch (_: IllegalStateException) {
                mappingFailed = true
            }
            assertTrue("Message mapping failure should propagate", mappingFailed)
            var routingFailed = false
            try {
                coordinator.process(
                    receiptId,
                    senderBundle,
                    OlmInboundCiphertextKind.PRE_KEY,
                ) { plaintext ->
                    cachedMessage(
                        "local-user",
                        "alice",
                        messageId,
                        plaintext,
                        chatId = "wrong-chat",
                    )
                }
            } catch (_: IllegalArgumentException) {
                routingFailed = true
            }
            assertTrue("A mismatched chat route must be rejected", routingFailed)
            assertEquals(
                OlmInboundReceiptStatus.PENDING.name,
                db.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )
            assertEquals(
                null,
                db.messageDao().getMessage("local-user", "alice", messageId),
            )

            assertEquals(
                OlmInboundHandoffResult.COMMITTED,
                coordinator.process(
                    receiptId,
                    senderBundle,
                    OlmInboundCiphertextKind.PRE_KEY,
                ) { plaintext ->
                    cachedMessage("local-user", "alice", messageId, plaintext)
                },
            )
            assertEquals(
                "retryable handoff plaintext",
                db.messageDao().getMessage("local-user", "alice", messageId)?.text,
            )
            assertEquals(
                OlmInboundReceiptStatus.PROCESSED.name,
                db.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )
            assertEquals(
                OlmInboundHandoffResult.ALREADY_COMMITTED,
                coordinator.process(
                    receiptId,
                    senderBundle,
                    OlmInboundCiphertextKind.PRE_KEY,
                ) { error("Duplicate processing must not create another message") },
            )

            // The established session now carries a regular Olm message.
            val normalMessageId = "handoff-normal-$suffix"
            val normalWire = requireNotNull(
                NativeCryptoBridge.nativeEncryptOlmMessage(
                    senderStore, senderDeviceId, sessionId, "normal handoff plaintext"
                )
            )
            val normalEnvelope = EncryptedOlmEnvelopeCodec.encode(
                EncryptedOlmEnvelope(
                    senderDeviceId = senderDeviceId,
                    recipientDeviceId = localBundle.deviceId,
                    sessionId = sessionId,
                    ciphertext = normalWire,
                )
            )
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", normalMessageId, normalEnvelope, 500L),
            )
            val normalReceiptId = sha256(
                "alice\u0000$senderDeviceId\u0000$normalMessageId\u0000${localBundle.deviceId}"
            )
            assertEquals(
                OlmInboundHandoffResult.COMMITTED,
                coordinator.process(
                    normalReceiptId,
                    senderBundle,
                    OlmInboundCiphertextKind.NORMAL,
                ) { plaintext ->
                    cachedMessage("local-user", "alice", normalMessageId, plaintext)
                },
            )
            assertEquals(
                "normal handoff plaintext",
                db.messageDao().getMessage("local-user", "alice", normalMessageId)?.text,
            )

            // A normal-message mapping failure must also retain the staged
            // plaintext so the same receipt can be retried safely.
            val retryNormalMessageId = "handoff-normal-retry-$suffix"
            val retryNormalWire = requireNotNull(
                NativeCryptoBridge.nativeEncryptOlmMessage(
                    senderStore, senderDeviceId, sessionId, "normal retry plaintext"
                )
            )
            val retryNormalEnvelope = EncryptedOlmEnvelopeCodec.encode(
                EncryptedOlmEnvelope(
                    senderDeviceId = senderDeviceId,
                    recipientDeviceId = localBundle.deviceId,
                    sessionId = sessionId,
                    ciphertext = retryNormalWire,
                )
            )
            assertEquals(
                OlmInboundRegistration.NEW,
                ledger.register("alice", retryNormalMessageId, retryNormalEnvelope, 600L),
            )
            val retryNormalReceiptId = sha256(
                "alice\u0000$senderDeviceId\u0000$retryNormalMessageId\u0000${localBundle.deviceId}"
            )
            var normalMappingFailed = false
            try {
                coordinator.process(
                    retryNormalReceiptId,
                    senderBundle,
                    OlmInboundCiphertextKind.NORMAL,
                ) { error("Simulated normal-message metadata failure") }
            } catch (_: IllegalStateException) {
                normalMappingFailed = true
            }
            assertTrue("Normal-message mapping failure should propagate", normalMappingFailed)
            assertEquals(
                OlmInboundReceiptStatus.PENDING.name,
                db.olmInboundReceiptDao().getByReceiptId(retryNormalReceiptId)?.status,
            )
            assertEquals(
                OlmInboundHandoffResult.COMMITTED,
                coordinator.process(
                    retryNormalReceiptId,
                    senderBundle,
                    OlmInboundCiphertextKind.NORMAL,
                ) { plaintext ->
                    cachedMessage("local-user", "alice", retryNormalMessageId, plaintext)
                },
            )
            assertEquals(
                "normal retry plaintext",
                db.messageDao().getMessage("local-user", "alice", retryNormalMessageId)?.text,
            )
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun parseBundle(deviceId: String, json: JSONObject): PublicDeviceKeyBundle {
        val identity = json.getJSONObject("identityKeys")
        fun parsePrekey(value: JSONObject) = SignedOlmPreKey(
            keyKind = value.getString("keyKind"),
            keyId = value.getString("keyId"),
            publicKey = value.getString("key"),
            signature = value.getString("signature"),
        )
        val oneTime = json.getJSONArray("oneTimeKeys")
        return PublicDeviceKeyBundle(
            deviceId = deviceId,
            ed25519IdentityKey = identity.getString("ed25519"),
            curve25519IdentityKey = identity.getString("curve25519"),
            curve25519IdentityKeySignature = identity.getString("curve25519Signature"),
            fallbackKey = parsePrekey(json.getJSONObject("fallbackKey")),
            oneTimeKeys = (0 until oneTime.length()).map { parsePrekey(oneTime.getJSONObject(it)) },
        )
    }

    private fun cachedMessage(
        localUserId: String,
        senderUserId: String,
        messageId: String,
        plaintext: String,
        chatId: String = senderUserId,
    ) = CachedMessageEntity(
        ownerUserId = localUserId,
        chatId = chatId,
        messageId = messageId,
        senderId = senderUserId,
        senderNickname = "Alice",
        text = plaintext,
        timestamp = 150L,
        replyMessageId = "",
        replySenderId = "",
        replySenderNickname = "",
        replyText = "",
        reactionsJson = "{}",
        edited = false,
        attachmentType = "",
        attachmentStorageKey = "",
        attachmentFileName = "",
        attachmentMimeType = "",
        attachmentSizeBytes = 0L,
        attachmentViewedAt = 0L,
        isPending = false,
        systemEvent = "",
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
