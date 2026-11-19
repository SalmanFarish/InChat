package com.example.inchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasVerificationRelayCleanupTest {
    private val deliveryId = "a".repeat(64)

    @Test
    fun expiredEntryAtTheCurrentTimeIsEligibleForCleanup() {
        assertTrue(isExpiredSasRelayEntry(deliveryId, 1_000L, 1_000L))
    }

    @Test
    fun entryWithFutureExpiryIsKept() {
        assertFalse(isExpiredSasRelayEntry(deliveryId, 1_001L, 1_000L))
    }

    @Test
    fun entryWithMissingExpiryIsNotDeleted() {
        assertFalse(isExpiredSasRelayEntry(deliveryId, null, 1_000L))
    }

    @Test
    fun entryWithMalformedDeliveryKeyIsNotDeleted() {
        assertFalse(isExpiredSasRelayEntry("not-a-delivery-id", 500L, 1_000L))
    }

    @Test
    fun uppercaseHexDeliveryIdIsAccepted() {
        assertTrue(isExpiredSasRelayEntry("F".repeat(64), 500L, 1_000L))
    }
}
