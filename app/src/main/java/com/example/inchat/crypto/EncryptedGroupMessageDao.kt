package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Durable group replay protection and encrypted-content persistence. */
@Dao
abstract class EncryptedGroupMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReceiptIgnore(
        receipt: EncryptedGroupMessageReceiptEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMessageIgnore(
        message: EncryptedGroupMessageEntity,
    ): Long

    /**
     * The replay key and encrypted message are one transaction. A duplicate
     * replay key is a no-op; a message-ID collision rolls the transaction back.
     */
    @Transaction
    open suspend fun commitDecryptedMessage(
        receipt: EncryptedGroupMessageReceiptEntity,
        message: EncryptedGroupMessageEntity,
    ): Boolean {
        require(
            receipt.ownerUserId == message.ownerUserId &&
                receipt.groupId == message.groupId &&
                receipt.messageId == message.messageId &&
                receipt.senderUserId == message.senderUserId &&
                receipt.senderDeviceId == message.senderDeviceId &&
                receipt.megolmSessionId == message.megolmSessionId &&
                receipt.messageIndex == message.messageIndex
        ) { "Group replay receipt does not match encrypted message metadata" }
        if (insertReceiptIgnore(receipt) == -1L) {
            val existing = getReplayReceipt(
                receipt.ownerUserId,
                receipt.groupId,
                receipt.senderUserId,
                receipt.senderDeviceId,
                receipt.megolmSessionId,
                receipt.messageIndex,
            )
            check(existing == receipt) {
                "Megolm replay index is already committed with different authenticated metadata"
            }
            return false
        }
        check(insertMessageIgnore(message) != -1L) {
            "Group message ID already exists with a different replay receipt"
        }
        return true
    }

    @Query(
        "SELECT * FROM encrypted_group_message_receipts " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId " +
            "AND senderUserId = :senderUserId AND senderDeviceId = :senderDeviceId " +
            "AND megolmSessionId = :sessionId AND messageIndex = :messageIndex LIMIT 1"
    )
    abstract suspend fun getReplayReceipt(
        ownerUserId: String,
        groupId: String,
        senderUserId: String,
        senderDeviceId: String,
        sessionId: String,
        messageIndex: Long,
    ): EncryptedGroupMessageReceiptEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM encrypted_group_messages)")
    abstract suspend fun hasStoredMessages(): Boolean

    @Query(
        "SELECT * FROM encrypted_group_messages " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId " +
            "ORDER BY sentAtMillis ASC, messageId ASC"
    )
    abstract fun observeMessages(
        ownerUserId: String,
        groupId: String,
    ): Flow<List<EncryptedGroupMessageEntity>>

    @Query(
        "SELECT * FROM encrypted_group_messages " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId AND messageId = :messageId LIMIT 1"
    )
    abstract suspend fun getMessage(
        ownerUserId: String,
        groupId: String,
        messageId: String,
    ): EncryptedGroupMessageEntity?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM encrypted_group_message_receipts " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId " +
            "AND senderUserId = :senderUserId AND senderDeviceId = :senderDeviceId " +
            "AND megolmSessionId = :sessionId AND messageIndex = :messageIndex)"
    )
    abstract suspend fun hasReplay(
        ownerUserId: String,
        groupId: String,
        senderUserId: String,
        senderDeviceId: String,
        sessionId: String,
        messageIndex: Long,
    ): Boolean
}
