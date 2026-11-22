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

    @Query("SELECT EXISTS(SELECT 1 FROM encrypted_olm_messages)")
    suspend fun hasStoredMessages(): Boolean

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
        "SELECT * FROM encrypted_olm_messages " +
            "WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId " +
            "ORDER BY sentAtMillis ASC, messageId ASC"
    )
    fun observeMessages(
        ownerUserId: String,
        conversationId: String,
    ): Flow<List<EncryptedOlmMessageEntity>>
}
