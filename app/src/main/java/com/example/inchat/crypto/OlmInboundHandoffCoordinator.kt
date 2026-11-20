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
internal enum class OlmInboundCiphertextKind {
    PRE_KEY,
    NORMAL;

    companion object {
        /**
         * vodozemac's OlmMessage::to_parts() discriminator is preserved as the
         * first wire byte by the native transport (0 = pre-key, 1 = normal).
         * The native parser validates the same discriminator before decryption.
         */
        fun fromEnvelope(envelope: EncryptedOlmEnvelope): OlmInboundCiphertextKind =
            when (envelope.ciphertext.firstOrNull()?.toInt()?.and(0xff)) {
                0 -> PRE_KEY
                1 -> NORMAL
                else -> throw IllegalArgumentException("Encrypted Olm message has an unknown wire type")
            }
    }
}

internal enum class OlmInboundHandoffResult {
    COMMITTED,
    ALREADY_COMMITTED,
}

/**
 * Coordinates the isolated two-phase inbound handoff.
 *
 * This class is intentionally not wired to Firebase listeners, chat UI, message
 * previews, notifications, or the existing plaintext messaging path. The
 * authenticated version-2 payload is validated before the caller maps it into
 * the local cache entity; transport metadata is never used as a substitute.
 */
internal class OlmInboundHandoffCoordinator(
    database: RoomDatabase,
    private val receiptDao: OlmInboundReceiptDao,
    private val sessionRepository: OlmSessionRepository,
    private val authenticatedLocalUserId: () -> String,
    private val trustVerifier: OlmDeviceTrustVerifier,
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
            require(localUserId == authenticatedLocalUserId()) {
                "Inbound recipient account does not match the authenticated local account"
            }
            require(receipt.recipientDeviceId == sessionRepository.localDeviceId) {
                "Inbound receipt is addressed to a different local device"
            }
            require(remoteDeviceUserId == receipt.senderUserId) {
                "Verified remote device account does not match the inbound receipt sender"
            }
            require(remoteDevice.deviceId == receipt.senderDeviceId) {
                "Verified remote device does not match the inbound receipt sender"
            }

            if (receipt.status == OlmInboundReceiptStatus.PROCESSED.name) {
                check(finalizeCommittedReceipt(receiptId, receipt.status)) {
                    "Could not finalize previously committed inbound plaintext"
                }
                return@withContext OlmInboundHandoffResult.ALREADY_COMMITTED
            }
            if (receipt.status == OlmInboundReceiptStatus.FINALIZED.name) {
                return@withContext OlmInboundHandoffResult.ALREADY_COMMITTED
            }
            check(receipt.status == OlmInboundReceiptStatus.PENDING.name) {
                "Inbound receipt has an unsupported status"
            }
            // Do not advance an Olm receive ratchet, consume a one-time prekey,
            // or stage plaintext for a directory identity that has not been
            // explicitly SAS-verified on this installation.
            check(
                trustVerifier.isVerified(
                    peerUserId = remoteDeviceUserId,
                    peerDeviceId = remoteDevice.deviceId,
                    peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
                )
            ) {
                "Remote device has not been verified on this installation"
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
            require(message.chatId == payload.conversationId) {
                "Cached message chat does not match the authenticated conversation"
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
            check(finalizeCommittedReceipt(receiptId, OlmInboundReceiptStatus.PROCESSED.name)) {
                "Message committed, but native plaintext staging could not be finalized; retry safely"
            }
            OlmInboundHandoffResult.COMMITTED
        }
    }

    /**
     * Recover the narrow crash window after the Room transaction marked a receipt
     * PROCESSED but before native staged plaintext was finalized. Native finalize
     * is idempotent, so retrying after either side commits is safe.
     */
    suspend fun finalizePreviouslyCommitted(
        receiptId: String,
        localUserId: String,
    ): Boolean = processingMutex.withLock {
        withContext(ioDispatcher) {
            require(RECEIPT_ID_PATTERN.matches(receiptId)) {
                "Inbound receipt ID must be a SHA-256 hex digest"
            }
            require(localUserId == authenticatedLocalUserId()) {
                "Inbound recipient account does not match the authenticated local account"
            }
            val receipt = requireNotNull(receiptDao.getByReceiptId(receiptId)) {
                "Inbound receipt does not exist"
            }
            require(receipt.recipientDeviceId == sessionRepository.localDeviceId) {
                "Inbound receipt is addressed to a different local device"
            }
            finalizeCommittedReceipt(receiptId, receipt.status)
        }
    }

    private fun finalizeCommittedReceipt(receiptId: String, status: String): Boolean {
        if (status == OlmInboundReceiptStatus.FINALIZED.name) return true
        check(status == OlmInboundReceiptStatus.PROCESSED.name) {
            "Only a committed inbound receipt can be finalized"
        }
        check(sessionRepository.finalizeInboundDecryption(receiptId)) {
            "Could not finalize committed inbound plaintext"
        }
        val marked = receiptDao.markFinalized(receiptId)
        check(marked == 1 || receiptDao.getByReceiptId(receiptId)?.status == OlmInboundReceiptStatus.FINALIZED.name) {
            "Native plaintext was finalized, but the durable receipt status was not updated"
        }
        return true
    }

    private companion object {
        val RECEIPT_ID_PATTERN = Regex("[A-Fa-f0-9]{64}")
    }
}
