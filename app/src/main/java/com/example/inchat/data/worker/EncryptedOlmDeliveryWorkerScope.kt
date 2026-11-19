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

/** A missing or changed native identity makes a device-scoped schedule obsolete. */
internal fun isEncryptedOlmScheduledDeviceActive(
    scheduledSenderDeviceId: String,
    activeDeviceId: String?,
): Boolean = activeDeviceId != null && activeDeviceId == scheduledSenderDeviceId
