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
 * previews, notifications, or the existing plaintext messaging path. The caller
 * supplies a message factory because sender-visible metadata must be validated
 * and mapped by the eventual protocol layer, not guessed from transport fields.
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
        ciphertextKind: OlmInboundCiphertextKind,
        messageFactory: (plaintext: String) -> CachedMessageEntity,
    ): OlmInboundHandoffResult = processingMutex.withLock {
        withContext(ioDispatcher) {
            require(RECEIPT_ID_PATTERN.matches(receiptId)) {
                "Inbound receipt ID must be a SHA-256 hex digest"
            }
            val receipt = requireNotNull(receiptDao.getByReceiptId(receiptId)) {
                "Inbound receipt does not exist"
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

            val message = messageFactory(staged.plaintext)
            require(message.text == staged.plaintext) {
                "Cached message text must exactly match the staged plaintext"
            }
            require(message.messageId == receipt.messageId) {
                "Cached message ID does not match its inbound receipt"
            }
            require(message.senderId == receipt.senderUserId) {
                "Cached message sender does not match its inbound receipt"
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
