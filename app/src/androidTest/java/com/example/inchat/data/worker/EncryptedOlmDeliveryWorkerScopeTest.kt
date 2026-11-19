package com.example.inchat.data.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedOlmDeliveryWorkerScopeTest {
    @Test
    fun accountScopeWaitsDuringAuthRestoreWithoutRetiringWork() {
        assertEquals(
            EncryptedOlmAuthScopeDecision.WAIT_FOR_AUTH,
            evaluateEncryptedOlmAuthScope(
                scheduledOwnerUserId = "alice",
                authenticatedUserId = null,
            ),
        )
    }

    @Test
    fun accountScopeRetiresWorkAfterAccountSwitch() {
        assertEquals(
            EncryptedOlmAuthScopeDecision.RETIRE_STALE_SCOPE,
            evaluateEncryptedOlmAuthScope(
                scheduledOwnerUserId = "alice",
                authenticatedUserId = "bob",
            ),
        )
    }

    @Test
    fun matchingAccountContinuesToDeviceIdentityValidation() {
        assertEquals(
            EncryptedOlmAuthScopeDecision.CHECK_DEVICE,
            evaluateEncryptedOlmAuthScope(
                scheduledOwnerUserId = "alice",
                authenticatedUserId = "alice",
            ),
        )
    }

    @Test
    fun onlyTheExactActiveDeviceKeepsItsSchedule() {
        assertTrue(isEncryptedOlmScheduledDeviceActive("device-a", "device-a"))
        assertFalse(isEncryptedOlmScheduledDeviceActive("device-a", "device-b"))
        assertFalse(isEncryptedOlmScheduledDeviceActive("device-a", null))
    }
}
