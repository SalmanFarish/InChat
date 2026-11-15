package com.example.inchat.crypto

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.inchat.data.local.CachedMessageEntity
import com.example.inchat.data.local.LocalChatDao

/** Atomically persists a decrypted local message and completes its ciphertext receipt. */
internal class OlmInboundMessageCommitter(
    private val database: RoomDatabase,
    private val messageDao: LocalChatDao,
    private val receiptDao: OlmInboundReceiptDao,
) {
    /**
     * Returns true only when a PENDING receipt and its matching message were
     * committed together. A processed receipt is never overwritten or replayed.
     */
    suspend fun commit(
        receiptId: String,
        message: CachedMessageEntity,
        processedAtMillis: Long,
    ): Boolean {
        require(receiptId.isNotBlank()) { "Receipt ID must not be blank" }
        require(processedAtMillis >= 0) { "Processed time must not be negative" }
        require(!message.isPending) { "Decrypted inbound messages cannot be pending sends" }

        return database.withTransaction {
            val receipt = receiptDao.getByReceiptId(receiptId)
                ?: error("Inbound receipt does not exist")
            check(receipt.status == OlmInboundReceiptStatus.PENDING.name) {
                "Inbound receipt is not pending"
            }
            check(message.messageId == receipt.messageId) {
                "Decrypted message ID does not match its inbound receipt"
            }
            check(message.senderId == receipt.senderUserId) {
                "Decrypted message sender does not match its inbound receipt"
            }

            messageDao.upsertMessage(message)
            check(receiptDao.markProcessed(receiptId, processedAtMillis) == 1) {
                "Inbound receipt could not be marked processed"
            }
            true
        }
    }
}