package com.example.inchat.data.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedOlmFcmWakeResolverScopeTest {
    @Test
    fun coldStartWakeWaitsForAuthThenUsesOnlyRestoredAccount() {
        val waiting = resolveEncryptedOlmFcmWakeOwner(
            scheduledOwnerUserId = null,
            authenticatedUserId = null,
            resolveOwnerFromAuthenticatedSession = true,
        )
        assertEquals(EncryptedOlmFcmWakeAuthDecision.WAIT_FOR_AUTH, waiting.decision)
        assertEquals(null, waiting.ownerUserId)

        val resolved = resolveEncryptedOlmFcmWakeOwner(
            scheduledOwnerUserId = null,
            authenticatedUserId = "alice",
            resolveOwnerFromAuthenticatedSession = true,
        )
        assertEquals(EncryptedOlmFcmWakeAuthDecision.RESOLVED, resolved.decision)
        assertEquals("alice", resolved.ownerUserId)
    }

    @Test
    fun coldStartWakeRejectsAnyPayloadSuppliedAccount() {
        val invalid = resolveEncryptedOlmFcmWakeOwner(
            scheduledOwnerUserId = "attacker",
            authenticatedUserId = "alice",
            resolveOwnerFromAuthenticatedSession = true,
        )
        assertEquals(EncryptedOlmFcmWakeAuthDecision.RETIRE, invalid.decision)
        assertEquals(null, invalid.ownerUserId)
    }

    @Test
    fun accountScopedWakeWaitsForAuthAndRetiresAfterAccountSwitch() {
        val waiting = resolveEncryptedOlmFcmWakeOwner(
            scheduledOwnerUserId = "alice",
            authenticatedUserId = null,
            resolveOwnerFromAuthenticatedSession = false,
        )
        assertEquals(EncryptedOlmFcmWakeAuthDecision.WAIT_FOR_AUTH, waiting.decision)

        val stale = resolveEncryptedOlmFcmWakeOwner(
            scheduledOwnerUserId = "alice",
            authenticatedUserId = "bob",
            resolveOwnerFromAuthenticatedSession = false,
        )
        assertEquals(EncryptedOlmFcmWakeAuthDecision.RETIRE, stale.decision)
    }
    @Test
    fun acceptsInboxWakeForTheAuthenticatedAccountAndExistingDevice() {
        assertTrue(
            isValidEncryptedOlmFcmWakeScope(
                ownerUserId = "alice",
                authenticatedUserId = "alice",
                localDeviceId = "alice-phone",
                wakeKind = EncryptedOlmFcmWakeKind.INBOX,
                expectedSenderDeviceId = null,
            ),
        )
    }

    @Test
    fun acceptsAcknowledgementWakeOnlyForItsExpectedSenderDevice() {
        assertTrue(
            isValidEncryptedOlmFcmWakeScope(
                ownerUserId = "alice",
                authenticatedUserId = "alice",
                localDeviceId = "alice-phone",
                wakeKind = EncryptedOlmFcmWakeKind.ACKNOWLEDGEMENT,
                expectedSenderDeviceId = "alice-phone",
            ),
        )
        assertFalse(
            isValidEncryptedOlmFcmWakeScope(
                ownerUserId = "alice",
                authenticatedUserId = "alice",
                localDeviceId = "alice-tablet",
                wakeKind = EncryptedOlmFcmWakeKind.ACKNOWLEDGEMENT,
                expectedSenderDeviceId = "alice-phone",
            ),
        )
    }

    @Test
    fun rejectsStaleAccountMissingDeviceAndUnexpectedInboxDeviceScope() {
        assertFalse(
            isValidEncryptedOlmFcmWakeScope(
                ownerUserId = "alice",
                authenticatedUserId = "bob",
                localDeviceId = "alice-phone",
                wakeKind = EncryptedOlmFcmWakeKind.INBOX,
                expectedSenderDeviceId = null,
            ),
        )
        assertFalse(
            isValidEncryptedOlmFcmWakeScope(
                ownerUserId = "alice",
                authenticatedUserId = "alice",
                localDeviceId = null,
                wakeKind = EncryptedOlmFcmWakeKind.INBOX,
                expectedSenderDeviceId = null,
            ),
        )
        assertFalse(
            isValidEncryptedOlmFcmWakeScope(
                ownerUserId = "alice",
                authenticatedUserId = "alice",
                localDeviceId = "alice-phone",
                wakeKind = EncryptedOlmFcmWakeKind.INBOX,
                expectedSenderDeviceId = "unexpected-device",
            ),
        )
    }
}
