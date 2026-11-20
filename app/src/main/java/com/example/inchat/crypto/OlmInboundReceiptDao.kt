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

    /** Receipts deferred until their sender device is published and SAS-trusted. */
    @Query("SELECT * FROM olm_inbound_receipts WHERE status = 'WAITING_FOR_DEVICE' ORDER BY receivedAtMillis ASC, receiptId ASC LIMIT :limit")
    fun getWaitingForDevice(limit: Int): List<OlmInboundReceiptEntity>

    @Query("UPDATE olm_inbound_receipts SET status = 'WAITING_FOR_DEVICE' WHERE receiptId = :receiptId AND status = 'PENDING'")
    fun markWaitingForDevice(receiptId: String): Int

    @Query("UPDATE olm_inbound_receipts SET status = 'PENDING' WHERE receiptId = :receiptId AND status = 'WAITING_FOR_DEVICE'")
    fun restorePending(receiptId: String): Int

    /** Receipts whose Room commit succeeded but native plaintext cleanup may need recovery. */
    @Query("SELECT * FROM olm_inbound_receipts WHERE status = 'PROCESSED' ORDER BY processedAtMillis ASC, receiptId ASC LIMIT :limit")
    fun getAwaitingFinalization(limit: Int): List<OlmInboundReceiptEntity>

    @Query("UPDATE olm_inbound_receipts SET status = 'FINALIZED' WHERE receiptId = :receiptId AND status = 'PROCESSED'")
    fun markFinalized(receiptId: String): Int

    @Query("UPDATE olm_inbound_receipts SET status = 'PROCESSED', processedAtMillis = :processedAtMillis WHERE receiptId = :receiptId AND status = 'PENDING'")
    fun markProcessed(receiptId: String, processedAtMillis: Long): Int
}
