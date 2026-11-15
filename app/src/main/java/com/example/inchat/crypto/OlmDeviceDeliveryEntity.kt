package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Ciphertext-only durable outbox row; no plaintext body or notification preview is stored. */
@Entity(
    tableName = "olm_device_deliveries",
    indices = [
        Index(value = ["status", "nextAttemptAtMillis", "leaseUntilMillis"]),
        Index(value = ["messageId"]),
        Index(value = ["messageId", "recipientUserId", "recipientDeviceId"], unique = true),
    ],
)
internal data class OlmDeviceDeliveryEntity(
    @PrimaryKey val deliveryId: String,
    val messageId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val envelope: String,
    val status: String,
    val attemptCount: Int,
    val nextAttemptAtMillis: Long,
    val leaseUntilMillis: Long,
    val lastError: String?,
)

internal fun QueuedOlmDeviceDelivery.toEntity() = OlmDeviceDeliveryEntity(
    deliveryId = deliveryId,
    messageId = messageId,
    recipientUserId = recipientUserId,
    recipientDeviceId = recipientDeviceId,
    envelope = envelope,
    status = status.name,
    attemptCount = attemptCount,
    nextAttemptAtMillis = nextAttemptAtMillis,
    leaseUntilMillis = leaseUntilMillis,
    lastError = lastError,
)

internal fun OlmDeviceDeliveryEntity.toDelivery() = QueuedOlmDeviceDelivery(
    messageId = messageId,
    deliveryId = deliveryId,
    recipientUserId = recipientUserId,
    recipientDeviceId = recipientDeviceId,
    envelope = envelope,
    status = OlmDeliveryStatus.valueOf(status),
    attemptCount = attemptCount,
    nextAttemptAtMillis = nextAttemptAtMillis,
    leaseUntilMillis = leaseUntilMillis,
    lastError = lastError,
)
