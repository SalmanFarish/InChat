package com.example.inchat.crypto

import com.example.inchat.data.repository.CloudflareWorkerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class RetiredDeviceMailboxPurgerTest {

    @Test
    fun defaultPurgerDoesNotContactWorkerWhileMailboxTransportIsDisabled() = runBlocking {
        var purgeCalled = false
        val purger = RetiredDeviceMailboxPurger(
            purge = { _, _ -> purgeCalled = true },
        )

        assertFalse(CloudflareWorkerConfig.ENCRYPTED_OLM_MAILBOX_ENABLED)
        purger.purgeAfterRevocation("account-1", "revoked-device")

        assertFalse(purgeCalled)
    }

    @Test
    fun enabledPurgerUsesOnlyTheRevokedAccountAndDevice() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val purger = RetiredDeviceMailboxPurger(
            isEnabled = { true },
            purge = { accountUid, deviceId -> calls += accountUid to deviceId },
        )

        purger.purgeAfterRevocation("account-1", "revoked-device")

        assertEquals(listOf("account-1" to "revoked-device"), calls)
    }

    @Test
    fun failedPurgeIsSurfacedAndCanBeRetriedWithoutChangingTheRevocation() = runBlocking {
        val failure = IllegalStateException("Worker temporarily unavailable")
        var attempts = 0
        val purger = RetiredDeviceMailboxPurger(
            isEnabled = { true },
            purge = { _, _ ->
                attempts++
                if (attempts == 1) throw failure
            },
        )

        val firstAttempt = runCatching {
            purger.purgeAfterRevocation("account-1", "revoked-device")
        }.exceptionOrNull()

        assertSame(failure, firstAttempt)
        assertEquals(1, attempts)

        purger.purgeAfterRevocation("account-1", "revoked-device")

        assertEquals(2, attempts)
    }
}
