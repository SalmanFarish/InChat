package com.example.inchat.data.worker

/**
 * Coalesces ACK-triggered sender wake-ups within one worker pass.
 *
 * The schedule action is synchronous (WorkManager enqueue). Mark the request as
 * scheduled only after it succeeds so another ACK callback can retry a transient
 * enqueue failure. Synchronization is required because ACK reconciliation runs
 * several independent callbacks concurrently.
 */
internal class EncryptedOlmAcknowledgementFollowUp(
    private val schedule: () -> Unit,
) {
    private val lock = Any()
    private var scheduled = false

    fun scheduleOnce() {
        synchronized(lock) {
            if (!scheduled) {
                schedule()
                scheduled = true
            }
        }
    }
}
