package com.example.inchat.data.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EncryptedOlmAcknowledgementFollowUpTest {
    @Test
    fun schedulesOnlyOneFollowUpForConcurrentAcknowledgementCallbacks() {
        val scheduleCalls = AtomicInteger()
        val followUp = EncryptedOlmAcknowledgementFollowUp {
            scheduleCalls.incrementAndGet()
        }
        val workerCount = 8
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workerCount)

        try {
            val tasks = (0 until 32).map {
                executor.submit {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) {
                        "Timed out waiting for concurrent ACK callbacks to start"
                    }
                    followUp.scheduleOnce()
                }
            }

            assertTrue("All ACK callbacks should be ready before release", ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, scheduleCalls.get())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun failedEnqueueDoesNotMarkFollowUpAsScheduledAndCanBeRetried() {
        var scheduleAttempts = 0
        val followUp = EncryptedOlmAcknowledgementFollowUp {
            scheduleAttempts++
            if (scheduleAttempts == 1) {
                throw IllegalStateException("temporary WorkManager enqueue failure")
            }
        }

        val firstFailure = runCatching { followUp.scheduleOnce() }.exceptionOrNull()
        assertTrue(firstFailure is IllegalStateException)

        followUp.scheduleOnce()
        followUp.scheduleOnce()

        assertEquals(2, scheduleAttempts)
    }
}
