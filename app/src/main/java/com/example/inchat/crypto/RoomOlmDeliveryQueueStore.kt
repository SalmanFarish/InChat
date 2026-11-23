package com.example.inchat.crypto

import androidx.room.RoomDatabase
import java.util.concurrent.Callable

/**
 * Durable Room implementation. All claim and transition read/modify/write
 * sequences execute inside SQLite transactions, so competing workers cannot
 * lease the same row concurrently.
 *
 * Invoke from a background dispatcher: this synchronous adapter intentionally
 * keeps the queue contract small and must never run on the Android main thread.
 */
private fun EncryptedDirectOutgoingCommitEntity.matchesImmutableIdentity(
    candidate: DirectOutgoingMessageCommit,
): Boolean =
    ownerUserId == candidate.ownerUserId &&
        conversationId == candidate.conversationId &&
        conversationPeerUserId == candidate.conversationPeerUserId &&
        messageId == candidate.messageId &&
        senderDeviceId == candidate.senderDeviceId &&
        sentAtMillis == candidate.sentAtMillis

internal class RoomOlmDeliveryQueueStore(
    private val database: RoomDatabase,
    private val dao: OlmDeliveryQueueDao,
) : OlmDeliveryQueueStore {
    override fun insertIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>): Int {
        if (deliveries.isEmpty()) return 0
        return database.runInTransaction(Callable {
            dao.insertIfAbsent(deliveries.map { it.toEntity() }).count { it != -1L }
        })
    }

    override fun insertGroupMessageIfAbsent(
        deliveries: List<QueuedOlmDeviceDelivery>,
        commit: GroupOutgoingMessageCommit,
    ): Int {
        require(deliveries.isNotEmpty()) { "A committed group message requires device deliveries" }
        return database.runInTransaction(Callable {
            val existing = dao.getOutgoingGroupCommit(
                commit.ownerUserId,
                commit.groupId,
                commit.messageId,
            )
            if (existing != null) {
                require(existing == commit.toEntity()) {
                    "Outgoing group message ID is already committed with different metadata"
                }
                // The original transaction committed the complete fan-out. Some
                // rows may since have been acknowledged and deleted; never recreate
                // them by replaying the producer after a crash.
                return@Callable 0
            }

            check(dao.insertOutgoingGroupCommitIfAbsent(commit.toEntity()) != -1L) {
                "Outgoing group commit marker could not be inserted"
            }
            val inserted = dao.insertIfAbsent(deliveries.map { it.toEntity() })
                .count { it != -1L }
            check(inserted == deliveries.size) {
                "Outgoing group fan-out conflicts with pre-existing delivery rows"
            }
            inserted
        })
    }

    override fun hasDirectMessageCommit(commit: DirectOutgoingMessageCommit): Boolean {
        val existing = dao.getOutgoingDirectCommit(commit.ownerUserId, commit.conversationId, commit.messageId) ?: return false
        require(existing.matchesImmutableIdentity(commit)) {
            "Outgoing direct message ID is already committed with different metadata"
        }
        return true
    }

    override fun insertDirectMessageIfAbsent(deliveries: List<QueuedOlmDeviceDelivery>, commit: DirectOutgoingMessageCommit): Int {
        require(deliveries.isNotEmpty()) { "A committed direct message requires device deliveries" }
        return database.runInTransaction(Callable {
            val existing = dao.getOutgoingDirectCommit(commit.ownerUserId, commit.conversationId, commit.messageId)
            if (existing != null) {
                require(existing.matchesImmutableIdentity(commit)) {
                    "Outgoing direct message ID is already committed with different metadata"
                }
                return@Callable 0
            }
            check(dao.insertOutgoingDirectCommitIfAbsent(commit.toEntity()) != -1L) { "Outgoing direct message commit marker could not be inserted" }
            val inserted = dao.insertIfAbsent(deliveries.map { it.toEntity() }).count { it != -1L }
            check(inserted == deliveries.size) { "Outgoing direct fan-out conflicts with pre-existing delivery rows" }
            inserted
        })
    }

    override fun hasMatchingDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean =
        dao.hasMatchingDelivery(
            deliveryId = acknowledgement.deliveryId,
            messageId = acknowledgement.messageId,
            recipientUserId = acknowledgement.recipientUserId,
            recipientDeviceId = acknowledgement.recipientDeviceId,
            ownerUserId = acknowledgement.senderUserId,
            senderDeviceId = acknowledgement.senderDeviceId,
        )

    override fun removeAcknowledgedDelivery(acknowledgement: OlmDeliveryAcknowledgement): Boolean =
        database.runInTransaction(Callable {
            dao.removeAcknowledgedDelivery(
                deliveryId = acknowledgement.deliveryId,
                messageId = acknowledgement.messageId,
                recipientUserId = acknowledgement.recipientUserId,
                recipientDeviceId = acknowledgement.recipientDeviceId,
                ownerUserId = acknowledgement.senderUserId,
                senderDeviceId = acknowledgement.senderDeviceId,
            ) == 1
        })

    override fun claimNextDue(
        ownerUserId: String,
        senderDeviceId: String,
        nowMillis: Long,
        leaseDurationMillis: Long,
        maxAttempts: Int,
    ): QueuedOlmDeviceDelivery? = database.runInTransaction(Callable {
        dao.failExhaustedDue(ownerUserId, senderDeviceId, nowMillis, maxAttempts)
        val candidate = dao.findNextDue(ownerUserId, senderDeviceId, nowMillis) ?: return@Callable null
        val leaseUntil = safeAdd(nowMillis, leaseDurationMillis)
        if (dao.leaseIfDue(candidate.deliveryId, ownerUserId, senderDeviceId, nowMillis, leaseUntil, maxAttempts) != 1) {
            return@Callable null
        }
        dao.getByDeliveryId(candidate.deliveryId)?.toDelivery()
    })

    override fun retryFailed(
        deliveryId: String,
        messageId: String,
        ownerUserId: String,
        senderDeviceId: String,
        nowMillis: Long,
    ): Boolean = database.runInTransaction(Callable {
        dao.retryFailed(
            deliveryId = deliveryId,
            messageId = messageId,
            ownerUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            nowMillis = nowMillis,
        ) == 1
    })

    override fun updateInFlight(
        deliveryId: String,
        messageId: String,
        ownerUserId: String,
        senderDeviceId: String,
        expectedAttemptCount: Int,
        expectedLeaseUntilMillis: Long,
        nowMillis: Long,
        update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
    ): Boolean = database.runInTransaction(Callable {
        val current = dao.getByDeliveryId(deliveryId) ?: return@Callable false
        if (current.messageId != messageId ||
            current.ownerUserId != ownerUserId ||
            current.senderDeviceId != senderDeviceId ||
            current.status != OlmDeliveryStatus.IN_FLIGHT.name ||
            current.attemptCount != expectedAttemptCount ||
            current.leaseUntilMillis != expectedLeaseUntilMillis ||
            current.leaseUntilMillis <= nowMillis
        ) {
            return@Callable false
        }
        val changed = update(current.toDelivery())
        require(changed.deliveryId == deliveryId && changed.messageId == messageId) {
            "Queue transition cannot change delivery identity"
        }
        dao.updateInFlight(
            deliveryId = deliveryId,
            messageId = messageId,
            ownerUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            expectedAttemptCount = expectedAttemptCount,
            expectedLeaseUntilMillis = expectedLeaseUntilMillis,
            nowMillis = nowMillis,
            status = changed.status.name,
            nextAttemptAtMillis = changed.nextAttemptAtMillis,
            leaseUntilMillis = changed.leaseUntilMillis,
            lastError = changed.lastError,
        ) == 1
    })

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
