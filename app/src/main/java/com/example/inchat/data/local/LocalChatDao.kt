package com.example.inchat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalChatDao {

    @Query(
        "SELECT * FROM cached_messages " +
            "WHERE ownerUserId = :ownerUserId AND chatId = :chatId " +
            "ORDER BY timestamp ASC, messageId ASC"
    )
    fun observeMessages(
        ownerUserId: String,
        chatId: String
    ): Flow<List<CachedMessageEntity>>

    @Query(
        "SELECT * FROM cached_messages " +
            "WHERE ownerUserId = :ownerUserId AND chatId = :chatId " +
            "AND messageId = :messageId LIMIT 1"
    )
    suspend fun getMessage(
        ownerUserId: String,
        chatId: String,
        messageId: String
    ): CachedMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<CachedMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: CachedMessageEntity)

    @Query(
        "DELETE FROM cached_messages " +
            "WHERE ownerUserId = :ownerUserId AND chatId = :chatId " +
            "AND messageId = :messageId"
    )
    suspend fun deleteMessage(
        ownerUserId: String,
        chatId: String,
        messageId: String
    )

    @Query(
        "UPDATE cached_messages SET text = :text, edited = 1 " +
            "WHERE ownerUserId = :ownerUserId AND chatId = :chatId " +
            "AND messageId = :messageId"
    )
    suspend fun updateMessageText(
        ownerUserId: String,
        chatId: String,
        messageId: String,
        text: String
    )

    @Query(
        "DELETE FROM cached_messages " +
            "WHERE ownerUserId = :ownerUserId AND chatId = :chatId " +
            "AND timestamp >= :fromTimestamp"
    )
    suspend fun deleteMessagesFromTimestamp(
        ownerUserId: String,
        chatId: String,
        fromTimestamp: Long
    )

    /**
     * Replace the range covered by Firebase's latest-message query without
     * deleting older pages that the user has already loaded into Room.
     */
    @Transaction
    suspend fun replaceLatestSnapshot(
        ownerUserId: String,
        chatId: String,
        messages: List<CachedMessageEntity>
    ) {
        // An empty listener snapshot can occur before offline data is available.
        // Never interpret it as proof that the server deleted all saved history.
        if (messages.isNotEmpty()) {
            deleteMessagesFromTimestamp(
                ownerUserId = ownerUserId,
                chatId = chatId,
                fromTimestamp = messages.minOf { it.timestamp }
            )
            upsertMessages(messages)
        }
    }
}