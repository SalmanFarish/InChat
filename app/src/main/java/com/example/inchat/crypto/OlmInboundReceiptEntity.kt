package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Ciphertext-only receiver deduplication record. Plaintext is never stored here. */
@Entity(
    tableName = "olm_inbound_receipts",
    indices = [
        Index(value = ["senderUserId", "senderDeviceId", "messageId", "recipientDeviceId"], unique = true),
        Index(value = ["status", "receivedAtMillis"]),
    ],
)
data class OlmInboundReceiptEntity(
    @PrimaryKey val receiptId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val messageId: String,
    val recipientDeviceId: String,
    val envelope: String,
    val envelopeSha256: String,
    val status: String,
    val receivedAtMillis: Long,
    val processedAtMillis: Long?,
)

enum class OlmInboundReceiptStatus { PENDING, PROCESSED }
