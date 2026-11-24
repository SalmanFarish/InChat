package com.example.inchat.crypto

import androidx.room.RoomDatabase
import org.json.JSONException
import org.json.JSONObject
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
        fun fromEnvelope(envelope: EncryptedOlmEnvelope): OlmInboundCiphertextKind {
            require(envelope.ciphertext.size >= 2) {
                "Encrypted Olm message is truncated"
            }
            return when (envelope.ciphertext.first().toInt() and 0xff) {
                0 -> PRE_KEY
                1 -> NORMAL
                else -> throw IllegalArgumentException("Encrypted Olm message has an unknown wire type")
            }
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
 * This class is intentionally not wired to Firebase plaintext listeners, the
 * existing cached_messages table, chat UI, previews, or notifications. The
 * authenticated version-2 payload is validated before mapping into the separate
 * encrypted_olm_messages table; transport metadata is never used as a substitute.
 */
internal class OlmInboundHandoffCoordinator(
    database: RoomDatabase,
    private val receiptDao: OlmInboundReceiptDao,
    private val sessionRepository: OlmSessionRepository,
    private val authenticatedLocalUserId: () -> String,
    private val trustVerifier: OlmDeviceTrustVerifier,
    encryptedMessageDao: EncryptedOlmMessageDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val groupMessageHandoff: OlmInboundGroupMessageHandoff? = null,
    private val groupSessionKeyHandoff: OlmInboundGroupSessionKeyHandoff? = null,
    private val megolmSessionRepository: MegolmSessionRepository? = null,
    private val onDirectMessageCommitted: (DecryptedOlmMessage) -> Unit = {},
) {
    private val committer = OlmInboundMessageCommitter(database, encryptedMessageDao, receiptDao)
    private val processingMutex = Mutex()

    /**
     * Prepare/recover plaintext, encrypt its text with the device Keystore key,
     * atomically commit ciphertext with the receipt, then finalize native staging.
     * The factory must return metadata that exactly matches the authenticated payload.
     */
    suspend fun process(
        receiptId: String,
        remoteDevice: PublicDeviceKeyBundle,
        remoteDeviceUserId: String,
        ciphertextKind: OlmInboundCiphertextKind,
        localUserId: String,
        messageFactory: (payload: OlmAuthenticatedMessagePayload) -> DecryptedOlmMessage,
    ): OlmInboundHandoffResult = processingMutex.withLock {
        withContext(ioDispatcher) {
            require(RECEIPT_ID_PATTERN.matches(receiptId)) {
                "Inbound receipt ID must be a SHA-256 hex digest"
            }
            val receipt = requireNotNull(receiptDao.getByReceiptId(receiptId)) {
                "Inbound receipt does not exist"
            }
            validateOlmInboundReceiptIntegrity(receipt)
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
                check(finalizeCommittedReceipt(receiptId, receipt.status, localUserId)) {
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
            // or stage plaintext for a directory identity whose first-seen pin has changed.
            check(
                trustVerifier.isTrustedForMessaging(
                    peerUserId = remoteDeviceUserId,
                    peerDeviceId = remoteDevice.deviceId,
                    peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
                )
            ) {
                "Remote device identity changed. Compare safety numbers before accepting messages"
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

            // Authentication can also change while native staging suspends. Never
            // commit a result under an account other than the one that started it.
            check(localUserId == authenticatedLocalUserId()) {
                "Authenticated account changed during native decryption"
            }

            // Native staging can suspend while the pinned identity changes elsewhere in the app.
            // Recheck before routing or committing either direct or group payloads.
            check(
                trustVerifier.isTrustedForMessaging(
                    peerUserId = remoteDeviceUserId,
                    peerDeviceId = remoteDevice.deviceId,
                    peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
                )
            ) {
                "Remote device identity changed during native decryption"
            }

            // The outer Olm envelope and pinned sender device identity are already
            // authenticated. Route the typed group payload only after this point,
            // before attempting the strict direct-message schema decoder.
            if (isGroupSessionKeyPayload(staged.plaintext)) {
                val keyHandoff = requireNotNull(groupSessionKeyHandoff) {
                    "Inbound Megolm key-distribution handling is not configured"
                }
                check(megolmSessionRepository != null) {
                    "Inbound Megolm session repository is not configured"
                }
                val imported = keyHandoff.process(
                    receipt = receipt,
                    remoteDevice = remoteDevice,
                    remoteDeviceUserId = remoteDeviceUserId,
                    localUserId = localUserId,
                    authenticatedOlmPlaintext = staged.plaintext,
                )
                check(localUserId == authenticatedLocalUserId()) {
                    "Authenticated account changed during inbound Megolm key import"
                }
                markOuterReceiptProcessed(receiptId)
                check(finalizeCommittedReceipt(receiptId, OlmInboundReceiptStatus.PROCESSED.name, localUserId)) {
                    "Megolm key imported, but its authenticated Olm receipt could not be finalized"
                }
                return@withContext if (imported) {
                    OlmInboundHandoffResult.COMMITTED
                } else {
                    OlmInboundHandoffResult.ALREADY_COMMITTED
                }
            }

            if (isGroupMessagePayload(staged.plaintext)) {
                val groupHandoff = requireNotNull(groupMessageHandoff) {
                    "Inbound Megolm group-message handling is not configured"
                }
                check(megolmSessionRepository != null) {
                    "Inbound Megolm session finalizer is not configured"
                }
                val inserted = groupHandoff.process(
                    receipt = receipt,
                    remoteDevice = remoteDevice,
                    remoteDeviceUserId = remoteDeviceUserId,
                    localUserId = localUserId,
                    authenticatedOlmPlaintext = staged.plaintext,
                )
                check(localUserId == authenticatedLocalUserId()) {
                    "Authenticated account changed during inbound group-message commit"
                }
                markOuterReceiptProcessed(receiptId)
                check(finalizeCommittedReceipt(receiptId, OlmInboundReceiptStatus.PROCESSED.name, localUserId)) {
                    "Group message committed, but native receive stages could not be finalized; retry safely"
                }
                return@withContext if (inserted) {
                    OlmInboundHandoffResult.COMMITTED
                } else {
                    OlmInboundHandoffResult.ALREADY_COMMITTED
                }
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
            require(message.ownerUserId == localUserId && message.recipientUserId == localUserId) {
                "Encrypted message owner/recipient does not match the authenticated local account"
            }
            require(message.text == payload.text) {
                "Encrypted message text must exactly match the authenticated payload"
            }
            require(message.messageId == payload.messageId) {
                "Encrypted message ID does not match the authenticated payload"
            }
            require(message.senderUserId == payload.senderUserId) {
                "Encrypted message sender does not match the authenticated payload"
            }
            require(message.senderDeviceId == payload.senderDeviceId) {
                "Encrypted message sender device does not match the authenticated payload"
            }
            require(message.recipientDeviceId == payload.recipientDeviceId) {
                "Encrypted message recipient device does not match the authenticated payload"
            }
            require(message.conversationId == payload.conversationId) {
                "Encrypted message conversation does not match the authenticated payload"
            }
            require(message.conversationPeerUserId == payload.conversationPeerUserId) {
                "Encrypted message conversation peer does not match the authenticated payload"
            }
            require(message.sentAtMillis == payload.sentAtMillis) {
                "Encrypted message timestamp does not match the authenticated payload"
            }

            // Keep the direct-message commit boundary fenced as well: Keystore
            // encryption and Room work must not proceed on stale sender trust.
            check(localUserId == authenticatedLocalUserId()) {
                "Authenticated account changed before inbound message commit"
            }
            check(
                trustVerifier.isTrustedForMessaging(
                    peerUserId = remoteDeviceUserId,
                    peerDeviceId = remoteDevice.deviceId,
                    peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
                )
            ) {
                "Remote device identity changed before inbound message commit"
            }

            check(committer.commit(receiptId, message, clock().coerceAtLeast(0L))) {
                "Inbound message and receipt were not committed"
            }
            runCatching { onDirectMessageCommitted(message) }
            check(localUserId == authenticatedLocalUserId()) {
                "Authenticated account changed during inbound message commit"
            }
            check(finalizeCommittedReceipt(receiptId, OlmInboundReceiptStatus.PROCESSED.name, localUserId)) {
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
            validateOlmInboundReceiptIntegrity(receipt)
            require(receipt.recipientDeviceId == sessionRepository.localDeviceId) {
                "Inbound receipt is addressed to a different local device"
            }
            finalizeCommittedReceipt(receiptId, receipt.status, localUserId)
        }
    }

    private fun finalizeCommittedReceipt(
        receiptId: String,
        status: String,
        localUserId: String,
    ): Boolean {
        if (status == OlmInboundReceiptStatus.FINALIZED.name) return true
        check(status == OlmInboundReceiptStatus.PROCESSED.name) {
            "Only a committed inbound receipt can be finalized"
        }
        // Native finalization is idempotent. If auth changes during this
        // sequence, leave the Room receipt PROCESSED so the correct account
        // scope can safely finish it on the next pass.
        check(localUserId == authenticatedLocalUserId()) {
            "Authenticated account changed before inbound native finalization"
        }
        // The group native stage is independent of the Olm stage. Remove it
        // first, only after the group ciphertext/replay transaction and outer
        // PROCESSED receipt have committed. The native operation is idempotent
        // and returns success when no group stage exists (direct-message case).
        megolmSessionRepository?.let { repository ->
            check(repository.finalizeInboundDecryption(receiptId)) {
                "Could not finalize committed Megolm plaintext"
            }
        }
        check(localUserId == authenticatedLocalUserId()) {
            "Authenticated account changed during Megolm finalization"
        }
        check(sessionRepository.finalizeInboundDecryption(receiptId)) {
            "Could not finalize committed inbound plaintext"
        }
        check(localUserId == authenticatedLocalUserId()) {
            "Authenticated account changed during Olm finalization"
        }
        val marked = receiptDao.markFinalized(receiptId)
        check(marked == 1 || receiptDao.getByReceiptId(receiptId)?.status == OlmInboundReceiptStatus.FINALIZED.name) {
            "Native plaintext was finalized, but the durable receipt status was not updated"
        }
        return true
    }

    private fun markOuterReceiptProcessed(receiptId: String) {
        val marked = receiptDao.markProcessed(
            receiptId,
            clock().coerceAtLeast(0L),
        )
        check(
            marked == 1 ||
                receiptDao.getByReceiptId(receiptId)?.status in setOf(
                    OlmInboundReceiptStatus.PROCESSED.name,
                    OlmInboundReceiptStatus.FINALIZED.name,
                )
        ) {
            "Authenticated group control/message committed, but its Olm receipt was not marked processed"
        }
    }

    private fun isGroupSessionKeyPayload(plaintext: String): Boolean = try {
        JSONObject(plaintext).opt("messageType") == "group_session_key"
    } catch (_: JSONException) {
        false
    }

    private fun isGroupMessagePayload(plaintext: String): Boolean = try {
        JSONObject(plaintext).opt("messageType") == "group_message"
    } catch (_: JSONException) {
        false
    }

    private companion object {
        val RECEIPT_ID_PATTERN = Regex("[A-Fa-f0-9]{64}")
    }
}
