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
    fun transientIdentityReadFailureDefersRetirement() {
        assertEquals(
            EncryptedOlmDeviceScopeDecision.DEFER_LOOKUP,
            evaluateEncryptedOlmDeviceScope(
                scheduledSenderDeviceId = "device-a",
                activeDeviceId = null,
                lookupFailed = true,
            ),
        )
    }

    @Test
    fun deferredDeviceIdentityLookupRetriesInsteadOfDiscardingTheOneShotWake() {
        assertEquals(
            EncryptedOlmDeviceScopeAction.RETRY,
            encryptedOlmDeviceScopeAction(EncryptedOlmDeviceScopeDecision.DEFER_LOOKUP),
        )
    }

    @Test
    fun activeDeviceIdentityContinuesTheWorker() {
        assertEquals(
            EncryptedOlmDeviceScopeAction.CONTINUE,
            encryptedOlmDeviceScopeAction(EncryptedOlmDeviceScopeDecision.KEEP_ACTIVE),
        )
    }

    @Test
    fun confirmedRetiredIdentityCancelsOnlyTheStaleScope() {
        assertEquals(
            EncryptedOlmDeviceScopeAction.RETIRE,
            encryptedOlmDeviceScopeAction(EncryptedOlmDeviceScopeDecision.RETIRE_STALE_SCOPE),
        )
    }

    @Test
    fun confirmedMissingIdentityRetiresOnlyTheStaleDeviceScope() {
        assertEquals(
            EncryptedOlmDeviceScopeDecision.RETIRE_STALE_SCOPE,
            evaluateEncryptedOlmDeviceScope(
                scheduledSenderDeviceId = "device-a",
                activeDeviceId = null,
                lookupFailed = false,
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
        assertEquals(
            EncryptedOlmDeviceScopeDecision.KEEP_ACTIVE,
            evaluateEncryptedOlmDeviceScope("device-a", "device-a", lookupFailed = false),
        )
        assertEquals(
            EncryptedOlmDeviceScopeDecision.RETIRE_STALE_SCOPE,
            evaluateEncryptedOlmDeviceScope("device-a", "device-b", lookupFailed = false),
        )
        assertEquals(
            EncryptedOlmDeviceScopeDecision.RETIRE_STALE_SCOPE,
            evaluateEncryptedOlmDeviceScope("device-a", null, lookupFailed = false),
        )
        assertEquals(
            EncryptedOlmDeviceScopeDecision.DEFER_LOOKUP,
            evaluateEncryptedOlmDeviceScope("device-a", null, lookupFailed = true),
        )
    }
}
