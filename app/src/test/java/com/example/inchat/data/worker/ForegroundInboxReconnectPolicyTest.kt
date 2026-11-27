package com.example.inchat.data.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundInboxReconnectPolicyTest {
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
