package com.example.inchat.crypto

import java.security.MessageDigest

/** State of one independently encrypted copy addressed to one device. */
internal enum class OlmDeliveryStatus {
    QUEUED,
    IN_FLIGHT,
    ACKNOWLEDGED,
    FAILED,
}

/**
 * A local outbox row. The envelope contains ciphertext only; never put plaintext
 * message bodies, previews, or attachment keys in this record.
 */
internal data class QueuedOlmDeviceDelivery(
    val messageId: String,
    val deliveryId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val envelope: String,
    val status: OlmDeliveryStatus = OlmDeliveryStatus.QUEUED,
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    val leaseUntilMillis: Long = 0,
    val lastError: String? = null,
)

/** A recipient-device acknowledgement returned by the authenticated sender's relay path. */
internal data class OlmDeliveryAcknowledgement(
    val deliveryId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val messageId: String,
)

/**
 * Atomic persistence boundary for the outbox.
 *
 * Implementations must persist only ciphertext/routing/status metadata and make
 * each operation transactional across threads and process restarts. In
 * particular, claimNextDue must atomically lease exactly one eligible row, and
 * updateInFlight must compare both deliveryId and messageId before changing it.
 */
internal interface OlmDeliveryQueueStore {
    /** Insert all rows idempotently; an existing deliveryId must never be replaced. */
    fun insertIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>): Int

    /** Check all immutable delivery identity fields, independent of queue status. */
    fun hasMatchingDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean

    /** Delete only the row matching all immutable delivery identity fields. */
    fun removeAcknowledgedDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean

    /**
     * Atomically lease the oldest due QUEUED row or expired IN_FLIGHT row.
     * The returned row must have attemptCount incremented and a new lease.
     */
    fun claimNextDue(nowMillis: Long, leaseDurationMillis: Long, maxAttempts: Int): QueuedOlmDeviceDelivery?

    /** Atomically update a matching IN_FLIGHT row; return false if it is no longer leased. */
    fun updateInFlight(
        deliveryId: String,
        messageId: String,
        update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
    ): Boolean
}

/**
 * Pure queue coordinator. It deliberately has no network sender and is not
 * connected to Firebase or live chat. A transport may send only a claimed row.
 *
 * At-least-once transport is unavoidable if a process dies after remote receipt
 * but before local acknowledgement. Receivers must deduplicate by messageId;
 * deliveryId is the sender-side per-device idempotency key.
 */
internal class OlmDeliveryQueue(
    private val store: OlmDeliveryQueueStore,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val leaseDurationMillis: Long = DEFAULT_LEASE_MILLIS,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(leaseDurationMillis > 0) { "leaseDurationMillis must be positive" }
    }

    /** Enqueue one already-encrypted envelope per planned target, idempotently. */
    fun enqueue(
        messageId: String,
        prepared: List<PreparedOlmDelivery>,
    ): Int {
        require(messageId.isNotBlank() && messageId.length <= MAX_MESSAGE_ID_LENGTH) {
            "Message ID has an invalid format"
        }
        require(prepared.isNotEmpty()) { "At least one encrypted delivery is required" }
        val rows = prepared.map { item ->
            require(USER_ID_PATTERN.matches(item.recipientUserId)) { "Recipient user ID has an invalid format" }
            require(DEVICE_ID_PATTERN.matches(item.recipientDeviceId)) { "Recipient device ID has an invalid format" }
            require(item.sessionId.isNotBlank() && item.sessionId.length <= MAX_SESSION_ID_LENGTH) {
                "Olm session ID has an invalid format"
            }
            require(item.envelope.isNotBlank() && item.envelope.length <= MAX_ENVELOPE_CHARS) {
                "Encrypted envelope has an invalid size"
            }
            val decoded = EncryptedOlmEnvelopeCodec.decode(item.envelope)
            require(decoded.recipientDeviceId == item.recipientDeviceId) {
                "Encrypted envelope recipient does not match its delivery target"
            }
            require(decoded.sessionId == item.sessionId) {
                "Encrypted envelope session does not match its delivery target"
            }
            QueuedOlmDeviceDelivery(
                messageId = messageId,
                deliveryId = deliveryId(messageId, item.recipientUserId, item.recipientDeviceId),
                recipientUserId = item.recipientUserId,
                recipientDeviceId = item.recipientDeviceId,
                envelope = item.envelope,
            )
        }
        require(rows.map { it.deliveryId }.distinct().size == rows.size) {
            "A message cannot contain duplicate device deliveries"
        }
        return store.insertIfAbsent(rows)
    }

    /** Check that an acknowledgement exactly identifies a durable outbox row. */
    fun hasMatchingDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean =
        store.hasMatchingDelivery(acknowledgement)

    /** Remove an exactly matched row after its remote acknowledgement has been deleted. */
    fun removeAcknowledgedDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean =
        store.removeAcknowledgedDelivery(acknowledgement)

    /** Claim one due row. The caller must send the returned ciphertext envelope. */
    fun claimNext(nowMillis: Long): QueuedOlmDeviceDelivery? {
        require(nowMillis >= 0) { "nowMillis must not be negative" }
        return store.claimNextDue(nowMillis, leaseDurationMillis, maxAttempts)
    }

    /** Mark a delivery acknowledged; duplicate acknowledgements are harmless. */
    fun acknowledge(deliveryId: String, messageId: String): Boolean =
        store.updateInFlight(deliveryId, messageId) { row ->
            row.copy(
                status = OlmDeliveryStatus.ACKNOWLEDGED,
                leaseUntilMillis = 0,
                lastError = null,
            )
        }

    /**
     * Release a failed attempt with bounded exponential backoff.
     * Once maxAttempts is reached the row becomes terminal FAILED.
     */
    fun retry(
        deliveryId: String,
        messageId: String,
        nowMillis: Long,
        errorCode: String,
    ): Boolean {
        require(nowMillis >= 0) { "nowMillis must not be negative" }
        require(errorCode.isNotBlank() && errorCode.length <= MAX_ERROR_LENGTH) {
            "Error code has an invalid format"
        }
        return store.updateInFlight(deliveryId, messageId) { row ->
            if (row.attemptCount >= maxAttempts) {
                row.copy(
                    status = OlmDeliveryStatus.FAILED,
                    leaseUntilMillis = 0,
                    lastError = errorCode,
                )
            } else {
                val exponent = (row.attemptCount - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
                val delay = BASE_RETRY_DELAY_MILLIS * (1L shl exponent)
                row.copy(
                    status = OlmDeliveryStatus.QUEUED,
                    nextAttemptAtMillis = safeAdd(nowMillis, delay),
                    leaseUntilMillis = 0,
                    lastError = errorCode,
                )
            }
        }
    }

    private fun deliveryId(messageId: String, userId: String, deviceId: String): String {
        val input = "$messageId\u0000$userId\u0000$deviceId".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8
        const val DEFAULT_LEASE_MILLIS = 60_000L
        const val BASE_RETRY_DELAY_MILLIS = 2_000L
        const val MAX_BACKOFF_EXPONENT = 10
        const val MAX_MESSAGE_ID_LENGTH = 128
        const val MAX_SESSION_ID_LENGTH = 128
        const val MAX_ENVELOPE_CHARS = 2 * 1024 * 1024
        const val MAX_ERROR_LENGTH = 128
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
