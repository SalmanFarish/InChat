package com.example.inchat.crypto

/** One device endpoint that must receive a separately encrypted copy of a message. */
internal data class OlmDeliveryTarget(val userId: String, val device: PublicDeviceKeyBundle)

/**
 * Deterministic, side-effect-free recipient planning for direct and group messages.
 * This does not create sessions, claim prekeys, encrypt, or send anything.
 */
internal object OlmFanOutPlanner {
    private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    private const val MAX_DEVICES_PER_USER = 16

    /**
     * Include every recipient member device and every other sender-owned device.
     * Device lists must come from the signature-verifying key directory.
     */
    fun plan(
        senderUserId: String,
        senderDeviceId: String,
        recipientDevicesByUser: Map<String, List<PublicDeviceKeyBundle>>,
        senderDevices: List<PublicDeviceKeyBundle>,
    ): List<OlmDeliveryTarget> {
        require(USER_ID_PATTERN.matches(senderUserId)) { "Sender user ID has an invalid format" }
        require(DEVICE_ID_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }
        require(recipientDevicesByUser.isNotEmpty()) { "At least one recipient user is required" }
        require(senderDevices.size <= MAX_DEVICES_PER_USER) { "Sender has too many registered devices" }

        val targets = LinkedHashMap<String, OlmDeliveryTarget>()
        fun add(userId: String, device: PublicDeviceKeyBundle) {
            require(USER_ID_PATTERN.matches(userId)) { "Delivery target user ID has an invalid format" }
            validatePublicBundle(device)
            if (userId == senderUserId && device.deviceId == senderDeviceId) return
            val key = "$userId::${device.deviceId}"
            targets.putIfAbsent(key, OlmDeliveryTarget(userId, device))
            require(targets.size <= MAX_OLM_DEVICE_DELIVERY_TARGETS) {
                "Message fan-out exceeds the supported device count"
            }
        }

        recipientDevicesByUser.toSortedMap().forEach { (userId, devices) ->
            require(userId != senderUserId) { "Sender must not be listed as a recipient" }
            require(devices.size <= MAX_DEVICES_PER_USER) { "Recipient has too many registered devices" }
            devices.sortedBy { it.deviceId }.forEach { add(userId, it) }
        }
        senderDevices.sortedBy { it.deviceId }.forEach { add(senderUserId, it) }
        require(targets.isNotEmpty()) { "No active devices are available for message delivery" }
        return targets.values.toList()
    }

    private fun validatePublicBundle(bundle: PublicDeviceKeyBundle) {
        require(DEVICE_ID_PATTERN.matches(bundle.deviceId)) { "Delivery target device ID has an invalid format" }
        require(bundle.ed25519IdentityKey.isNotBlank() && bundle.curve25519IdentityKey.isNotBlank() &&
            bundle.curve25519IdentityKeySignature.isNotBlank()) { "Delivery target has an incomplete identity bundle" }
        require(bundle.fallbackKey?.keyKind == "fallback") { "Delivery target has no signed fallback key" }
        require(bundle.oneTimeKeys.size <= 20 && bundle.oneTimeKeys.all {
            it.keyKind == "one_time" && it.keyId.isNotBlank() && it.publicKey.isNotBlank() && it.signature.isNotBlank()
        }) { "Delivery target has an invalid one-time prekey list" }
    }
}
