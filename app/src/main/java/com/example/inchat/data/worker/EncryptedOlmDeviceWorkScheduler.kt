package com.example.inchat.data.worker

import android.content.Context
import com.example.inchat.crypto.DeviceIdentityRepository
import com.google.firebase.auth.FirebaseAuth

/**
 * Installs the sender, recipient-inbox, and per-device FCM jobs after an Olm
 * identity is published or restored. Auth login can happen before the user
 * creates their first device identity, so registration must also be triggered
 * at the point that identity becomes usable.
 */
object EncryptedOlmDeviceWorkScheduler {
    private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    /**
     * Wake the durable sender immediately after a connectivity transition.
     * The Room outbox is authoritative; this is only a prompt to drain it.
     */
    fun scheduleImmediateRecovery(
        context: Context,
        ownerUserId: String,
    ) {
        require(USER_ID_PATTERN.matches(ownerUserId)) {
            "Encrypted messaging owner ID has an invalid format"
        }
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.uid != ownerUserId) return
        val appContext = context.applicationContext
        val deviceId = DeviceIdentityRepository(appContext).existingDeviceIdForDelivery()
            ?: return
        if (auth.currentUser?.uid != ownerUserId) return
        EncryptedOlmDeliveryWorker.scheduleImmediate(
            context = appContext,
            ownerUserId = ownerUserId,
            senderDeviceId = deviceId,
        )
    }

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
