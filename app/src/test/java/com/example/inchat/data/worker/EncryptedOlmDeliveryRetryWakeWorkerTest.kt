package com.example.inchat.data.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EncryptedOlmDeliveryRetryWakeWorkerTest {

    @Test
    fun retryDeadlinesHaveIndependentUniqueWorkNames() {
        val earlier = EncryptedOlmDeliveryRetryWakeWorker.workName("alice", "device-a", 1_000L)
        val later = EncryptedOlmDeliveryRetryWakeWorker.workName("alice", "device-a", 1_030L)

        assertNotEquals(earlier, later)
    }

    @Test
    fun identicalRetryDeadlinesCoalesceForTheSameDeviceScope() {
        val first = EncryptedOlmDeliveryRetryWakeWorker.workName("alice", "device-a", 1_000L)
        val duplicate = EncryptedOlmDeliveryRetryWakeWorker.workName("alice", "device-a", 1_000L)

        assertEquals(first, duplicate)
    }

    @Test
    fun retryDeadlinesRemainIsolatedByAccountAndDevice() {
        val owner = EncryptedOlmDeliveryRetryWakeWorker.workName("alice", "device-a", 1_000L)
        val otherOwner = EncryptedOlmDeliveryRetryWakeWorker.workName("bob", "device-a", 1_000L)
        val otherDevice = EncryptedOlmDeliveryRetryWakeWorker.workName("alice", "device-b", 1_000L)

        assertNotEquals(owner, otherOwner)
        assertNotEquals(owner, otherDevice)
    }
}
