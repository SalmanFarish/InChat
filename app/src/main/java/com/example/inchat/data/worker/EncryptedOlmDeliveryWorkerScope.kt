package com.example.inchat.data.worker

/** Account-scope decision made before inspecting local device identity. */
internal enum class EncryptedOlmAuthScopeDecision {
    WAIT_FOR_AUTH,
    RETIRE_STALE_SCOPE,
    CHECK_DEVICE,
}

internal fun evaluateEncryptedOlmAuthScope(
    scheduledOwnerUserId: String,
    authenticatedUserId: String?,
): EncryptedOlmAuthScopeDecision = when {
    authenticatedUserId == null -> EncryptedOlmAuthScopeDecision.WAIT_FOR_AUTH
    authenticatedUserId != scheduledOwnerUserId -> EncryptedOlmAuthScopeDecision.RETIRE_STALE_SCOPE
    else -> EncryptedOlmAuthScopeDecision.CHECK_DEVICE
}

/** Distinguishes a confirmed retired identity from a temporary storage read failure. */
internal enum class EncryptedOlmDeviceScopeDecision {
    KEEP_ACTIVE,
    RETIRE_STALE_SCOPE,
    DEFER_LOOKUP,
}

internal fun evaluateEncryptedOlmDeviceScope(
    scheduledSenderDeviceId: String,
    activeDeviceId: String?,
    lookupFailed: Boolean,
): EncryptedOlmDeviceScopeDecision = when {
    lookupFailed -> EncryptedOlmDeviceScopeDecision.DEFER_LOOKUP
    activeDeviceId == scheduledSenderDeviceId -> EncryptedOlmDeviceScopeDecision.KEEP_ACTIVE
    else -> EncryptedOlmDeviceScopeDecision.RETIRE_STALE_SCOPE
}

/**
 * Reject a device-identity lookup that completed after a newer auth callback.
 * This prevents a slow lookup for one account from recreating its work after
 * a later sign-out or account switch.
 */
internal fun isEncryptedOlmAuthResolutionCurrent(
    requestGeneration: Long,
    currentGeneration: Long,
    requestedOwnerUserId: String,
    authenticatedUserId: String?,
): Boolean =
    requestGeneration == currentGeneration &&
        authenticatedUserId == requestedOwnerUserId
