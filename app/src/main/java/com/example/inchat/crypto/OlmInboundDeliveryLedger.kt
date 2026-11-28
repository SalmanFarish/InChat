package com.example.inchat.crypto

import java.security.MessageDigest

internal enum class OlmInboundRegistration { NEW, PENDING_RETRY, DUPLICATE_PROCESSED, DUPLICATE_REJECTED, CONFLICT }

/** Stable receiver receipt identity, bound to the local account and both endpoint devices. */
internal fun olmInboundReceiptId(
    recipientUserId: String,
    senderUserId: String,
    senderDeviceId: String,
    messageId: String,
    recipientDeviceId: String,
): String = sha256InboundIdentity(
    "$recipientUserId\u0000$senderUserId\u0000$senderDeviceId\u0000$messageId\u0000$recipientDeviceId",
)

/** Reject corrupted or mismatched durable receipt metadata before touching a ratchet. */
internal fun validateOlmInboundReceiptIntegrity(receipt: OlmInboundReceiptEntity) {
    require(Regex("[A-Za-z0-9_-]{1,128}").matches(receipt.recipientUserId.orEmpty())) {
        "Inbound receipt recipient account is invalid or unassigned"
    }
    require(Regex("[A-Za-z0-9_-]{1,128}").matches(receipt.senderUserId)) {
        "Inbound receipt sender account is invalid"
    }
    require(Regex("[A-Za-z0-9_-]{1,64}").matches(receipt.senderDeviceId)) {
        "Inbound receipt sender device is invalid"
    }
    require(Regex("[A-Za-z0-9_-]{1,128}").matches(receipt.messageId)) {
        "Inbound receipt message ID is invalid"
    }
    require(Regex("[A-Za-z0-9_-]{1,64}").matches(receipt.recipientDeviceId)) {
        "Inbound receipt recipient device is invalid"
    }
    require(receipt.receivedAtMillis >= 0L) { "Inbound receipt receive time is invalid" }
    val status = runCatching { OlmInboundReceiptStatus.valueOf(receipt.status) }
        .getOrElse { throw IllegalArgumentException("Inbound receipt status is invalid", it) }
    when (status) {
        OlmInboundReceiptStatus.PENDING,
        OlmInboundReceiptStatus.WAITING_FOR_DEVICE,
        OlmInboundReceiptStatus.REJECTED -> require(receipt.processedAtMillis == null) {
            "Unprocessed inbound receipt must not have a processed timestamp"
        }
        OlmInboundReceiptStatus.PROCESSED,
        OlmInboundReceiptStatus.FINALIZED -> require(
            receipt.processedAtMillis != null && receipt.processedAtMillis >= 0L,
        ) { "Processed inbound receipt must have a valid processed timestamp" }
    }
    require(
        receipt.receiptId == olmInboundReceiptId(
            requireNotNull(receipt.recipientUserId),
            receipt.senderUserId,
            receipt.senderDeviceId,
            receipt.messageId,
            receipt.recipientDeviceId,
        ),
    ) { "Inbound receipt identity does not match its durable metadata" }
    val digest = sha256InboundIdentity(receipt.envelope)
    require(receipt.envelopeSha256.equals(digest, ignoreCase = true)) {
        "Inbound receipt envelope digest does not match its stored ciphertext"
    }
    val envelope = EncryptedOlmEnvelopeCodec.decode(receipt.envelope)
    require(
        envelope.senderDeviceId == receipt.senderDeviceId &&
            envelope.recipientDeviceId == receipt.recipientDeviceId,
    ) { "Inbound receipt envelope routing does not match its durable metadata" }
}

private fun sha256InboundIdentity(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

/**
 * Durable receiver-side deduplication ledger. New receipts are always scoped to
 * the account that currently owns this polling worker. Legacy unscoped rows are
 * never adopted by a later account.
 */
internal class OlmInboundDeliveryLedger(
    private val dao: OlmInboundReceiptDao,
    private val recipientUserId: String,
    private val localDeviceId: String,
) {
    init {
        require(USER_ID_PATTERN.matches(recipientUserId)) { "Recipient account ID has an invalid format" }
        require(DEVICE_ID_PATTERN.matches(localDeviceId)) { "Local device ID has an invalid format" }
    }

    /**
     * Returns a durable logical receive timestamp strictly after every ciphertext
     * already staged for this account. The receiver adds each mailbox page offset.
     */
    fun nextReceivedAtMillis(nowMillis: Long): Long {
        require(nowMillis >= 0L) { "Receive time must not be negative" }
        val nextAfterLatest = dao.getLatestReceivedAtMillis(recipientUserId)
            ?.let { Math.addExact(it, 1L) }
            ?: 0L
        return maxOf(nowMillis, nextAfterLatest)
    }

    fun register(
        senderUserId: String,
        messageId: String,
        encodedEnvelope: String,
        receivedAtMillis: Long,
    ): OlmInboundRegistration {
        require(USER_ID_PATTERN.matches(senderUserId)) { "Sender user ID has an invalid format" }
        require(MESSAGE_ID_PATTERN.matches(messageId)) { "Message ID has an invalid format" }
        require(receivedAtMillis >= 0) { "Receive time must not be negative" }

        val envelope = EncryptedOlmEnvelopeCodec.decode(encodedEnvelope)
        require(envelope.recipientDeviceId == localDeviceId) {
            "Inbound envelope is addressed to a different device"
        }
        val receiptId = olmInboundReceiptId(
            recipientUserId, senderUserId, envelope.senderDeviceId, messageId, localDeviceId,
        )
        val envelopeDigest = sha256InboundIdentity(encodedEnvelope)
        val row = OlmInboundReceiptEntity(
            receiptId = receiptId,
            recipientUserId = recipientUserId,
            senderUserId = senderUserId,
            senderDeviceId = envelope.senderDeviceId,
            messageId = messageId,
            recipientDeviceId = localDeviceId,
            envelope = encodedEnvelope,
            envelopeSha256 = envelopeDigest,
            status = OlmInboundReceiptStatus.PENDING.name,
            receivedAtMillis = receivedAtMillis,
            processedAtMillis = null,
        )
        val inserted = dao.insertIfAbsent(row)
        val stored = dao.getByReceiptId(receiptId)
            ?: error("Inbound receipt could not be read after insertion")
        if (runCatching { validateOlmInboundReceiptIntegrity(stored) }.isFailure) {
            return OlmInboundRegistration.CONFLICT
        }
        if (stored.envelopeSha256 != envelopeDigest) return OlmInboundRegistration.CONFLICT
        if (inserted != -1L) return OlmInboundRegistration.NEW
        return when (stored.status) {
            OlmInboundReceiptStatus.PROCESSED.name,
            OlmInboundReceiptStatus.FINALIZED.name -> OlmInboundRegistration.DUPLICATE_PROCESSED
            OlmInboundReceiptStatus.REJECTED.name -> OlmInboundRegistration.DUPLICATE_REJECTED
            else -> OlmInboundRegistration.PENDING_RETRY
        }
    }

    fun markProcessed(senderUserId: String, messageId: String, senderDeviceId: String): Boolean {
        require(USER_ID_PATTERN.matches(senderUserId))
        require(MESSAGE_ID_PATTERN.matches(messageId))
        require(DEVICE_ID_PATTERN.matches(senderDeviceId))
        val receiptId = olmInboundReceiptId(
            recipientUserId, senderUserId, senderDeviceId, messageId, localDeviceId,
        )
        val stored = dao.getByReceiptId(receiptId) ?: return false
        if (runCatching { validateOlmInboundReceiptIntegrity(stored) }.isFailure) return false
        if (stored.status != OlmInboundReceiptStatus.PENDING.name) return false
        return dao.markProcessed(receiptId, System.currentTimeMillis().coerceAtLeast(0L)) == 1
    }

    private companion object {
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
