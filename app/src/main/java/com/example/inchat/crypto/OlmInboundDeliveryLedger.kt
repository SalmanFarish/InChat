package com.example.inchat.crypto

import java.security.MessageDigest

internal enum class OlmInboundRegistration { NEW, PENDING_RETRY, DUPLICATE_PROCESSED, CONFLICT }

/**
 * Durable receiver-side deduplication ledger. PENDING receipts can be retried
 * after a crash; PROCESSED receipts must not be decrypted again.
 *
 * The eventual message-cache integration must commit the receipt and local
 * plaintext message atomically. Do not mark processed before that transaction.
 */
internal class OlmInboundDeliveryLedger(
    private val dao: OlmInboundReceiptDao,
    private val localDeviceId: String,
) {
    init {
        require(DEVICE_ID_PATTERN.matches(localDeviceId)) { "Local device ID has an invalid format" }
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
        val receiptId = sha256("$senderUserId\u0000${envelope.senderDeviceId}\u0000$messageId\u0000$localDeviceId")
        val envelopeDigest = sha256(encodedEnvelope)
        val row = OlmInboundReceiptEntity(
            receiptId = receiptId,
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
        if (stored.envelopeSha256 != envelopeDigest) return OlmInboundRegistration.CONFLICT
        if (inserted != -1L) return OlmInboundRegistration.NEW
        return if (stored.status == OlmInboundReceiptStatus.PROCESSED.name) {
            OlmInboundRegistration.DUPLICATE_PROCESSED
        } else {
            OlmInboundRegistration.PENDING_RETRY
        }
    }

    fun markProcessed(senderUserId: String, messageId: String, senderDeviceId: String): Boolean {
        require(USER_ID_PATTERN.matches(senderUserId))
        require(MESSAGE_ID_PATTERN.matches(messageId))
        require(DEVICE_ID_PATTERN.matches(senderDeviceId))
        val receiptId = sha256("$senderUserId\u0000$senderDeviceId\u0000$messageId\u0000$localDeviceId")
        return dao.markProcessed(receiptId, System.currentTimeMillis()) == 1
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
