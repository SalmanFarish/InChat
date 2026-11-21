package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Queue identity/status only; envelopes are deliberately excluded from recovery scans. */
data class OlmDeliveryQueueTargetState(
    val recipientUserId: String,
    val recipientDeviceId: String,
    val status: String,
)

@Dao
interface OlmDeliveryQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(rows: List<OlmDeviceDeliveryEntity>): List<Long>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM olm_device_deliveries " +
            "WHERE deliveryId = :deliveryId AND messageId = :messageId " +
            "AND recipientUserId = :recipientUserId AND recipientDeviceId = :recipientDeviceId " +
            "AND ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId)"
    )
    fun hasMatchingDelivery(
        deliveryId: String,
        messageId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        ownerUserId: String,
        senderDeviceId: String,
    ): Boolean

    @Query(
        "DELETE FROM olm_device_deliveries WHERE deliveryId = :deliveryId AND messageId = :messageId " +
            "AND recipientUserId = :recipientUserId AND recipientDeviceId = :recipientDeviceId " +
            "AND ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId"
    )
    fun removeAcknowledgedDelivery(
        deliveryId: String,
        messageId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        ownerUserId: String,
        senderDeviceId: String,
    ): Int

    /**
     * Enumerate durable targets for one group-key distribution. messageId is
     * the immutable distribution ID; no ciphertext is returned to the scanner.
     */
    @Query(
        "SELECT recipientUserId, recipientDeviceId, status FROM olm_device_deliveries " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND messageId = :distributionId ORDER BY recipientUserId ASC, recipientDeviceId ASC"
    )
    fun getTargetStatesForDistribution(
        ownerUserId: String,
        senderDeviceId: String,
        distributionId: String,
    ): List<OlmDeliveryQueueTargetState>

    @Query("SELECT * FROM olm_device_deliveries WHERE deliveryId = :deliveryId LIMIT 1")
    fun getByDeliveryId(deliveryId: String): OlmDeviceDeliveryEntity?


    @Query(
        "SELECT status FROM olm_device_deliveries " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND recipientUserId = :recipientUserId AND messageId = :messageId " +
            "AND status IN ('QUEUED', 'IN_FLIGHT', 'UPLOADED', 'FAILED')"
    )
    fun observeDeliveryStatuses(
        ownerUserId: String,
        senderDeviceId: String,
        recipientUserId: String,
        messageId: String,
    ): Flow<List<String>>

    @Query(
        "SELECT * FROM olm_device_deliveries " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId AND " +
            "((status IN ('QUEUED', 'UPLOADED') AND nextAttemptAtMillis <= :nowMillis) " +
            "OR (status = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis)) " +
            "ORDER BY CASE WHEN status IN ('QUEUED', 'UPLOADED') THEN nextAttemptAtMillis ELSE leaseUntilMillis END ASC, deliveryId ASC " +
            "LIMIT 1"
    )
    fun findNextDue(ownerUserId: String, senderDeviceId: String, nowMillis: Long): OlmDeviceDeliveryEntity?

    @Query(
        "UPDATE olm_device_deliveries SET status = 'IN_FLIGHT', " +
            "attemptCount = attemptCount + 1, leaseUntilMillis = :leaseUntilMillis " +
            "WHERE deliveryId = :deliveryId AND ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND attemptCount < :maxAttempts AND " +
            "((status IN ('QUEUED', 'UPLOADED') AND nextAttemptAtMillis <= :nowMillis) OR " +
            "(status = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis))"
    )
    fun leaseIfDue(
        deliveryId: String,
        ownerUserId: String,
        senderDeviceId: String,
        nowMillis: Long,
        leaseUntilMillis: Long,
        maxAttempts: Int,
    ): Int

    @Query(
        "UPDATE olm_device_deliveries SET status = 'FAILED', leaseUntilMillis = 0, " +
            "lastError = 'attempt_limit_reached' " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND status IN ('QUEUED', 'UPLOADED', 'IN_FLIGHT') AND attemptCount >= :maxAttempts " +
            "AND ((status IN ('QUEUED', 'UPLOADED') AND nextAttemptAtMillis <= :nowMillis) OR " +
            "(status = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis))"
    )
    fun failExhaustedDue(ownerUserId: String, senderDeviceId: String, nowMillis: Long, maxAttempts: Int): Int

    @Query(
        "UPDATE olm_device_deliveries SET status = :status, nextAttemptAtMillis = :nextAttemptAtMillis, " +
            "leaseUntilMillis = :leaseUntilMillis, lastError = :lastError " +
            "WHERE deliveryId = :deliveryId AND messageId = :messageId " +
            "AND ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId AND status = 'IN_FLIGHT' " +
            "AND attemptCount = :expectedAttemptCount AND leaseUntilMillis = :expectedLeaseUntilMillis " +
            "AND leaseUntilMillis > :nowMillis"
    )
    fun updateInFlight(
        deliveryId: String,
        messageId: String,
        ownerUserId: String,
        senderDeviceId: String,
        expectedAttemptCount: Int,
        expectedLeaseUntilMillis: Long,
        nowMillis: Long,
        status: String,
        nextAttemptAtMillis: Long,
        leaseUntilMillis: Long,
        lastError: String?,
    ): Int
}
