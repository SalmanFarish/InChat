package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index

/** AES-GCM encrypted group plaintext, isolated from legacy plaintext chat rows. */
@Entity(
    tableName = "encrypted_group_messages",
    primaryKeys = ["ownerUserId", "groupId", "messageId"],
    indices = [Index(value = ["ownerUserId", "groupId", "sentAtMillis"])],
)
internal data class EncryptedGroupMessageEntity(
    val ownerUserId: String,
    val groupId: String,
    val messageId: String,
    val membershipEpoch: Long,
    val senderUserId: String,
    val senderDeviceId: String,
    val megolmSessionId: String,
    val messageIndex: Long,
    val sentAtMillis: Long,
    val contentEncryptionVersion: Int,
    val textNonceBase64: String,
    val textCiphertextBase64: String,
)

/** Receipt/replay ledger committed atomically with its encrypted message. */
@Entity(
    tableName = "encrypted_group_message_receipts",
    primaryKeys = [
        "ownerUserId", "groupId", "senderUserId", "senderDeviceId",
        "megolmSessionId", "messageIndex",
    ],
    indices = [
        Index(value = ["receiptId"], unique = true),
        Index(value = ["ownerUserId", "groupId", "receivedAtMillis"]),
    ],
)
internal data class EncryptedGroupMessageReceiptEntity(
    val ownerUserId: String,
    val groupId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val megolmSessionId: String,
    val messageIndex: Long,
    val messageId: String,
    val receiptId: String,
    val ciphertextSha256: String,
    val receivedAtMillis: Long,
)

/** In-memory plaintext; never a Room entity or database column. */
internal data class DecryptedGroupMessage(
    val ownerUserId: String,
    val groupId: String,
    val messageId: String,
    val membershipEpoch: Long,
    val senderUserId: String,
    val senderDeviceId: String,
    val megolmSessionId: String,
    val messageIndex: Long,
    val sentAtMillis: Long,
    val text: String,
)
