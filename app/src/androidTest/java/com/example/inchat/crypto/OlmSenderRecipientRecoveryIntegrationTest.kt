package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.CachedMessageEntity
import com.example.inchat.data.local.LocalChatDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * In-process two-device integration coverage.
 *
 * Each endpoint has its own Room database and native Olm identity. Only the
 * network boundary is replaced by a shared in-memory relay; production queue,
 * transport, inbox receiver, receipt ledger, native decrypt, and Room handoff
 * code are exercised unchanged.
 */
class OlmSenderRecipientRecoveryIntegrationTest {
    @Test
    fun senderToRecipientRecoverySurvivesLostCompletionsAndDeviceRestarts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val senderUserId = "alice"
        val recipientUserId = "bob"
        val senderDeviceId = "e2e-sender-" + suffix
        val messageId = "e2e-message-" + suffix
        val clearText = "end-to-end recovery plaintext"

        // This instrumentation installation is the recipient device. The sender
        // uses a separate native store and a different device ID.
        val recipientBundle = DeviceIdentityRepository(context).ensurePublicKeyBundle()
        val senderStore = CryptoStateStore(context, "e2e-sender-" + suffix)
        val senderIdentity = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(senderStore, senderDeviceId),
            ),
        )
        val senderBundle = parseBundle(senderDeviceId, senderIdentity)
        val recipientPreKey = requireNotNull(recipientBundle.oneTimeKeys.firstOrNull()) {
            "The recipient device must publish a one-time prekey for this test"
        }
        val sessionId = requireNotNull(
            NativeCryptoBridge.nativeCreateOutboundOlmSession(
                senderStore,
                senderDeviceId,
                recipientBundle.curve25519IdentityKey,
                recipientPreKey.publicKey,
            ),
        )
        val payload = OlmAuthenticatedMessagePayload(
            messageId = messageId,
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            recipientUserId = recipientUserId,
            recipientDeviceId = recipientBundle.deviceId,
            sentAtMillis = 1_700_000_000_000L,
            text = clearText,
        )
        val wireCiphertext = requireNotNull(
            NativeCryptoBridge.nativeEncryptOlmMessage(
                senderStore,
                senderDeviceId,
                sessionId,
                OlmAuthenticatedMessagePayloadCodec.encode(payload),
            ),
        )
        val envelope = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientBundle.deviceId,
                sessionId = sessionId,
                ciphertext = wireCiphertext,
            ),
        )
        val prepared = PreparedOlmDelivery(
            recipientUserId = recipientUserId,
            recipientDeviceId = recipientBundle.deviceId,
            sessionId = sessionId,
            envelope = envelope,
        )

        val senderDatabaseName = "olm-e2e-sender-" + suffix + ".db"
        val recipientDatabaseName = "olm-e2e-recipient-" + suffix + ".db"
        context.deleteDatabase(senderDatabaseName)
        context.deleteDatabase(recipientDatabaseName)
        var senderDatabase = openDatabase(context, senderDatabaseName)
        var recipientDatabase = openDatabase(context, recipientDatabaseName)

        try {
            val senderQueue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(senderDatabase, senderDatabase.olmDeliveryQueueDao()),
            )
            var scheduleCalls = 0
            val outboundCoordinator = OlmOutboundDeliveryCoordinator(
                preparer = OlmFirstMessagePreparer { actualPayload ->
                    assertEquals(payload, actualPayload)
                    prepared
                },
                queue = senderQueue,
                trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
                scheduleDelivery = { ownerUserId, scheduledDeviceId ->
                    assertEquals(senderUserId, ownerUserId)
                    assertEquals(senderDeviceId, scheduledDeviceId)
                    scheduleCalls++
                },
            )
            assertEquals(
                1,
                outboundCoordinator.enqueueDirectTextMessage(
                    senderUserId = senderUserId,
                    senderDeviceId = senderDeviceId,
                    recipientUserId = recipientUserId,
                    messageId = messageId,
                    sentAtMillis = payload.sentAtMillis,
                    text = clearText,
                    recipientDevices = listOf(recipientBundle),
                    senderDevices = listOf(senderBundle),
                ),
            )
            assertEquals(1, scheduleCalls)

            val deliveryId = sha256(
                senderUserId + "\u0000" + senderDeviceId + "\u0000" + messageId +
                    "\u0000" + recipientUserId + "\u0000" + recipientBundle.deviceId,
            )
            val relay = InMemoryOlmRelayStore(
                failAfterInboxPutOnce = true,
                failAfterAcknowledgementPutOnce = true,
            )
            var authenticatedUserId = senderUserId
            val trustVerifier = OlmDeviceTrustVerifier { userId, deviceId, identityKey ->
                (userId == senderUserId &&
                    deviceId == senderBundle.deviceId &&
                    identityKey == senderBundle.ed25519IdentityKey) ||
                    (userId == recipientUserId &&
                        deviceId == recipientBundle.deviceId &&
                        identityKey == recipientBundle.ed25519IdentityKey)
            }
            val transport = FirebaseOlmEnvelopeTransport(
                trustVerifier = trustVerifier,
                relayStore = relay,
                currentAuthenticatedUserId = { authenticatedUserId },
                readPublishedDevices = { userId ->
                    if (userId == recipientUserId) listOf(recipientBundle) else emptyList()
                },
            )

            // The first upload is committed by the relay, but its completion is
            // lost. The sender persists backoff and retries the exact same ID and
            // ciphertext; the relay must retain one immutable inbox record.
            val firstSend = OlmDeliveryWorkerRunner(
                queue = senderQueue,
                transport = transport,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                clock = { 1_000L },
            ).run()
            assertEquals(0, firstSend.uploaded)
            assertTrue(firstSend.retryRequired)
            assertEquals(1, relay.inboxCount())
            assertEquals(
                OlmDeliveryStatus.QUEUED.name,
                senderDatabase.olmDeliveryQueueDao().getByDeliveryId(deliveryId)?.status,
            )

            val retriedSend = OlmDeliveryWorkerRunner(
                queue = senderQueue,
                transport = transport,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                clock = { 3_000L },
            ).run()
            assertEquals(1, retriedSend.uploaded)
            assertEquals(2, relay.inboxPutCalls)
            assertEquals(1, relay.inboxCount())
            assertFalse(relay.inboxValues().toString().contains(clearText))
            var changedCiphertextRetryRejected = false
            try {
                relay.putInbox(
                    recipientUid = recipientUserId,
                    recipientDeviceId = recipientBundle.deviceId,
                    deliveryId = deliveryId,
                    fields = mapOf(
                        "senderUid" to senderUserId,
                        "senderDeviceId" to senderDeviceId,
                        "messageId" to messageId,
                        "envelope" to envelope + "-changed",
                    ),
                )
            } catch (_: IllegalStateException) {
                changedCiphertextRetryRejected = true
            }
            assertTrue(changedCiphertextRetryRejected)
            assertEquals(1, relay.inboxCount())
            assertEquals(
                OlmDeliveryStatus.UPLOADED.name,
                senderDatabase.olmDeliveryQueueDao().getByDeliveryId(deliveryId)?.status,
            )

            // The sender process stops after relay acceptance. Reopening its Room
            // database must preserve the same pending ciphertext and retry state.
            senderDatabase.close()
            senderDatabase = openDatabase(context, senderDatabaseName)
            val reopenedSenderRow = senderDatabase.olmDeliveryQueueDao().getByDeliveryId(deliveryId)
            assertEquals(OlmDeliveryStatus.UPLOADED.name, reopenedSenderRow?.status)
            assertEquals(envelope, reopenedSenderRow?.envelope)
            val reopenedSenderQueue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(senderDatabase, senderDatabase.olmDeliveryQueueDao()),
            )

            // The recipient stores the ciphertext receipt first. The relay commits
            // its acknowledgement but loses the write completion, so inbox removal
            // is intentionally not reached on this pass.
            authenticatedUserId = recipientUserId
            val firstRecipientReceiver = FirebaseOlmInboxReceiver(
                transport = transport,
                ledger = OlmInboundDeliveryLedger(
                    recipientDatabase.olmInboundReceiptDao(),
                    recipientBundle.deviceId,
                ),
                deviceId = recipientBundle.deviceId,
                clock = { 4_000L },
            )
            var lostAcknowledgementCompletion = false
            try {
                firstRecipientReceiver.receivePending()
            } catch (expected: IllegalStateException) {
                lostAcknowledgementCompletion =
                    expected.message == "simulated lost acknowledgement completion"
            }
            assertTrue(lostAcknowledgementCompletion)
            assertEquals(1, relay.inboxCount())
            assertEquals(1, relay.acknowledgementCount())
            val receiptId = sha256(
                senderUserId + "\u0000" + senderDeviceId + "\u0000" + messageId +
                    "\u0000" + recipientBundle.deviceId,
            )
            assertEquals(
                OlmInboundReceiptStatus.PENDING.name,
                recipientDatabase.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )

            // Recreate the recipient's local database and retry. The existing
            // PENDING receipt is reused, the acknowledgement is retried, and only
            // then is the relay inbox copy removed.
            recipientDatabase.close()
            recipientDatabase = openDatabase(context, recipientDatabaseName)
            assertEquals(
                OlmInboundReceiptStatus.PENDING.name,
                recipientDatabase.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )
            val recipientLedger = OlmInboundDeliveryLedger(
                recipientDatabase.olmInboundReceiptDao(),
                recipientBundle.deviceId,
            )
            val recoveredReceive = FirebaseOlmInboxReceiver(
                transport = transport,
                ledger = recipientLedger,
                deviceId = recipientBundle.deviceId,
                clock = { 5_000L },
            ).receivePending()
            assertEquals(1, recoveredReceive.alreadyPending)
            assertEquals(0, recoveredReceive.stored)
            assertEquals(0, recoveredReceive.conflicts)
            assertEquals(0, relay.inboxCount())
            assertEquals(1, relay.acknowledgementCount())

            // Continue through authenticated native decryption and the existing
            // atomic Room message+receipt handoff.
            val handoff = OlmInboundHandoffCoordinator(
                database = recipientDatabase,
                receiptDao = recipientDatabase.olmInboundReceiptDao(),
                sessionRepository = OlmSessionRepository(context),
                authenticatedLocalUserId = { recipientUserId },
                trustVerifier = trustVerifier,
                messageDao = recipientDatabase.messageDao(),
                clock = { 6_000L },
            )
            assertEquals(
                OlmInboundHandoffResult.COMMITTED,
                handoff.process(
                    receiptId = receiptId,
                    remoteDevice = senderBundle,
                    remoteDeviceUserId = senderUserId,
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = recipientUserId,
                ) { authenticatedPayload ->
                    cachedMessage(authenticatedPayload)
                },
            )
            assertEquals(
                clearText,
                recipientDatabase.messageDao()
                    .getMessage(recipientUserId, senderUserId, messageId)?.text,
            )
            assertEquals(
                OlmInboundReceiptStatus.PROCESSED.name,
                recipientDatabase.olmInboundReceiptDao().getByReceiptId(receiptId)?.status,
            )
            assertEquals(
                OlmInboundHandoffResult.ALREADY_COMMITTED,
                handoff.process(
                    receiptId = receiptId,
                    remoteDevice = senderBundle,
                    remoteDeviceUserId = senderUserId,
                    ciphertextKind = OlmInboundCiphertextKind.PRE_KEY,
                    localUserId = recipientUserId,
                ) {
                    error("A committed message must not be decrypted or inserted again")
                },
            )

            // Model a late duplicate relay delivery after the recipient has already
            // committed the plaintext. Its PROCESSED receipt must only re-ack/delete.
            relay.putInbox(
                recipientUid = recipientUserId,
                recipientDeviceId = recipientBundle.deviceId,
                deliveryId = deliveryId,
                fields = mapOf(
                    "senderUid" to senderUserId,
                    "senderDeviceId" to senderDeviceId,
                    "messageId" to messageId,
                    "envelope" to envelope,
                ),
            )
            val duplicateReceive = FirebaseOlmInboxReceiver(
                transport = transport,
                ledger = recipientLedger,
                deviceId = recipientBundle.deviceId,
                clock = { 7_000L },
            ).receivePending()
            assertEquals(1, duplicateReceive.alreadyProcessed)
            assertEquals(0, relay.inboxCount())
            assertEquals(1, relay.acknowledgementCount())

            // The sender consumes the exact recipient acknowledgement, records its
            // metadata in Room, and then removes its ciphertext outbox row.
            authenticatedUserId = senderUserId
            val acknowledgementRecorder = RoomOlmDeliveryAcknowledgementRecorder(
                database = senderDatabase,
                dao = senderDatabase.olmDeliveryReceiptDao(),
            )
            val finalSend = OlmDeliveryWorkerRunner(
                queue = reopenedSenderQueue,
                transport = transport,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                clock = { 8_000L },
                recordAcknowledgement = acknowledgementRecorder::record,
            ).run()
            assertEquals(1, finalSend.acknowledged)
            assertEquals(0, finalSend.uploaded)
            assertNull(senderDatabase.olmDeliveryQueueDao().getByDeliveryId(deliveryId))
            assertEquals(
                listOf(messageId),
                senderDatabase.olmDeliveryReceiptDao().getDeliveredMessageIds(
                    senderUserId,
                    senderDeviceId,
                    recipientUserId,
                ),
            )
            assertEquals(0, relay.inboxCount())
            assertEquals(0, relay.acknowledgementCount())
            assertEquals(3, relay.acknowledgementPutCalls)
        } finally {
            senderDatabase.close()
            recipientDatabase.close()
            context.deleteDatabase(senderDatabaseName)
            context.deleteDatabase(recipientDatabaseName)
        }
    }

    private fun openDatabase(context: Context, name: String): LocalChatDatabase =
        Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun parseBundle(deviceId: String, json: JSONObject): PublicDeviceKeyBundle {
        val identity = json.getJSONObject("identityKeys")
        fun parsePreKey(value: JSONObject) = SignedOlmPreKey(
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
            fallbackKey = parsePreKey(json.getJSONObject("fallbackKey")),
            oneTimeKeys = (0 until oneTime.length()).map { parsePreKey(oneTime.getJSONObject(it)) },
        )
    }

    private fun cachedMessage(payload: OlmAuthenticatedMessagePayload) = CachedMessageEntity(
        ownerUserId = payload.recipientUserId,
        chatId = payload.senderUserId,
        messageId = payload.messageId,
        senderId = payload.senderUserId,
        senderNickname = "Alice",
        text = payload.text,
        timestamp = payload.sentAtMillis,
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
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class InMemoryOlmRelayStore(
        failAfterInboxPutOnce: Boolean,
        failAfterAcknowledgementPutOnce: Boolean,
    ) : OlmRelayStore {
        private val inbox = linkedMapOf<Triple<String, String, String>, Map<String, Any?>>()
        private val acknowledgements = linkedMapOf<Triple<String, String, String>, Map<String, Any?>>()
        private var failInboxCompletion = failAfterInboxPutOnce
        private var failAcknowledgementCompletion = failAfterAcknowledgementPutOnce
        var inboxPutCalls = 0
            private set
        var acknowledgementPutCalls = 0
            private set

        override suspend fun putInbox(
            recipientUid: String,
            recipientDeviceId: String,
            deliveryId: String,
            fields: Map<String, Any?>,
        ) {
            val key = Triple(recipientUid, recipientDeviceId, deliveryId)
            val existing = inbox[key]
            if (existing == null) {
                inbox[key] = fields.toMap()
            } else {
                check(existing == fields) {
                    "A retry cannot replace the immutable ciphertext for an existing delivery ID"
                }
            }
            inboxPutCalls++
            if (failInboxCompletion) {
                failInboxCompletion = false
                throw IllegalStateException("simulated lost inbox upload completion")
            }
        }

        override suspend fun readInbox(
            recipientUid: String,
            recipientDeviceId: String,
        ): List<FirebaseOlmRelayRecord> =
            inbox.entries
                .filter { it.key.first == recipientUid && it.key.second == recipientDeviceId }
                .map { (key, fields) -> FirebaseOlmRelayRecord(key.third, fields) }

        override suspend fun removeInbox(
            recipientUid: String,
            recipientDeviceId: String,
            deliveryId: String,
        ) {
            inbox.remove(Triple(recipientUid, recipientDeviceId, deliveryId))
        }

        override suspend fun putAcknowledgement(
            senderUid: String,
            senderDeviceId: String,
            deliveryId: String,
            fields: Map<String, Any?>,
        ) {
            val normalized = fields.toMutableMap().apply {
                // The Firebase adapter uses ServerValue.TIMESTAMP. The fake relay
                // resolves it to a server number accepted by the production parser.
                this["acknowledgedAt"] = 1_700_000_001_000L
            }.toMap()
            val key = Triple(senderUid, senderDeviceId, deliveryId)
            val existing = acknowledgements[key]
            if (existing == null) {
                acknowledgements[key] = normalized
            } else {
                check(existing == normalized) {
                    "A repeated acknowledgement must keep the same immutable metadata"
                }
            }
            acknowledgementPutCalls++
            if (failAcknowledgementCompletion) {
                failAcknowledgementCompletion = false
                throw IllegalStateException("simulated lost acknowledgement completion")
            }
        }

        override suspend fun readAcknowledgements(
            senderUid: String,
            senderDeviceId: String,
        ): List<FirebaseOlmRelayRecord> =
            acknowledgements.entries
                .filter { it.key.first == senderUid && it.key.second == senderDeviceId }
                .map { (key, fields) -> FirebaseOlmRelayRecord(key.third, fields) }

        override suspend fun removeAcknowledgement(
            senderUid: String,
            senderDeviceId: String,
            deliveryId: String,
        ) {
            acknowledgements.remove(Triple(senderUid, senderDeviceId, deliveryId))
        }

        fun inboxCount(): Int = inbox.size
        fun acknowledgementCount(): Int = acknowledgements.size
        fun inboxValues(): List<Map<String, Any?>> = inbox.values.toList()
    }
}
