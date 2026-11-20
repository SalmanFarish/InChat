package com.example.inchat.crypto

/**
 * Result of preparing the device targets for one group-key distribution.
 * Untrusted devices are deliberately omitted rather than receiving a key.
 */
internal data class OlmGroupKeyDeliveryPlan(
    val targets: List<OlmDeliveryTarget>,
    val skippedUntrustedDeviceCount: Int,
)

/**
 * Builds a fan-out only from the exact current group membership snapshot and
 * devices whose published identity is already SAS-trusted on this installation.
 *
 * The device directory must be queried for every non-sender member; missing
 * map entries are rejected so a partial network response cannot silently
 * become an incomplete authorization snapshot.
 */
internal object OlmGroupSessionKeyDistributionPlanner {
    private val USER_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private const val MAX_MEMBERS = 50
    private const val MAX_DEVICES_PER_USER = 16

    fun plan(
        senderUserId: String,
        senderDeviceId: String,
        currentMemberUserIds: Set<String>,
        recipientDevicesByUser: Map<String, List<PublicDeviceKeyBundle>>,
        senderDevices: List<PublicDeviceKeyBundle>,
        trustVerifier: OlmDeviceTrustVerifier,
    ): OlmGroupKeyDeliveryPlan {
        require(USER_ID.matches(senderUserId)) {
            "Group-key sender user ID has an invalid format"
        }
        require(DEVICE_ID.matches(senderDeviceId)) {
            "Group-key sender device ID has an invalid format"
        }
        require(currentMemberUserIds.size in 2..MAX_MEMBERS) {
            "Current group membership snapshot has an invalid size"
        }
        require(currentMemberUserIds.all(USER_ID::matches)) {
            "Current group membership snapshot contains an invalid user ID"
        }
        require(senderUserId in currentMemberUserIds) {
            "Group-key sender is not a current group member"
        }

        val recipientUserIds = currentMemberUserIds - senderUserId
        require(recipientDevicesByUser.keys == recipientUserIds) {
            "Device directory results must cover exactly every other current group member"
        }
        require(senderDevices.size <= MAX_DEVICES_PER_USER) {
            "Sender has too many registered devices"
        }

        var skippedUntrusted = 0
        val trustedRecipients = recipientDevicesByUser.toSortedMap().mapValues { (userId, devices) ->
            require(devices.size <= MAX_DEVICES_PER_USER) {
                "Group member has too many registered devices"
            }
            val trusted = devices.filter { device ->
                trustVerifier.isVerified(
                    peerUserId = userId,
                    peerDeviceId = device.deviceId,
                    peerEd25519IdentityKey = device.ed25519IdentityKey,
                )
            }
            require(trusted.isNotEmpty()) {
                "Every current group member needs at least one SAS-verified device before group key distribution"
            }
            skippedUntrusted += devices.size - trusted.size
            trusted
        }

        // The currently used installation is excluded by OlmFanOutPlanner and
        // is not expected to have a peer-trust record for itself.
        val trustedSenderDevices = senderDevices.filter { device ->
            if (device.deviceId == senderDeviceId) {
                false
            } else {
                val trusted = trustVerifier.isVerified(
                    peerUserId = senderUserId,
                    peerDeviceId = device.deviceId,
                    peerEd25519IdentityKey = device.ed25519IdentityKey,
                )
                if (!trusted) skippedUntrusted += 1
                trusted
            }
        }

        val targets = OlmFanOutPlanner.plan(
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            recipientDevicesByUser = trustedRecipients,
            senderDevices = trustedSenderDevices,
        )
        return OlmGroupKeyDeliveryPlan(
            targets = targets,
            skippedUntrustedDeviceCount = skippedUntrusted,
        )
    }
}
