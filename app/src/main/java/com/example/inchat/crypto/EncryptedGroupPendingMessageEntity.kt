package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index

/** Encrypted-at-rest outgoing group intent; plaintext never enters Room. */
@Entity(
    tableName = "encrypted_group_pending_messages",
    primaryKeys = ["ownerUserId", "groupId", "messageId"],
    indices = [Index(value = ["ownerUserId", "groupId", "sentAtMillis"])],
)
internal data class EncryptedGroupPendingMessageEntity(
    val ownerUserId: String,
    val groupId: String,
    val messageId: String,
    val sentAtMillis: Long,
    val contentEncryptionVersion: Int,
    val textNonceBase64: String,
    val textCiphertextBase64: String,
)
