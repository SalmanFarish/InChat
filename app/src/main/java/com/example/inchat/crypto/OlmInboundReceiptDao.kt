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

    /** Oldest ciphertext receipts that still need authenticated decryption and message commit. */
    @Query("SELECT * FROM olm_inbound_receipts WHERE status = 'PENDING' ORDER BY receivedAtMillis ASC, receiptId ASC LIMIT :limit")
    fun getPending(limit: Int): List<OlmInboundReceiptEntity>

    @Query("UPDATE olm_inbound_receipts SET status = 'PROCESSED', processedAtMillis = :processedAtMillis WHERE receiptId = :receiptId AND status = 'PENDING'")
    fun markProcessed(receiptId: String, processedAtMillis: Long): Int
}
