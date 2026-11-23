package com.example.inchat.data.worker

import android.content.Context

/**
 * Installs the sender, recipient-inbox, and per-device FCM jobs after an Olm
 * identity is published or restored. Auth login can happen before the user
 * creates their first device identity, so registration must also be triggered
 * at the point that identity becomes usable.
 */
object EncryptedOlmDeviceWorkScheduler {
    private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    fun schedule(
        context: Context,
        ownerUserId: String,
        deviceId: String,
    ) {
        require(USER_ID_PATTERN.matches(ownerUserId)) {
            "Encrypted messaging owner ID has an invalid format"
        }
        require(DEVICE_ID_PATTERN.matches(deviceId)) {
            "Encrypted messaging device ID has an invalid format"
        }

        EncryptedOlmDeliveryWorker.schedule(
            context = context,
            ownerUserId = ownerUserId,
            senderDeviceId = deviceId,
        )
        EncryptedOlmInboxWorker.schedule(
            context = context,
            ownerUserId = ownerUserId,
            recipientDeviceId = deviceId,
        )
        // This worker fetches the latest FCM token itself and stores it under
        // this exact published device ID; the token is never put into Work input.
        FcmTokenRegistrationWorker.enqueue(
            context = context,
            uid = ownerUserId,
        )
    }
}
