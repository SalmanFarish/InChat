package com.example.inchat.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.data.repository.CloudflareNotificationRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Durably dispatches a sender wake-up after a recipient-side Olm ACK was stored.
 *
 * This side effect is deliberately separate from inbox decryption: the inbound
 * worker only persists the ACK and schedules this work, then continues to
 * decrypt/commit messages without waiting for a Cloudflare/FCM round trip.
 */
class EncryptedOlmAcknowledgementWakeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val acknowledgedByUserId = inputData.getString(KEY_ACK_USER_ID).orEmpty()
        val acknowledgingDeviceId = inputData.getString(KEY_ACK_DEVICE_ID).orEmpty()
        val senderUserId = inputData.getString(KEY_SENDER_USER_ID).orEmpty()
        val senderDeviceId = inputData.getString(KEY_SENDER_DEVICE_ID).orEmpty()
        val deliveryId = inputData.getString(KEY_DELIVERY_ID).orEmpty()
        val messageId = inputData.getString(KEY_MESSAGE_ID).orEmpty()

        if (!acknowledgedByUserId.matches(USER_ID_PATTERN) ||
            !senderUserId.matches(USER_ID_PATTERN) ||
            !acknowledgingDeviceId.matches(DEVICE_ID_PATTERN) ||
            !senderDeviceId.matches(DEVICE_ID_PATTERN) ||
            !deliveryId.matches(DELIVERY_ID_PATTERN) ||
            !messageId.matches(MESSAGE_ID_PATTERN) ||
            (acknowledgedByUserId == senderUserId && acknowledgingDeviceId == senderDeviceId)
        ) {
            Log.e(TAG, "Encrypted acknowledgement wake-up work has invalid identifiers.")
            return Result.failure()
        }

        val authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (authenticatedUserId.isNullOrBlank()) {
            Log.i(TAG, "Deferring encrypted acknowledgement wake-up until Firebase auth is restored.")
            return Result.retry()
        }
        if (authenticatedUserId != acknowledgedByUserId) {
            Log.i(TAG, "Retiring encrypted acknowledgement wake-up after the signed-in account changed.")
            return Result.success()
        }

        val activeDeviceId = try {
            DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not resolve the acknowledging device for sender wake-up.", error)
            return Result.retry()
        }
        if (activeDeviceId != acknowledgingDeviceId) {
            Log.i(TAG, "Retiring encrypted acknowledgement wake-up for a different local device.")
            return Result.success()
        }

        return try {
            val dispatch = CloudflareNotificationRepository().notifyEncryptedAcknowledgement(
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = acknowledgingDeviceId,
                deliveryId = deliveryId,
                messageId = messageId,
            )
            val failure = dispatch.exceptionOrNull()
            when {
                failure == null -> Result.success()
                notifications.isRetryableNotificationFailure(failure) -> {
                    Log.w(TAG, "Encrypted acknowledgement wake-up will be retried.", failure)
                    Result.retry()
                }
                else -> {
                    Log.e(TAG, "Encrypted acknowledgement wake-up was rejected permanently.", failure)
                    Result.failure()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Encrypted acknowledgement wake-up will be retried.", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EncryptedOlmAckWake"
        private const val KEY_ACK_USER_ID = "acknowledged_by_user_id"
        private const val KEY_ACK_DEVICE_ID = "acknowledging_device_id"
        private const val KEY_SENDER_USER_ID = "sender_user_id"
        private const val KEY_SENDER_DEVICE_ID = "sender_device_id"
        private const val KEY_DELIVERY_ID = "delivery_id"
        private const val KEY_MESSAGE_ID = "message_id"
        private const val WORK_TAG = "encrypted_olm_ack_wakeup"
        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val DELIVERY_ID_PATTERN = Regex("[A-Fa-f0-9]{64}")
        private val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

        fun schedule(
            context: Context,
            acknowledgedByUserId: String,
            acknowledgingDeviceId: String,
            senderUserId: String,
            senderDeviceId: String,
            deliveryId: String,
            messageId: String,
        ) {
            require(acknowledgedByUserId.matches(USER_ID_PATTERN))
            require(acknowledgingDeviceId.matches(DEVICE_ID_PATTERN))
            require(senderUserId.matches(USER_ID_PATTERN))
            require(senderDeviceId.matches(DEVICE_ID_PATTERN))
            require(deliveryId.matches(DELIVERY_ID_PATTERN))
            require(messageId.matches(MESSAGE_ID_PATTERN))
            require(
                acknowledgedByUserId != senderUserId ||
                    acknowledgingDeviceId != senderDeviceId,
            ) { "A device cannot acknowledge its own local delivery." }

            val request = OneTimeWorkRequestBuilder<EncryptedOlmAcknowledgementWakeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_ACK_USER_ID to acknowledgedByUserId,
                        KEY_ACK_DEVICE_ID to acknowledgingDeviceId,
                        KEY_SENDER_USER_ID to senderUserId,
                        KEY_SENDER_DEVICE_ID to senderDeviceId,
                        KEY_DELIVERY_ID to deliveryId,
                        KEY_MESSAGE_ID to messageId,
                    ),
                )
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(acknowledgedByUserId, acknowledgingDeviceId, deliveryId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun workName(
            acknowledgedByUserId: String,
            acknowledgingDeviceId: String,
            deliveryId: String,
        ): String =
            "encrypted-olm-ack-" + acknowledgedByUserId + "-" +
                acknowledgingDeviceId + "-" + deliveryId
    }
}
