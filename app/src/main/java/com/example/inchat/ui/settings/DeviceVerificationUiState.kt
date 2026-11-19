package com.example.inchat.ui.settings

import com.example.inchat.crypto.SasVerificationPhase
import com.example.inchat.crypto.SasVerificationUiState

/** Return the most recently published attempt state for one peer device. */
internal fun latestDeviceVerificationState(
    states: Map<String, SasVerificationUiState>,
    peerUserId: String,
    peerDeviceId: String,
): SasVerificationUiState? = states.values.lastOrNull {
    it.peerUserId == peerUserId && it.peerDeviceId == peerDeviceId
}

/**
 * A completed attempt may remain in the UI after trust is revoked or its
 * identity key rotates. Offer a fresh SAS exchange only after the current
 * trust lookup has completed and confirmed that no matching pin exists.
 */
internal fun shouldOfferDeviceReverification(
    state: SasVerificationUiState?,
    trustedAtMillis: Long?,
    trustStatusLoaded: Boolean,
): Boolean =
    state?.phase == SasVerificationPhase.COMPLETE &&
        trustedAtMillis == null &&
        trustStatusLoaded
