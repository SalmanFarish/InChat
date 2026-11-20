package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class OlmInboundHandoffCoordinatorTest {
    private val directChatId =
        OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "local-user")
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
        val initialPayload = authenticatedPayload(
            "handoff-$suffix", "alice", senderDeviceId, "local-user",
            localBundle.deviceId, 150L, "handoff plaintext",
        )
        val ciphertext = requireNotNull(
            NativeCryptoBridge.nativeEncryptOlmMessage(
                senderStore, senderDeviceId, senderSessionId,
                OlmAuthenticatedMessagePayloadCodec.encode(initialPayload),
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
            val decodedPayload = OlmAuthenticatedMessagePayloadCodec.decodeAndValidate(
                staged.plaintext, receipt.messageId, "alice", senderDeviceId,
                "local-user", localBundle.deviceId,
            )
            assertEquals("handoff plaintext", decodedPayload.text)
            val message = encryptedMessage(decodedPayload)
            assertTrue(
                OlmInboundMessageCommitter(db, db.encryptedOlmMessageDao(), db.olmInboundReceiptDao())
                    .commit(receiptId, message, 200L)
            )

            val reopenedRepository = OlmSessionRepository(context)
            val coordinator = OlmInboundHandoffCoordinator(
                database = db,
                receiptDao = db.olmInboundReceiptDao(),
                sessionRepository = reopenedRepository,
                authenticatedLocalUserId = { "local-user" },
                trustVerifier = trustedDevice("alice", senderBundle),
                encryptedMessageDao = db.encryptedOlmMessageDao(),
            )
            assertTrue(
                "Committed plaintext staging should finalize without rebuilding the Room message",
                coordinator.finalizePreviouslyCommitted(receiptId, "local-user"),
            )
            assertEquals(
                "handoff plaintext",
                db.encryptedOlmMessageDao().getMessage("local-user", directChatId, receipt.messageId)?.text,
            )
            assertEquals(
                OlmInboundReceiptStatus.FINALIZED.name,
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
        val firstPayload = authenticatedPayload(
            "handoff-retry-$suffix", "alice", senderDeviceId, "local-user",
            localBundle.deviceId, 150L, "retryable handoff plaintext",
        )
        val wire = requireNotNull(
            NativeCryptoBridge.nativeEncryptOlmMessage(
                senderStore, senderDeviceId, sessionId,
                OlmAuthenticatedMessagePayloadCodec.encode(firstPayload),
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
                authenticatedLocalUserId = { "local-user" },
                trustVerifier = trustedDevice("alice", senderBundle),
                encryptedMessageDao = db.encryptedOlmMessageDao(),
                clock = { 400L },
            )

            val wrongLocalAccountCoordinator = OlmInboundHandoffCoordinator(
                database = db,
                receiptDao = db.olmInboundReceiptDao(),
                sessionRepository = repository,
                authenticatedLocalUserId = { "different-local-account" },
                trustVerifier = trustedDevice("alice", senderBundle),
                encryptedMessageDao = db.encryptedOlmMessageDao(),
            )
            var localAccountMismatchRejected = false
            try {
                wrongLocalAccountCoordinator.process(
                    receiptId,
                    senderBundle,
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = "local-user",
                ) { payload -> encryptedMessage(payload) }
            } catch (_: IllegalArgumentException) {
                localAccountMismatchRejected = true
            }
            assertTrue("A caller cannot substitute a different authenticated local account", localAccountMismatchRejected)

            var senderAccountMismatchRejected = false
            try {
                coordinator.process(
                    receiptId,
                    senderBundle,
                    remoteDeviceUserId = "mallory",
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = "local-user",
                ) { payload -> encryptedMessage(payload) }
            } catch (_: IllegalArgumentException) {
                senderAccountMismatchRejected = true
            }
            assertTrue(
                "A verified device loaded under another account must be rejected",
                senderAccountMismatchRejected,
            )
            assertEquals(
                OlmInboundReceiptStatus.PENDING.name,
                db.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )

            val untrustedCoordinator = OlmInboundHandoffCoordinator(
                database = db,
                receiptDao = db.olmInboundReceiptDao(),
                sessionRepository = repository,
                authenticatedLocalUserId = { "local-user" },
                trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> false },
                encryptedMessageDao = db.encryptedOlmMessageDao(),
            )
            var untrustedSenderRejected = false
            try {
                untrustedCoordinator.process(
                    receiptId,
                    senderBundle,
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = "local-user",
                ) { payload -> encryptedMessage(payload) }
            } catch (expected: IllegalStateException) {
                untrustedSenderRejected = expected.message.orEmpty().contains("has not been verified")
            }
            assertTrue("Unverified senders must not advance the Olm receive state", untrustedSenderRejected)
            assertEquals(
                OlmInboundReceiptStatus.PENDING.name,
                db.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )
            assertEquals(null, db.encryptedOlmMessageDao().getMessage("local-user", directChatId, messageId))

            val rotatedIdentityCoordinator = OlmInboundHandoffCoordinator(
                database = db,
                receiptDao = db.olmInboundReceiptDao(),
                sessionRepository = repository,
                authenticatedLocalUserId = { "local-user" },
                trustVerifier = trustedDevice("alice", senderBundle),
                encryptedMessageDao = db.encryptedOlmMessageDao(),
            )
            var rotatedIdentityRejected = false
            try {
                rotatedIdentityCoordinator.process(
                    receiptId,
                    senderBundle.copy(ed25519IdentityKey = "rotated-ed25519-key"),
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = "local-user",
                ) { payload -> encryptedMessage(payload) }
            } catch (expected: IllegalStateException) {
                rotatedIdentityRejected = expected.message.orEmpty().contains("has not been verified")
            }
            assertTrue("A changed sender identity key must be re-verified", rotatedIdentityRejected)
            assertEquals(
                OlmInboundReceiptStatus.PENDING.name,
                db.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )

            var mappingFailed = false
            try {
                coordinator.process(
                    receiptId,
                    senderBundle,
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = "local-user",
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
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = "local-user",
                ) { payload ->
                    encryptedMessage(payload, conversationId = "wrong-chat")
                }
            } catch (_: IllegalArgumentException) {
                routingFailed = true
            }
            assertTrue("A mismatched chat route must be rejected", routingFailed)
            var timestampMismatchRejected = false
            try {
                coordinator.process(
                    receiptId,
                    senderBundle,
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = "local-user",
                ) { payload ->
                    encryptedMessage(payload, sentAtMillis = payload.sentAtMillis + 1L)
                }
            } catch (_: IllegalArgumentException) {
                timestampMismatchRejected = true
            }
            assertTrue("A cache timestamp must match authenticated metadata", timestampMismatchRejected)
            assertEquals(
                OlmInboundReceiptStatus.PENDING.name,
                db.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )
            assertEquals(
                null,
                db.encryptedOlmMessageDao().getMessage("local-user", directChatId, messageId),
            )

            assertEquals(
                OlmInboundHandoffResult.COMMITTED,
                coordinator.process(
                    receiptId,
                    senderBundle,
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = "local-user",
                ) { payload ->
                    encryptedMessage(payload)
                },
            )
            assertEquals(
                "retryable handoff plaintext",
                db.encryptedOlmMessageDao().getMessage("local-user", directChatId, messageId)?.text,
            )
            assertEquals(
                OlmInboundReceiptStatus.FINALIZED.name,
                db.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )
            assertEquals(
                OlmInboundHandoffResult.ALREADY_COMMITTED,
                coordinator.process(
                    receiptId,
                    senderBundle,
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = "local-user",
                ) { error("Duplicate processing must not create another message") },
            )

            // Complete the Olm handshake before expecting the sender to emit
            // a normal message. Until the initiator receives a reply, vodozemac
            // may continue emitting pre-key messages on that outbound session.
            val handshakeReply = repository.encrypt(sessionId, "inbound session reply")
            assertEquals(
                "inbound session reply",
                NativeCryptoBridge.nativeDecryptOlmMessage(
                    senderStore,
                    senderDeviceId,
                    sessionId,
                    handshakeReply,
                ),
            )

            // The established session now carries a regular Olm message.
            val normalMessageId = "handoff-normal-$suffix"
            val normalPayload = authenticatedPayload(
                normalMessageId, "alice", senderDeviceId, "local-user",
                localBundle.deviceId, 550L, "normal handoff plaintext",
            )
            val normalWire = requireNotNull(
                NativeCryptoBridge.nativeEncryptOlmMessage(
                    senderStore, senderDeviceId, sessionId,
                    OlmAuthenticatedMessagePayloadCodec.encode(normalPayload),
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
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.NORMAL,
                    localUserId = "local-user",
                ) { payload ->
                    encryptedMessage(payload)
                },
            )
            assertEquals(
                "normal handoff plaintext",
                db.encryptedOlmMessageDao().getMessage("local-user", directChatId, normalMessageId)?.text,
            )

            // A normal-message mapping failure must also retain the staged
            // plaintext so the same receipt can be retried safely.
            val retryNormalMessageId = "handoff-normal-retry-$suffix"
            val retryNormalPayload = authenticatedPayload(
                retryNormalMessageId, "alice", senderDeviceId, "local-user",
                localBundle.deviceId, 650L, "normal retry plaintext",
            )
            val retryNormalWire = requireNotNull(
                NativeCryptoBridge.nativeEncryptOlmMessage(
                    senderStore, senderDeviceId, sessionId,
                    OlmAuthenticatedMessagePayloadCodec.encode(retryNormalPayload),
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
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.NORMAL,
                    localUserId = "local-user",
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
                    remoteDeviceUserId = "alice",
                    ciphertextKind = OlmInboundCiphertextKind.NORMAL,
                    localUserId = "local-user",
                ) { payload ->
                    encryptedMessage(payload)
                },
            )
            assertEquals(
                "normal retry plaintext",
                db.encryptedOlmMessageDao().getMessage("local-user", directChatId, retryNormalMessageId)?.text,
            )
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun trustedDevice(
        userId: String,
        device: PublicDeviceKeyBundle,
    ): OlmDeviceTrustVerifier = OlmDeviceTrustVerifier { actualUserId, actualDeviceId, identityKey ->
        actualUserId == userId &&
            actualDeviceId == device.deviceId &&
            identityKey == device.ed25519IdentityKey
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

    private fun authenticatedPayload(
        messageId: String,
        senderUserId: String,
        senderDeviceId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        sentAtMillis: Long,
        text: String,
    ) = OlmAuthenticatedMessagePayload(
        messageId = messageId,
        conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId(senderUserId, recipientUserId),
        conversationPeerUserId = recipientUserId,
        senderUserId = senderUserId,
        senderDeviceId = senderDeviceId,
        recipientUserId = recipientUserId,
        recipientDeviceId = recipientDeviceId,
        sentAtMillis = sentAtMillis,
        text = text,
    )

    private fun encryptedMessage(
        payload: OlmAuthenticatedMessagePayload,
        conversationId: String = payload.conversationId,
        sentAtMillis: Long = payload.sentAtMillis,
    ) = EncryptedOlmMessageEntity(
        ownerUserId = payload.recipientUserId,
        conversationId = conversationId,
        messageId = payload.messageId,
        conversationPeerUserId = payload.conversationPeerUserId,
        senderUserId = payload.senderUserId,
        senderDeviceId = payload.senderDeviceId,
        recipientUserId = payload.recipientUserId,
        recipientDeviceId = payload.recipientDeviceId,
        sentAtMillis = sentAtMillis,
        text = payload.text,
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
