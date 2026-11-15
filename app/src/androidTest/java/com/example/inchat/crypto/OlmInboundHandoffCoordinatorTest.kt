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
    ) = CachedMessageEntity(
        ownerUserId = localUserId,
        chatId = senderUserId,
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
