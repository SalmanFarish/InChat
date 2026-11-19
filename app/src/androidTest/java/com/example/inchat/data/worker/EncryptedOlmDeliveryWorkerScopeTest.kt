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
    fun latestAuthLookupCanApplyForTheCurrentAccount() {
        assertTrue(
            isEncryptedOlmAuthResolutionCurrent(
                requestGeneration = 7L,
                currentGeneration = 7L,
                requestedOwnerUserId = "alice",
                authenticatedUserId = "alice",
            ),
        )
    }

    @Test
    fun lateAuthLookupCannotRestoreWorkAfterSignOut() {
        assertFalse(
            isEncryptedOlmAuthResolutionCurrent(
                requestGeneration = 6L,
                currentGeneration = 7L,
                requestedOwnerUserId = "alice",
                authenticatedUserId = null,
            ),
        )
    }

    @Test
    fun lateAuthLookupCannotRestorePreviousAccountAfterSwitch() {
        assertFalse(
            isEncryptedOlmAuthResolutionCurrent(
                requestGeneration = 6L,
                currentGeneration = 7L,
                requestedOwnerUserId = "alice",
                authenticatedUserId = "bob",
            ),
        )
    }

    @Test
    fun oldLookupCannotApplyAfterSignOutAndSignInToSameAccount() {
        assertFalse(
            isEncryptedOlmAuthResolutionCurrent(
                requestGeneration = 6L,
                currentGeneration = 8L,
                requestedOwnerUserId = "alice",
                authenticatedUserId = "alice",
            ),
        )
    }

    @Test
    fun sameGenerationStillRejectsUnexpectedAuthenticatedAccount() {
        assertFalse(
            isEncryptedOlmAuthResolutionCurrent(
                requestGeneration = 7L,
                currentGeneration = 7L,
                requestedOwnerUserId = "alice",
                authenticatedUserId = "bob",
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
