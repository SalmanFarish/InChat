package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index

/**
 * Locally decrypted direct-text message storage for the isolated Olm pipeline.
 *
 * This table is intentionally separate from cached_messages, which is observed
 * by ChatRepository and therefore feeds the current plaintext chat UI.
 */
@Entity(
    tableName = "encrypted_olm_messages",
    primaryKeys = ["ownerUserId", "conversationId", "messageId"],
    indices = [Index(value = ["ownerUserId", "conversationId", "sentAtMillis"])],
)
data class EncryptedOlmMessageEntity(
    val ownerUserId: String,
    val conversationId: String,
    val messageId: String,
    val conversationPeerUserId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val sentAtMillis: Long,
    /** Decrypted app-private Room content; Keystore-backed at-rest encryption remains pending. */
    val text: String,
)
