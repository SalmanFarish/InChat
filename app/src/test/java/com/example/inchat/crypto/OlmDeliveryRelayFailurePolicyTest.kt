package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmDeliveryRelayFailurePolicyTest {
    @Test
    fun authenticationTimeoutRateLimitAndServerFailuresRemainRetryable() {
        listOf(401, 408, 429, 500, 503, 599).forEach { status ->
            assertTrue("HTTP $status should be retried", OlmDeliveryRelayFailurePolicy.isRetryableHttpStatus(status))
        }
    }

    @Test
    fun permanentClientFailuresDoNotConsumeTheTransientRetryBudget() {
        listOf(400, 403, 404, 409, 410, 422).forEach { status ->
            assertFalse("HTTP $status must not be retried", OlmDeliveryRelayFailurePolicy.isRetryableHttpStatus(status))
        }
    }

    @Test
    fun permanentMailboxFailuresKeepUsefulTerminalReasons() {
        assertEquals("relay_delivery_forbidden", OlmDeliveryRelayFailurePolicy.permanentErrorCode(403))
        assertEquals("relay_device_unavailable", OlmDeliveryRelayFailurePolicy.permanentErrorCode(404))
        assertEquals("relay_delivery_expired", OlmDeliveryRelayFailurePolicy.permanentErrorCode(410))
        assertEquals("relay_rejected_delivery", OlmDeliveryRelayFailurePolicy.permanentErrorCode(409))
    }
}
