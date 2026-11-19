package com.example.inchat.ui.settings

import com.example.inchat.crypto.SasVerificationPhase
import com.example.inchat.crypto.SasVerificationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceVerificationUiStateTest {
    @Test
    fun latestAttemptWinsWhenADeviceIsVerifiedAgain() {
        val previous = state("old-attempt", SasVerificationPhase.COMPLETE)
        val current = state("new-attempt", SasVerificationPhase.CONFIRMING)
        val states = linkedMapOf(
            previous.transactionId to previous,
            current.transactionId to current,
        )

        assertEquals(
            current,
            latestDeviceVerificationState(states, "alice", "alice-tablet"),
        )
    }

    @Test
    fun completedUntrustedDeviceCanBeVerifiedAgainOnlyAfterTrustLookup() {
        val completed = state("completed", SasVerificationPhase.COMPLETE)

        assertFalse(shouldOfferDeviceReverification(completed, null, false))
        assertTrue(shouldOfferDeviceReverification(completed, null, true))
        assertFalse(shouldOfferDeviceReverification(completed, 1_800_000_000_000L, true))
    }

    @Test
    fun activeAttemptDoesNotOfferAnotherVerification() {
        val waiting = state("waiting", SasVerificationPhase.WAITING_FOR_PEER)
        val comparing = state("comparing", SasVerificationPhase.CONFIRMING)

        assertFalse(shouldOfferDeviceReverification(waiting, null, true))
        assertFalse(shouldOfferDeviceReverification(comparing, null, true))
    }

    private fun state(
        transactionId: String,
        phase: SasVerificationPhase,
    ) = SasVerificationUiState(
        transactionId = transactionId,
        peerUserId = "alice",
        peerDeviceId = "alice-tablet",
        phase = phase,
    )
}
