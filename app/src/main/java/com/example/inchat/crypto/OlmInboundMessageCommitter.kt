package com.example.inchat.crypto

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** Atomically persists an AES-GCM-encrypted local message and completes its ciphertext receipt. */
internal class OlmInboundMessageCommitter(
    private val database: RoomDatabase,
    private val encryptedMessageDao: EncryptedOlmMessageDao,
    private val receiptDao: OlmInboundReceiptDao,
    private val vault: EncryptedOlmMessageVault = EncryptedOlmMessageVault(),
) {
    /**
     * Encrypts plaintext before the Room transaction, then commits only ciphertext
     * with the receipt. Existing plaintext chat cache is never written.
     */
    suspend fun commit(
        receiptId: String,
        message: DecryptedOlmMessage,
        processedAtMillis: Long,
    ): Boolean {
        require(receiptId.matches(RECEIPT_ID_PATTERN)) {
            "Inbound receipt ID must be a SHA-256 hex digest"
        }
        require(processedAtMillis >= 0) { "Processed time must not be negative" }
        require(message.ownerUserId == message.recipientUserId) {
            "Encrypted message owner must match its recipient"
        }

        // A missing Keystore key may be created only for a genuinely empty
        // encrypted-message table. Never rotate into a new key while old rows exist.
        val allowKeyCreation = !encryptedMessageDao.hasStoredMessages()
        val encryptedMessage = vault.encrypt(
            message = message,
            allowKeyCreation = allowKeyCreation,
        )

        return database.withTransaction {
            val receipt = receiptDao.getByReceiptId(receiptId)
                ?: error("Inbound receipt does not exist")
            check(receipt.status == OlmInboundReceiptStatus.PENDING.name) {
                "Inbound receipt is not pending"
            }
            check(encryptedMessage.messageId == receipt.messageId) {
                "Decrypted message ID does not match its inbound receipt"
            }
            check(encryptedMessage.senderUserId == receipt.senderUserId) {
                "Decrypted message sender does not match its inbound receipt"
            }
            check(encryptedMessage.senderDeviceId == receipt.senderDeviceId) {
                "Decrypted message sender device does not match its inbound receipt"
            }
            check(encryptedMessage.recipientDeviceId == receipt.recipientDeviceId) {
                "Decrypted message recipient device does not match its inbound receipt"
            }

            check(
                encryptedMessageDao.getMessage(
                    encryptedMessage.ownerUserId,
                    encryptedMessage.conversationId,
                    encryptedMessage.messageId,
                ) == null
            ) {
                "An encrypted message with this identity already exists for another or incomplete receipt"
            }
            encryptedMessageDao.upsertMessage(encryptedMessage)
            check(receiptDao.markProcessed(receiptId, processedAtMillis) == 1) {
                "Inbound receipt could not be marked processed"
            }
            true
        }
    }

    private companion object {
        val RECEIPT_ID_PATTERN = Regex("[A-Fa-f0-9]{64}")
    }
}
