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
    fun stateLookupSeparatesAccountsWithTheSameDeviceId() {
        val aliceState = state(
            "alice-attempt",
            SasVerificationPhase.COMPLETE,
            peerUserId = "alice",
            peerDeviceId = "shared-device-id",
        )
        val bobState = state(
            "bob-attempt",
            SasVerificationPhase.CONFIRMING,
            peerUserId = "bob",
            peerDeviceId = "shared-device-id",
        )
        val states = linkedMapOf(
            aliceState.transactionId to aliceState,
            bobState.transactionId to bobState,
        )

        assertEquals(aliceState, latestDeviceVerificationState(states, "alice", "shared-device-id"))
        assertEquals(bobState, latestDeviceVerificationState(states, "bob", "shared-device-id"))
    }

    @Test
    fun completedUntrustedDeviceCanBeVerifiedAgainOnlyAfterTrustLookup() {
        val completed = state("completed", SasVerificationPhase.COMPLETE)

        assertFalse(shouldOfferDeviceReverification(completed, null, false))
        assertTrue(shouldOfferDeviceReverification(completed, null, true))
        assertFalse(shouldOfferDeviceReverification(completed, 1_800_000_000_000L, true))
    }

    @Test
    fun trustLookupKeysIncludeBothAccountAndDevice() {
        assertTrue(deviceTrustMapKey("alice", "shared-id") != deviceTrustMapKey("bob", "shared-id"))
        assertEquals("alice::phone", deviceTrustMapKey("alice", "phone"))
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
        peerUserId: String = "alice",
        peerDeviceId: String = "alice-tablet",
    ) = SasVerificationUiState(
        transactionId = transactionId,
        peerUserId = peerUserId,
        peerDeviceId = peerDeviceId,
        phase = phase,
    )
}
