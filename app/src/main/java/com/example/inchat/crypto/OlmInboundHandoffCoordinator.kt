package com.example.inchat.crypto

import androidx.room.RoomDatabase
import com.example.inchat.data.local.CachedMessageEntity
import com.example.inchat.data.local.LocalChatDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Identifies which Olm wire-message variant is expected for this receipt. */
internal enum class OlmInboundCiphertextKind { PRE_KEY, NORMAL }

internal enum class OlmInboundHandoffResult {
    COMMITTED,
    ALREADY_COMMITTED,
}

/**
 * Coordinates the isolated two-phase inbound handoff.
 *
 * This class is intentionally not wired to Firebase listeners, chat UI, message
 * previews, notifications, or the existing plaintext messaging path. The
 * authenticated version-1 payload is validated before the caller maps it into
 * the local cache entity; transport metadata is never used as a substitute.
 */
internal class OlmInboundHandoffCoordinator(
    database: RoomDatabase,
    private val receiptDao: OlmInboundReceiptDao,
    private val sessionRepository: OlmSessionRepository,
    messageDao: LocalChatDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val committer = OlmInboundMessageCommitter(database, messageDao, receiptDao)
    private val processingMutex = Mutex()

    /**
     * Prepare/recover plaintext, atomically commit it with the receipt, then
     * finalize native staging. The factory must return a non-pending message
     * whose text is exactly the decrypted plaintext and whose sender/message
     * IDs match the durable receipt.
     */
    suspend fun process(
        receiptId: String,
        remoteDevice: PublicDeviceKeyBundle,
        remoteDeviceUserId: String,
        ciphertextKind: OlmInboundCiphertextKind,
        localUserId: String,
        messageFactory: (payload: OlmAuthenticatedMessagePayload) -> CachedMessageEntity,
    ): OlmInboundHandoffResult = processingMutex.withLock {
        withContext(ioDispatcher) {
            require(RECEIPT_ID_PATTERN.matches(receiptId)) {
                "Inbound receipt ID must be a SHA-256 hex digest"
            }
            val receipt = requireNotNull(receiptDao.getByReceiptId(receiptId)) {
                "Inbound receipt does not exist"
            }
            require(remoteDeviceUserId == receipt.senderUserId) {
                "Verified remote device account does not match the inbound receipt sender"
            }
            require(remoteDevice.deviceId == receipt.senderDeviceId) {
                "Verified remote device does not match the inbound receipt sender"
            }

            if (receipt.status == OlmInboundReceiptStatus.PROCESSED.name) {
                check(sessionRepository.finalizeInboundDecryption(receiptId)) {
                    "Could not finalize previously committed inbound plaintext"
                }
                return@withContext OlmInboundHandoffResult.ALREADY_COMMITTED
            }
            check(receipt.status == OlmInboundReceiptStatus.PENDING.name) {
                "Inbound receipt has an unsupported status"
            }

            val envelope = EncryptedOlmEnvelopeCodec.decode(receipt.envelope)
            require(envelope.senderDeviceId == receipt.senderDeviceId) {
                "Stored envelope sender does not match its receipt"
            }
            require(envelope.recipientDeviceId == receipt.recipientDeviceId) {
                "Stored envelope recipient does not match its receipt"
            }

            val staged = when (ciphertextKind) {
                OlmInboundCiphertextKind.PRE_KEY ->
                    sessionRepository.prepareInboundSessionFromEnvelope(
                        receiptId = receiptId,
                        remoteDevice = remoteDevice,
                        encodedEnvelope = receipt.envelope,
                    )
                OlmInboundCiphertextKind.NORMAL ->
                    sessionRepository.prepareDecryption(
                        receiptId = receiptId,
                        expectedSenderDeviceId = remoteDevice.deviceId,
                        encodedEnvelope = receipt.envelope,
                    )
            }

            val payload = OlmAuthenticatedMessagePayloadCodec.decodeAndValidate(
                plaintext = staged.plaintext,
                expectedMessageId = receipt.messageId,
                expectedSenderUserId = receipt.senderUserId,
                expectedSenderDeviceId = receipt.senderDeviceId,
                expectedRecipientUserId = localUserId,
                expectedRecipientDeviceId = receipt.recipientDeviceId,
            )
            val message = messageFactory(payload)
            require(message.ownerUserId == localUserId) {
                "Cached message owner does not match the authenticated recipient"
            }
            require(message.text == payload.text) {
                "Cached message text must exactly match the authenticated payload"
            }
            require(message.messageId == payload.messageId) {
                "Cached message ID does not match the authenticated payload"
            }
            require(message.senderId == payload.senderUserId) {
                "Cached message sender does not match the authenticated payload"
            }
            require(message.chatId == payload.senderUserId) {
                "Cached message chat does not match the authenticated sender"
            }
            require(message.timestamp == payload.sentAtMillis) {
                "Cached message timestamp does not match the authenticated payload"
            }
            require(!message.isPending) {
                "Decrypted inbound messages cannot be pending sends"
            }

            check(committer.commit(receiptId, message, clock().coerceAtLeast(0L))) {
                "Inbound message and receipt were not committed"
            }
            check(sessionRepository.finalizeInboundDecryption(receiptId)) {
                "Message committed, but native plaintext staging could not be finalized; retry safely"
            }
            OlmInboundHandoffResult.COMMITTED
        }
    }

    private companion object {
        val RECEIPT_ID_PATTERN = Regex("[A-Fa-f0-9]{64}")
    }
}
