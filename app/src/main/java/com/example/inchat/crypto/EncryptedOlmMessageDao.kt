package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** DAO for authenticated, locally decrypted Olm messages; not observed by plaintext chat flows. */
@Dao
interface EncryptedOlmMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: EncryptedOlmMessageEntity)

    /** Insert once; a message-ID collision must be checked, never silently replaced. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: EncryptedOlmMessageEntity): Long

    /** Synchronous variants used only inside RoomDatabase.runInTransaction. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMessageIfAbsentInTransaction(message: EncryptedOlmMessageEntity): Long

    @Query(
        "SELECT * FROM encrypted_olm_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId " +
            "AND messageId = :messageId LIMIT 1"
    )
    fun getMessageInTransaction(
        ownerUserId: String,
        conversationId: String,
        messageId: String,
    ): EncryptedOlmMessageEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM encrypted_olm_messages)")
    suspend fun hasStoredMessages(): Boolean

    /** Durable local notification outbox; message bodies remain encrypted at rest. */
    @Query("SELECT * FROM encrypted_olm_messages WHERE ownerUserId = :ownerUserId AND notificationPending = 1 ORDER BY sentAtMillis ASC, messageId ASC LIMIT :limit")
    suspend fun getPendingNotificationMessages(ownerUserId: String, limit: Int): List<EncryptedOlmMessageEntity>

    @Query("UPDATE encrypted_olm_messages SET notificationPending = 0 WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId AND messageId = :messageId AND notificationPending = 1")
    suspend fun markNotificationPublished(ownerUserId: String, conversationId: String, messageId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM encrypted_olm_messages WHERE ownerUserId = :ownerUserId AND notificationPending = 1)")
    suspend fun hasPendingNotifications(ownerUserId: String): Boolean

    @Query(
        "SELECT * FROM encrypted_olm_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId " +
            "AND messageId = :messageId LIMIT 1"
    )
    suspend fun getMessage(
        ownerUserId: String,
        conversationId: String,
        messageId: String,
    ): EncryptedOlmMessageEntity?

    @Query(
        "DELETE FROM encrypted_olm_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId " +
            "AND messageId = :messageId"
    )
    suspend fun deleteMessageForOwner(
        ownerUserId: String,
        conversationId: String,
        messageId: String,
    ): Int

    @Query(
        "DELETE FROM encrypted_olm_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId"
    )
    suspend fun deleteConversationForOwner(
        ownerUserId: String,
        conversationId: String,
    ): Int

    @Query(
        "SELECT * FROM encrypted_olm_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId " +
            "ORDER BY sentAtMillis ASC, messageId ASC"
    )
    fun observeMessages(
        ownerUserId: String,
        conversationId: String,
    ): Flow<List<EncryptedOlmMessageEntity>>
}
