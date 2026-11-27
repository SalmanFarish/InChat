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

    /** Oldest ciphertext receipts belonging only to the authenticated local account. */
    @Query("SELECT * FROM olm_inbound_receipts WHERE recipientUserId = :recipientUserId AND status = 'PENDING' ORDER BY CASE WHEN lastAttemptAtMillis IS NULL THEN 0 ELSE 1 END ASC, lastAttemptAtMillis ASC, receivedAtMillis ASC, receiptId ASC LIMIT :limit")
    fun getPending(recipientUserId: String, limit: Int): List<OlmInboundReceiptEntity>

    /** Deferred receipts remain account-scoped while waiting for device publication or SAS trust. */
    @Query("SELECT * FROM olm_inbound_receipts WHERE recipientUserId = :recipientUserId AND status = 'WAITING_FOR_DEVICE' ORDER BY receivedAtMillis ASC, receiptId ASC LIMIT :limit")
    fun getWaitingForDevice(recipientUserId: String, limit: Int): List<OlmInboundReceiptEntity>

    @Query("UPDATE olm_inbound_receipts SET lastAttemptAtMillis = :attemptedAtMillis WHERE receiptId = :receiptId AND status = 'PENDING'")
    fun markRetryAttempted(receiptId: String, attemptedAtMillis: Long): Int

    @Query("UPDATE olm_inbound_receipts SET status = 'WAITING_FOR_DEVICE' WHERE receiptId = :receiptId AND status = 'PENDING'")
    fun markWaitingForDevice(receiptId: String): Int

    @Query("UPDATE olm_inbound_receipts SET status = 'PENDING' WHERE receiptId = :receiptId AND status = 'WAITING_FOR_DEVICE'")
    fun restorePending(receiptId: String): Int

    /** Permanently quarantine malformed envelopes so they cannot starve valid messages. */
    @Query("UPDATE olm_inbound_receipts SET status = 'REJECTED', processedAtMillis = NULL WHERE receiptId = :receiptId AND status IN ('PENDING', 'WAITING_FOR_DEVICE')")
    fun markRejected(receiptId: String): Int

    /** Committed receipts are finalized only under the account that received them. */
    @Query("SELECT * FROM olm_inbound_receipts WHERE recipientUserId = :recipientUserId AND status = 'PROCESSED' ORDER BY processedAtMillis ASC, receiptId ASC LIMIT :limit")
    fun getAwaitingFinalization(recipientUserId: String, limit: Int): List<OlmInboundReceiptEntity>

    @Query("UPDATE olm_inbound_receipts SET status = 'FINALIZED' WHERE receiptId = :receiptId AND status = 'PROCESSED'")
    fun markFinalized(receiptId: String): Int

    @Query("UPDATE olm_inbound_receipts SET status = 'PROCESSED', processedAtMillis = :processedAtMillis WHERE receiptId = :receiptId AND status = 'PENDING'")
    fun markProcessed(receiptId: String, processedAtMillis: Long): Int
}
