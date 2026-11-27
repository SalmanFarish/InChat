package com.example.inchat.data.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundInboxReconnectPolicyTest {
    @Test
    fun acknowledgementWakeRequiresExactForegroundAccountAndDeviceScope() {
        assertTrue(
            isForegroundAcknowledgementWakeInScope(
                isForeground = true,
                activeOwnerUserId = "alice",
                expectedOwnerUserId = "alice",
                activeDeviceId = "alice-phone",
                expectedDeviceId = "alice-phone",
                authenticatedUserId = "alice",
            ),
        )
        assertFalse(
            isForegroundAcknowledgementWakeInScope(
                isForeground = false,
                activeOwnerUserId = "alice",
                expectedOwnerUserId = "alice",
                activeDeviceId = "alice-phone",
                expectedDeviceId = "alice-phone",
                authenticatedUserId = "alice",
            ),
        )
        assertFalse(
            isForegroundAcknowledgementWakeInScope(
                isForeground = true,
                activeOwnerUserId = "alice",
                expectedOwnerUserId = "alice",
                activeDeviceId = "alice-tablet",
                expectedDeviceId = "alice-phone",
                authenticatedUserId = "alice",
            ),
        )
        assertFalse(
            isForegroundAcknowledgementWakeInScope(
                isForeground = true,
                activeOwnerUserId = "alice",
                expectedOwnerUserId = "alice",
                activeDeviceId = "alice-phone",
                expectedDeviceId = "alice-phone",
                authenticatedUserId = "bob",
            ),
        )
    }

    @Test
    fun reconnectDelayStartsQuicklyAndDoubles() {
        assertEquals(1_000L, foregroundInboxReconnectDelayMillis(1))
        assertEquals(2_000L, foregroundInboxReconnectDelayMillis(2))
        assertEquals(4_000L, foregroundInboxReconnectDelayMillis(3))
        assertEquals(8_000L, foregroundInboxReconnectDelayMillis(4))
        assertEquals(16_000L, foregroundInboxReconnectDelayMillis(5))
    }

    @Test
    fun reconnectDelayIsCappedAndInvalidAttemptUsesFirstDelay() {
        assertEquals(1_000L, foregroundInboxReconnectDelayMillis(0))
        assertEquals(30_000L, foregroundInboxReconnectDelayMillis(6))
        assertEquals(30_000L, foregroundInboxReconnectDelayMillis(100))
    }
}
