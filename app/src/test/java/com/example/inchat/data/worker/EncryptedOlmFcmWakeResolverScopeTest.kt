package com.example.inchat.data.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedOlmFcmWakeResolverScopeTest {
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
