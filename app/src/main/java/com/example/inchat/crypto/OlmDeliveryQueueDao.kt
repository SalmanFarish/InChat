package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface OlmDeliveryQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(rows: List<OlmDeviceDeliveryEntity>): List<Long>

    @Query("SELECT * FROM olm_device_deliveries WHERE deliveryId = :deliveryId LIMIT 1")
    fun getByDeliveryId(deliveryId: String): OlmDeviceDeliveryEntity?

    @Query(
        "SELECT * FROM olm_device_deliveries " +
            "WHERE (status = 'QUEUED' AND nextAttemptAtMillis <= :nowMillis) " +
            "OR (status = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis) " +
            "ORDER BY CASE WHEN status = 'QUEUED' THEN nextAttemptAtMillis ELSE leaseUntilMillis END ASC, deliveryId ASC " +
            "LIMIT 1"
    )
    fun findNextDue(nowMillis: Long): OlmDeviceDeliveryEntity?

    @Query(
        "UPDATE olm_device_deliveries SET status = 'IN_FLIGHT', " +
            "attemptCount = attemptCount + 1, leaseUntilMillis = :leaseUntilMillis " +
            "WHERE deliveryId = :deliveryId AND attemptCount < :maxAttempts AND " +
            "((status = 'QUEUED' AND nextAttemptAtMillis <= :nowMillis) OR " +
            "(status = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis))"
    )
    fun leaseIfDue(
        deliveryId: String,
        nowMillis: Long,
        leaseUntilMillis: Long,
        maxAttempts: Int,
    ): Int

    @Query(
        "UPDATE olm_device_deliveries SET status = 'FAILED', leaseUntilMillis = 0, " +
            "lastError = 'attempt_limit_reached' " +
            "WHERE status IN ('QUEUED', 'IN_FLIGHT') AND attemptCount >= :maxAttempts " +
            "AND ((status = 'QUEUED' AND nextAttemptAtMillis <= :nowMillis) OR " +
            "(status = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis))"
    )
    fun failExhaustedDue(nowMillis: Long, maxAttempts: Int): Int

    @Query(
        "UPDATE olm_device_deliveries SET status = :status, nextAttemptAtMillis = :nextAttemptAtMillis, " +
            "leaseUntilMillis = :leaseUntilMillis, lastError = :lastError " +
            "WHERE deliveryId = :deliveryId AND messageId = :messageId AND status = 'IN_FLIGHT'"
    )
    fun updateInFlight(
        deliveryId: String,
        messageId: String,
        status: String,
        nextAttemptAtMillis: Long,
        leaseUntilMillis: Long,
        lastError: String?,
    ): Int
}
