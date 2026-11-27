package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Ciphertext-only receiver deduplication record. Plaintext is never stored here. */
@Entity(
    tableName = "olm_inbound_receipts",
    indices = [
        Index(value = ["recipientUserId", "senderUserId", "senderDeviceId", "messageId", "recipientDeviceId"], unique = true),
        Index(value = ["status", "receivedAtMillis"]),
        Index(value = ["status", "lastAttemptAtMillis", "receivedAtMillis"]),
    ],
)
data class OlmInboundReceiptEntity(
    @PrimaryKey val receiptId: String,
    /** Null only for pre-v14 receipts whose receiving account cannot be inferred safely. */
    val recipientUserId: String?,
    val senderUserId: String,
    val senderDeviceId: String,
    val messageId: String,
    val recipientDeviceId: String,
    val envelope: String,
    val envelopeSha256: String,
    val status: String,
    val receivedAtMillis: Long,
    val processedAtMillis: Long?,
    /** Latest failed processing attempt; null means never attempted. */
    val lastAttemptAtMillis: Long? = null,
)

/** FINALIZED means Room commit and native staged-plaintext cleanup both completed. */
enum class OlmInboundReceiptStatus {
    PENDING,
    WAITING_FOR_DEVICE,
    PROCESSED,
    FINALIZED,
    REJECTED,
}
