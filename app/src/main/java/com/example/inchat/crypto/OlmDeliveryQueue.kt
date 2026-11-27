package com.example.inchat.crypto

import java.security.MessageDigest

/** Shared hard cap for any per-message Olm fan-out (direct or group). */
internal const val MAX_OLM_DEVICE_DELIVERY_TARGETS = 256

/**
 * Validate a group-scoped outbox row against one authoritative membership
 * snapshot. Direct rows deliberately do not pass this check.
 */
internal fun isGroupDeliveryScopeCurrent(
    delivery: QueuedOlmDeviceDelivery,
    authenticatedOwnerUserId: String,
    membership: com.example.inchat.data.model.GroupCryptoMembershipSnapshot,
): Boolean {
    val groupId = delivery.groupId ?: return false
    val membershipEpoch = delivery.membershipEpoch ?: return false
    return membershipEpoch > 0L &&
        delivery.conversationId == groupId &&
        delivery.conversationPeerUserId == null &&
        membership.isValid() &&
        membership.groupId == groupId &&
        membership.epoch == membershipEpoch &&
        authenticatedOwnerUserId in membership.memberUserIds &&
        delivery.recipientUserId in membership.memberUserIds
}

/** State of one independently encrypted copy addressed to one device. */
internal enum class OlmDeliveryStatus {
    QUEUED,
    IN_FLIGHT,
    UPLOADED,
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
    val ownerUserId: String? = null,
    val senderDeviceId: String? = null,
    val groupId: String? = null,
    val membershipEpoch: Long? = null,
    val conversationId: String? = null,
    val conversationPeerUserId: String? = null,
    val envelope: String,
    val status: OlmDeliveryStatus = OlmDeliveryStatus.QUEUED,
    val attemptCount: Int = 0,
    val nextAttemptAtMillis: Long = 0,
    val leaseUntilMillis: Long = 0,
    val lastError: String? = null,
)

/** Non-secret identity metadata atomically committed with a group-message Olm fan-out. */
internal data class GroupOutgoingMessageCommit(
    val ownerUserId: String,
    val groupId: String,
    val messageId: String,
    val senderDeviceId: String,
    val membershipEpoch: Long,
    val megolmSessionId: String,
    val messageIndex: Long,
    val sentAtMillis: Long,
    val recipientDeviceCount: Int,
)

internal data class DirectOutgoingMessageCommit(val ownerUserId: String, val conversationId: String, val conversationPeerUserId: String, val messageId: String, val senderDeviceId: String, val sentAtMillis: Long, val recipientDeviceCount: Int)

/** A recipient-device acknowledgement returned by the authenticated sender's relay path. */
internal data class OlmDeliveryAcknowledgement(
    val deliveryId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val messageId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val relayOrigin: OlmRelayOrigin = OlmRelayOrigin.FIREBASE,
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

    /** Atomically persist group commit metadata and all corresponding outbox rows. */
    fun insertGroupMessageIfAbsent(
        deliveries: List<QueuedOlmDeviceDelivery>,
        commit: GroupOutgoingMessageCommit,
    ): Int = throw UnsupportedOperationException(
        "Queue store must implement atomic group commit persistence",
    )

    fun hasDirectMessageCommit(commit: DirectOutgoingMessageCommit): Boolean = false

    fun insertDirectMessageIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>, commit: DirectOutgoingMessageCommit): Int = throw UnsupportedOperationException("Queue store must implement atomic direct-message commit persistence")

    /** Atomically persist the direct outbox, commit marker, and sender-owned encrypted timeline row. */
    fun insertDirectMessageWithLocalCopyIfAbsent(
        deliveries: List<QueuedOlmDeviceDelivery>,
        commit: DirectOutgoingMessageCommit,
        localCopy: EncryptedOlmMessageEntity,
    ): Int = throw UnsupportedOperationException(
        "Queue store must implement atomic direct-message and encrypted local-copy persistence",
    )

    /** Check all immutable delivery identity fields, independent of queue status. */
    fun hasMatchingDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean

    /** Delete only the row matching all immutable delivery identity fields. */
    fun removeAcknowledgedDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean

    /**
     * Atomically lease the oldest due QUEUED/UPLOADED row or expired IN_FLIGHT
     * row. The returned row must have attemptCount incremented and a new lease.
     */
    fun claimNextDue(ownerUserId: String, senderDeviceId: String, nowMillis: Long, leaseDurationMillis: Long, maxAttempts: Int): QueuedOlmDeviceDelivery?

    /** Atomically claim only a due delivery belonging to one message. */
    fun claimNextDueForMessage(
        ownerUserId: String,
        senderDeviceId: String,
        messageId: String,
        nowMillis: Long,
        leaseDurationMillis: Long,
        maxAttempts: Int,
    ): QueuedOlmDeviceDelivery? = throw UnsupportedOperationException(
        "Queue store must implement message-scoped delivery claims",
    )

    /** Earliest future QUEUED/UPLOADED retry or IN_FLIGHT lease expiry for this sender scope. */
    fun nextScheduledRetryAtMillis(ownerUserId: String, senderDeviceId: String, nowMillis: Long): Long? = null

    /** Explicitly requeue a terminal row without changing its immutable ciphertext identity. */
    fun retryFailed(
        deliveryId: String,
        messageId: String,
        ownerUserId: String,
        senderDeviceId: String,
        nowMillis: Long,
    ): Boolean

    /** Atomically update a matching IN_FLIGHT row; return false if it is no longer leased. */
    fun updateInFlight(
        deliveryId: String,
        messageId: String,
        ownerUserId: String,
        senderDeviceId: String,
        expectedAttemptCount: Int,
        expectedLeaseUntilMillis: Long,
        nowMillis: Long,
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
        ownerUserId: String,
        senderDeviceId: String,
        messageId: String,
        prepared: List<PreparedOlmDelivery>,
        groupMessageCommit: GroupOutgoingMessageCommit? = null,
        directMessageCommit: DirectOutgoingMessageCommit? = null,
        groupId: String? = null,
        membershipEpoch: Long? = null,
        conversationId: String? = null,
        conversationPeerUserId: String? = null,
        directMessageLocalCopy: EncryptedOlmMessageEntity? = null,
    ): Int {
        require(USER_ID_PATTERN.matches(ownerUserId)) { "Sender user ID has an invalid format" }
        require(DEVICE_ID_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }
        require(messageId.isNotBlank() && messageId.length <= MAX_MESSAGE_ID_LENGTH) {
            "Message ID has an invalid format"
        }
        require(prepared.isNotEmpty()) { "At least one encrypted delivery is required" }
        require(prepared.size <= MAX_OLM_DEVICE_DELIVERY_TARGETS) {
            "Encrypted delivery fan-out exceeds the supported device count"
        }
        require(groupMessageCommit == null || directMessageCommit == null) { "A delivery batch cannot be both direct and group scoped" }
        require(directMessageLocalCopy == null || directMessageCommit != null) {
            "An encrypted sender-local copy must belong to a committed direct message"
        }
        val scopedGroupId = groupMessageCommit?.groupId ?: groupId
        val scopedMembershipEpoch = groupMessageCommit?.membershipEpoch ?: membershipEpoch
        require((scopedGroupId == null) == (scopedMembershipEpoch == null) &&
            (scopedGroupId == null || (scopedGroupId.matches(Regex("[A-Za-z0-9_-]{1,128}")) && scopedMembershipEpoch!! > 0L))
        ) { "Group delivery scope must include a valid group ID and positive membership epoch" }
        val nonOwnerRecipients = prepared.map { it.recipientUserId }.filter { it != ownerUserId }.distinct()
        val scopedPeerUserId = if (scopedGroupId == null) {
            conversationPeerUserId ?: nonOwnerRecipients.singleOrNull()
        } else {
            conversationPeerUserId
        }
        val scopedConversationId = scopedGroupId ?: conversationId ?: scopedPeerUserId?.let {
            OlmAuthenticatedMessagePayloadCodec.directConversationId(ownerUserId, it)
        }
        if (scopedGroupId == null) {
            require(scopedPeerUserId != null && USER_ID_PATTERN.matches(scopedPeerUserId) &&
                scopedPeerUserId != ownerUserId &&
                scopedConversationId == OlmAuthenticatedMessagePayloadCodec.directConversationId(ownerUserId, scopedPeerUserId) &&
                prepared.all { it.recipientUserId == ownerUserId || it.recipientUserId == scopedPeerUserId }
            ) { "Direct delivery scope must match its authenticated conversation peer" }
        } else {
            require(conversationId == null || conversationId == scopedGroupId)
            require(scopedPeerUserId == null)
        }
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
            require(decoded.senderDeviceId == senderDeviceId) {
                "Encrypted envelope sender does not match the outbox owner device"
            }
            require(decoded.recipientDeviceId == item.recipientDeviceId) {
                "Encrypted envelope recipient does not match its delivery target"
            }
            require(decoded.sessionId == item.sessionId) {
                "Encrypted envelope session does not match its delivery target"
            }
            QueuedOlmDeviceDelivery(
                messageId = messageId,
                deliveryId = deliveryId(ownerUserId, senderDeviceId, messageId, item.recipientUserId, item.recipientDeviceId),
                recipientUserId = item.recipientUserId,
                recipientDeviceId = item.recipientDeviceId,
                ownerUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
                groupId = scopedGroupId,
                membershipEpoch = scopedMembershipEpoch,
                conversationId = scopedConversationId,
                conversationPeerUserId = scopedPeerUserId,
                envelope = item.envelope,
            )
        }
        require(rows.map { it.deliveryId }.distinct().size == rows.size) {
            "A message cannot contain duplicate device deliveries"
        }
        groupMessageCommit?.let { commit ->
            require(commit.ownerUserId == ownerUserId &&
                commit.senderDeviceId == senderDeviceId &&
                commit.messageId == messageId &&
                commit.groupId.matches(Regex("[A-Za-z0-9_-]{1,128}")) &&
                commit.membershipEpoch > 0L &&
                commit.megolmSessionId.isNotBlank() && commit.megolmSessionId.length <= 128 &&
                commit.messageIndex >= 0L && commit.sentAtMillis >= 0L &&
                commit.recipientDeviceCount == rows.size
            ) { "Group outgoing commit metadata does not match its durable delivery batch" }
            return store.insertGroupMessageIfAbsent(rows, commit)
        }
        directMessageCommit?.let { commit ->
            require(commit.ownerUserId == ownerUserId && commit.senderDeviceId == senderDeviceId && commit.messageId == messageId && commit.conversationId == scopedConversationId && commit.conversationPeerUserId == scopedPeerUserId && commit.sentAtMillis >= 0L && commit.recipientDeviceCount == rows.size) { "Direct outgoing commit metadata does not match its durable delivery batch" }
            return if (directMessageLocalCopy == null) {
                store.insertDirectMessageIfAbsent(rows, commit)
            } else {
                store.insertDirectMessageWithLocalCopyIfAbsent(rows, commit, directMessageLocalCopy)
            }
        }
        return store.insertIfAbsent(rows)
    }

    fun hasDirectMessageCommit(commit: DirectOutgoingMessageCommit): Boolean = store.hasDirectMessageCommit(commit)

    /** Check that an acknowledgement exactly identifies a durable outbox row. */
    fun hasMatchingDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean =
        store.hasMatchingDelivery(acknowledgement)

    /** Remove an exactly matched row after its remote acknowledgement has been deleted. */
    fun removeAcknowledgedDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean =
        store.removeAcknowledgedDelivery(acknowledgement)

    /** Earliest future queued/uploaded retry or in-flight lease time for this sender device. */
    fun nextScheduledRetryAtMillis(ownerUserId: String, senderDeviceId: String, nowMillis: Long): Long? {
        require(USER_ID_PATTERN.matches(ownerUserId)) { "Sender user ID has an invalid format" }
        require(DEVICE_ID_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        return store.nextScheduledRetryAtMillis(ownerUserId, senderDeviceId, nowMillis)
    }

    /** Claim one due row. The caller must send the returned ciphertext envelope. */
    fun claimNext(
        ownerUserId: String,
        senderDeviceId: String,
        nowMillis: Long,
        messageId: String? = null,
    ): QueuedOlmDeviceDelivery? {
        require(USER_ID_PATTERN.matches(ownerUserId)) { "Sender user ID has an invalid format" }
        require(DEVICE_ID_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }
        require(nowMillis >= 0) { "nowMillis must not be negative" }
        if (messageId == null) {
            return store.claimNextDue(ownerUserId, senderDeviceId, nowMillis, leaseDurationMillis, maxAttempts)
        }
        require(messageId.isNotBlank() && messageId.length <= MAX_MESSAGE_ID_LENGTH) {
            "Message ID has an invalid format"
        }
        return store.claimNextDueForMessage(
            ownerUserId, senderDeviceId, messageId, nowMillis, leaseDurationMillis, maxAttempts,
        )
    }

    /**
     * Persist successful relay acceptance while keeping the immutable ciphertext
     * eligible for an exact retry after the acknowledgement window expires.
     * This distinguishes "Sent" from "still uploading" without losing recovery.
     */
    fun markUploaded(delivery: QueuedOlmDeviceDelivery, nowMillis: Long): Boolean {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        return updateInFlight(delivery, nowMillis) { row ->
            row.copy(
                status = OlmDeliveryStatus.UPLOADED,
                nextAttemptAtMillis = safeAdd(nowMillis, UPLOADED_RETRY_INTERVAL_MILLIS),
                leaseUntilMillis = 0L,
                lastError = null,
            )
        }
    }

    /** Mark only the still-active claim acknowledged; duplicate or stale claims are harmless. */
    fun acknowledge(delivery: QueuedOlmDeviceDelivery, nowMillis: Long): Boolean =
        updateInFlight(delivery, nowMillis) { row ->
            row.copy(
                status = OlmDeliveryStatus.ACKNOWLEDGED,
                leaseUntilMillis = 0,
                lastError = null,
            )
        }

    /**
     * Release a failed attempt with capped exponential backoff.
     * Production retries transient failures indefinitely; callers may still
     * provide a finite maxAttempts budget for tests or explicit policies.
     */
    fun retry(
        delivery: QueuedOlmDeviceDelivery,
        nowMillis: Long,
        errorCode: String,
    ): Boolean {
        require(nowMillis >= 0) { "nowMillis must not be negative" }
        require(errorCode.isNotBlank() && errorCode.length <= MAX_ERROR_LENGTH) {
            "Error code has an invalid format"
        }
        return updateInFlight(delivery, nowMillis) { row ->
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

    /**
     * Permanently quarantine a claimed delivery when its committed group
     * membership epoch is no longer current. The ciphertext remains durable
     * for diagnosis; it is not retried as though this were a network failure.
     */
    fun failPermanently(
        delivery: QueuedOlmDeviceDelivery,
        nowMillis: Long,
        errorCode: String,
    ): Boolean {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        require(errorCode.isNotBlank() && errorCode.length <= MAX_ERROR_LENGTH) {
            "Error code has an invalid format"
        }
        return updateInFlight(delivery, nowMillis) { row ->
            row.copy(
                status = OlmDeliveryStatus.FAILED,
                leaseUntilMillis = 0L,
                lastError = errorCode,
            )
        }
    }

    /**
     * Explicit application action to retry a terminal failure. The exact
     * original envelope and deterministic delivery ID are retained. Upload
     * revalidates the recipient device and SAS trust before writing the relay.
     */
    fun retryFailed(delivery: QueuedOlmDeviceDelivery, nowMillis: Long): Boolean {
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        if (delivery.status != OlmDeliveryStatus.FAILED) return false
        val ownerUserId = delivery.ownerUserId ?: return false
        val senderDeviceId = delivery.senderDeviceId ?: return false
        return store.retryFailed(
            deliveryId = delivery.deliveryId,
            messageId = delivery.messageId,
            ownerUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            nowMillis = nowMillis,
        )
    }

    /**
     * Fence state transitions with the claim generation. A late worker cannot
     * mutate a lease reclaimed by another worker, even if its clock moves back.
     */
    private fun updateInFlight(
        delivery: QueuedOlmDeviceDelivery,
        nowMillis: Long,
        update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
    ): Boolean {
        if (delivery.status != OlmDeliveryStatus.IN_FLIGHT ||
            delivery.attemptCount <= 0 ||
            delivery.leaseUntilMillis <= nowMillis
        ) return false
        val ownerUserId = delivery.ownerUserId ?: return false
        val senderDeviceId = delivery.senderDeviceId ?: return false
        return store.updateInFlight(
            deliveryId = delivery.deliveryId,
            messageId = delivery.messageId,
            ownerUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            expectedAttemptCount = delivery.attemptCount,
            expectedLeaseUntilMillis = delivery.leaseUntilMillis,
            nowMillis = nowMillis,
            update = update,
        )
    }

    private fun deliveryId(ownerUserId: String, senderDeviceId: String, messageId: String, userId: String, deviceId: String): String {
        val input = "$ownerUserId\u0000$senderDeviceId\u0000$messageId\u0000$userId\u0000$deviceId".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(input).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val UPLOADED_RETRY_INTERVAL_MILLIS = 10L * 60L * 1000L
        const val DEFAULT_MAX_ATTEMPTS = Int.MAX_VALUE
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
