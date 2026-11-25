package com.example.inchat.crypto

/**
 * Local trust state for one published peer device, bound to its current
 * Ed25519 identity key. First-use pinning is deliberately distinct from SAS.
 */
internal enum class DeviceTrustStatus {
    NOT_SEEN,
    FIRST_USE_PINNED,
    SAS_VERIFIED,
    BLOCKED_OR_CHANGED,
}

/** Human-readable aggregate for the devices currently published by one account. */
internal data class DeviceTrustOverview(
    val title: String,
    val description: String,
    val requiresReview: Boolean = false,
) {
    companion object {
        fun from(statuses: List<DeviceTrustStatus>): DeviceTrustOverview {
            if (statuses.isEmpty()) {
                return DeviceTrustOverview(
                    title = "No published devices yet",
                    description = "This person needs to open InChat once before encrypted messages can be delivered.",
                )
            }

            val blocked = statuses.count { it == DeviceTrustStatus.BLOCKED_OR_CHANGED }
            if (blocked > 0) {
                return DeviceTrustOverview(
                    title = "Identity needs review",
                    description = "At least one device identity changed or was blocked on this phone. Verify the device before trusting its messages.",
                    requiresReview = true,
                )
            }

            val pinned = statuses.count { it == DeviceTrustStatus.FIRST_USE_PINNED }
            if (pinned > 0) {
                return DeviceTrustOverview(
                    title = "First-use identity pinned",
                    description = "Messaging can continue. Safety numbers have not been compared for every published device.",
                )
            }

            val unseen = statuses.count { it == DeviceTrustStatus.NOT_SEEN }
            if (unseen > 0) {
                return DeviceTrustOverview(
                    title = "Not yet checked on this phone",
                    description = "This phone has not received an encrypted message from every published device yet.",
                )
            }

            return DeviceTrustOverview(
                title = "All devices SAS-verified",
                description = "Safety numbers were compared and confirmed for every currently published device.",
            )
        }
    }
}
