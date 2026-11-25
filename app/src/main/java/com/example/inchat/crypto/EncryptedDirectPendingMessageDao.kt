package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Local encrypted outbox for direct conversations. No plaintext text columns exist. */
@Dao
interface EncryptedDirectPendingMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingMessage(message: EncryptedDirectPendingMessageEntity): Long

    @Query(
        "SELECT * FROM encrypted_direct_pending_messages " +
            "WHERE ownerUserId = :ownerUserId " +
            "ORDER BY sentAtMillis ASC, conversationId ASC, messageId ASC"
    )
    suspend fun getPendingMessagesForOwner(ownerUserId: String): List<EncryptedDirectPendingMessageEntity>

    @Query(
        "SELECT * FROM encrypted_direct_pending_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId " +
            "ORDER BY sentAtMillis ASC, messageId ASC"
    )
    fun observePendingMessages(
        ownerUserId: String,
        conversationId: String,
    ): Flow<List<EncryptedDirectPendingMessageEntity>>

    @Query(
        "SELECT * FROM encrypted_direct_pending_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId " +
            "AND messageId = :messageId LIMIT 1"
    )
    suspend fun getPendingMessage(
        ownerUserId: String,
        conversationId: String,
        messageId: String,
    ): EncryptedDirectPendingMessageEntity?

    @Query(
        "DELETE FROM encrypted_direct_pending_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId " +
            "AND messageId = :messageId"
    )
    suspend fun deletePendingMessage(
        ownerUserId: String,
        conversationId: String,
        messageId: String,
    ): Int

    @Query(
        "DELETE FROM encrypted_direct_pending_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId"
    )
    suspend fun deleteConversationForOwner(
        ownerUserId: String,
        conversationId: String,
    ): Int
}
