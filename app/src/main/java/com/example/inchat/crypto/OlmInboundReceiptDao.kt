package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OlmInboundReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(receipt: OlmInboundReceiptEntity): Long

    @Query("SELECT * FROM olm_inbound_receipts WHERE receiptId = :receiptId LIMIT 1")
    fun getByReceiptId(receiptId: String): OlmInboundReceiptEntity?

    @Query("UPDATE olm_inbound_receipts SET status = 'PROCESSED', processedAtMillis = :processedAtMillis WHERE receiptId = :receiptId AND status = 'PENDING'")
    fun markProcessed(receiptId: String, processedAtMillis: Long): Int
}
