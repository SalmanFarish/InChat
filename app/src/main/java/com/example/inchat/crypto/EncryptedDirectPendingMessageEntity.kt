package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index

/** Encrypted-at-rest direct-message intent waiting for the network delivery worker. */
@Entity(
    tableName = "encrypted_direct_pending_messages",
    primaryKeys = ["ownerUserId", "conversationId", "messageId"],
    indices = [Index(value = ["ownerUserId", "conversationId", "sentAtMillis"])],
)
data class EncryptedDirectPendingMessageEntity(
    val ownerUserId: String,
    val conversationId: String,
    val conversationPeerUserId: String,
    val messageId: String,
    val sentAtMillis: Long,
    val contentEncryptionVersion: Int,
    val textNonceBase64: String,
    val textCiphertextBase64: String,
)
