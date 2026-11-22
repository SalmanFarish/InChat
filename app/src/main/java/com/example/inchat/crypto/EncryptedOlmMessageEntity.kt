package com.example.inchat.crypto

import androidx.room.Entity
import androidx.room.Index

/**
 * Authenticated metadata and AES-GCM ciphertext for a locally decrypted Olm message.
 *
 * Message text is never persisted as a Room plaintext column. Its nonce and
 * ciphertext are authenticated against every routing/identity field below.
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
    val contentEncryptionVersion: Int,
    val textNonceBase64: String,
    val textCiphertextBase64: String,
)

/**
 * An account may own a local encrypted copy as either the sender or recipient.
 * This lets the sending installation retain its own copy without writing plaintext
 * into the legacy chat cache or changing the inbound message's ownership rules.
 */
internal object EncryptedOlmMessageOwnershipPolicy {
    fun isOwnedByAccount(ownerUserId: String, senderUserId: String, recipientUserId: String): Boolean =
        ownerUserId == senderUserId || ownerUserId == recipientUserId

    fun isOutgoing(ownerUserId: String, senderUserId: String): Boolean =
        ownerUserId == senderUserId
}

/** Decrypted message data held only in process memory until encrypted for Room. */
internal data class DecryptedOlmMessage(
    val ownerUserId: String,
    val conversationId: String,
    val messageId: String,
    val conversationPeerUserId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val sentAtMillis: Long,
    val text: String,
) {
    /** True for the sender's own local copy, including a copy synced to another sender device. */
    val isOutgoing: Boolean
        get() = EncryptedOlmMessageOwnershipPolicy.isOutgoing(ownerUserId, senderUserId)
}
