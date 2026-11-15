package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable replay ledger for short-lived SAS exchange messages. */
@Entity(
    tableName = "sas_verification_messages",
    indices = [
        Index(value = ["transactionId", "senderUserId", "senderDeviceId", "type"], unique = true),
        Index(value = ["expiresAtMillis"]),
    ],
)
data class SasVerificationMessageEntity(
    @PrimaryKey val deliveryId: String,
    val transactionId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val type: String,
    val payloadSha256: String,
    val expiresAtMillis: Long,
    val receivedAtMillis: Long,
)
