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

/** Metadata-only delivery state projected for one direct message. */
data class OlmMessageDeliveryStatusState(
    val messageId: String,
    val status: String,
)

@Dao
interface OlmDeliveryQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(rows: List<OlmDeviceDeliveryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOutgoingDirectCommitIfAbsent(commit: EncryptedDirectOutgoingCommitEntity): Long

    @Query("SELECT * FROM encrypted_direct_outgoing_commits WHERE ownerUserId = :ownerUserId AND conversationId = :conversationId AND messageId = :messageId LIMIT 1")
    fun getOutgoingDirectCommit(ownerUserId: String, conversationId: String, messageId: String): EncryptedDirectOutgoingCommitEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertOutgoingGroupCommitIfAbsent(commit: EncryptedGroupOutgoingCommitEntity): Long

    @Query(
        "SELECT * FROM encrypted_group_outgoing_commits " +
            "WHERE ownerUserId = :ownerUserId AND groupId = :groupId AND messageId = :messageId LIMIT 1"
    )
    fun getOutgoingGroupCommit(
        ownerUserId: String,
        groupId: String,
        messageId: String,
    ): EncryptedGroupOutgoingCommitEntity?

    /** Resolve committed group metadata without assuming message IDs are globally unique. */
    @Query(
        "SELECT * FROM encrypted_group_outgoing_commits " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND messageId = :messageId ORDER BY groupId ASC"
    )
    fun getOutgoingGroupCommitsForMessage(
        ownerUserId: String,
        senderDeviceId: String,
        messageId: String,
    ): List<EncryptedGroupOutgoingCommitEntity>

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
            "AND messageId = :distributionId AND groupId = :groupId " +
            "AND membershipEpoch = :membershipEpoch " +
            "ORDER BY recipientUserId ASC, recipientDeviceId ASC"
    )
    fun getTargetStatesForDistribution(
        ownerUserId: String,
        senderDeviceId: String,
        distributionId: String,
        groupId: String,
        membershipEpoch: Long,
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
        """
        SELECT * FROM olm_device_deliveries
        WHERE ownerUserId = :ownerUserId
          AND senderDeviceId = :senderDeviceId
          AND recipientUserId = :recipientUserId
          AND conversationId = :conversationId
          AND conversationPeerUserId = :recipientUserId
          AND groupId IS NULL
          AND membershipEpoch IS NULL
          AND recipientDeviceId IN (:trustedRecipientDeviceIds)
          AND status = 'FAILED'
        ORDER BY messageId ASC, recipientDeviceId ASC
        """,
    )
    fun getFailedDirectDeliveriesForTrustedDevices(
        ownerUserId: String,
        senderDeviceId: String,
        recipientUserId: String,
        conversationId: String,
        trustedRecipientDeviceIds: List<String>,
    ): List<OlmDeviceDeliveryEntity>

    @Query(
        """
        SELECT messageId, status FROM olm_device_deliveries
        WHERE ownerUserId = :ownerUserId
          AND recipientUserId = :recipientUserId
          AND conversationId = :conversationId
          AND conversationPeerUserId = :recipientUserId
          AND groupId IS NULL
          AND membershipEpoch IS NULL
          AND status IN ('QUEUED', 'IN_FLIGHT', 'UPLOADED', 'FAILED')
        ORDER BY messageId ASC
        """,
    )
    fun observeConversationDeliveryStatuses(
        ownerUserId: String,
        recipientUserId: String,
        conversationId: String,
    ): Flow<List<OlmMessageDeliveryStatusState>>

    @Query(
        "SELECT MIN(CASE WHEN status = 'IN_FLIGHT' THEN leaseUntilMillis ELSE nextAttemptAtMillis END) " +
            "FROM olm_device_deliveries WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND status IN ('QUEUED', 'UPLOADED', 'IN_FLIGHT') " +
            "AND (CASE WHEN status = 'IN_FLIGHT' THEN leaseUntilMillis ELSE nextAttemptAtMillis END) > :nowMillis"
    )
    fun findNextScheduledRetryAtMillis(
        ownerUserId: String,
        senderDeviceId: String,
        nowMillis: Long,
    ): Long?

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
        "SELECT * FROM olm_device_deliveries " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND messageId = :messageId AND " +
            "((status IN ('QUEUED', 'UPLOADED') AND nextAttemptAtMillis <= :nowMillis) " +
            "OR (status = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis)) " +
            "ORDER BY CASE WHEN status IN ('QUEUED', 'UPLOADED') THEN nextAttemptAtMillis ELSE leaseUntilMillis END ASC, deliveryId ASC " +
            "LIMIT 1"
    )
    fun findNextDueForMessage(
        ownerUserId: String,
        senderDeviceId: String,
        messageId: String,
        nowMillis: Long,
    ): OlmDeviceDeliveryEntity?

    @Query(
        "UPDATE olm_device_deliveries SET status = 'FAILED', leaseUntilMillis = 0, " +
            "lastError = 'attempt_limit_reached' " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND messageId = :messageId " +
            "AND status IN ('QUEUED', 'UPLOADED', 'IN_FLIGHT') AND attemptCount >= :maxAttempts " +
            "AND ((status IN ('QUEUED', 'UPLOADED') AND nextAttemptAtMillis <= :nowMillis) OR " +
            "(status = 'IN_FLIGHT' AND leaseUntilMillis <= :nowMillis))"
    )
    fun failExhaustedDueForMessage(
        ownerUserId: String,
        senderDeviceId: String,
        messageId: String,
        nowMillis: Long,
        maxAttempts: Int,
    ): Int

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
        "UPDATE olm_device_deliveries SET status = 'QUEUED', attemptCount = 0, " +
            "nextAttemptAtMillis = :nowMillis, leaseUntilMillis = 0, lastError = NULL " +
            "WHERE deliveryId = :deliveryId AND messageId = :messageId " +
            "AND ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND status = 'FAILED'"
    )
    fun retryFailed(
        deliveryId: String,
        messageId: String,
        ownerUserId: String,
        senderDeviceId: String,
        nowMillis: Long,
    ): Int

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
