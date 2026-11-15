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

    override fun claimNextDue(
        nowMillis: Long,
        leaseDurationMillis: Long,
        maxAttempts: Int,
    ): QueuedOlmDeviceDelivery? = database.runInTransaction(Callable {
        dao.failExhaustedDue(nowMillis, maxAttempts)
        val candidate = dao.findNextDue(nowMillis) ?: return@Callable null
        val leaseUntil = safeAdd(nowMillis, leaseDurationMillis)
        if (dao.leaseIfDue(candidate.deliveryId, nowMillis, leaseUntil, maxAttempts) != 1) {
            return@Callable null
        }
        dao.getByDeliveryId(candidate.deliveryId)?.toDelivery()
    })

    override fun updateInFlight(
        deliveryId: String,
        messageId: String,
        update: (QueuedOlmDeviceDelivery) -> QueuedOlmDeviceDelivery,
    ): Boolean = database.runInTransaction(Callable {
        val current = dao.getByDeliveryId(deliveryId) ?: return@Callable false
        if (current.messageId != messageId || current.status != OlmDeliveryStatus.IN_FLIGHT.name) {
            return@Callable false
        }
        val changed = update(current.toDelivery())
        require(changed.deliveryId == deliveryId && changed.messageId == messageId) {
            "Queue transition cannot change delivery identity"
        }
        dao.updateInFlight(
            deliveryId = deliveryId,
            messageId = messageId,
            status = changed.status.name,
            nextAttemptAtMillis = changed.nextAttemptAtMillis,
            leaseUntilMillis = changed.leaseUntilMillis,
            lastError = changed.lastError,
        ) == 1
    })

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
