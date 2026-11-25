package com.example.inchat.crypto

/**
 * Local trust state for one published peer device, bound to its current
 * Ed25519 identity key. Legacy SAS metadata is not required for messaging.
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
                    description = "At least one device identity changed or was revoked on this phone. Messages from that device remain blocked.",
                    requiresReview = true,
                )
            }

            val pinned = statuses.count { it == DeviceTrustStatus.FIRST_USE_PINNED }
            if (pinned > 0) {
                return DeviceTrustOverview(
                    title = "Device identity pinned",
                    description = "This device identity is saved on this phone. Encrypted messaging can continue automatically.",
                )
            }

            val unseen = statuses.count { it == DeviceTrustStatus.NOT_SEEN }
            if (unseen > 0) {
                return DeviceTrustOverview(
                    title = "New device identity",
                    description = "InChat saves each device identity automatically when encrypted messaging first uses it.",
                )
            }

            return DeviceTrustOverview(
                title = "All device identities pinned",
                description = "Every currently published device identity is saved on this phone.",
            )
        }
    }
}
