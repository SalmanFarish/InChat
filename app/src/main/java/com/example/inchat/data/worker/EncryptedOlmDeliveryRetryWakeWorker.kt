package com.example.inchat.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Timer-only recovery work. It wakes the immediate sender chain after the
 * Room outbox's persisted retry time without putting new messages behind a
 * delayed or exponentially-backed-off sender request.
 */
internal class EncryptedOlmDeliveryRetryWakeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID).orEmpty()
        val senderDeviceId = inputData.getString(KEY_SENDER_DEVICE_ID).orEmpty()
        if (!ownerUserId.matches(USER_ID_PATTERN) || !senderDeviceId.matches(DEVICE_ID_PATTERN)) {
            Log.e(TAG, "Encrypted delivery retry wake-up has invalid account/device identifiers.")
            return Result.failure()
        }
        return try {
            EncryptedOlmDeliveryWorker.scheduleImmediate(
                context = applicationContext,
                ownerUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not wake the immediate encrypted delivery chain.", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EncryptedOlmRetryWake"
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_SENDER_DEVICE_ID = "sender_device_id"
        private const val WORK_TAG = "encrypted_olm_delivery_retry"
        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        fun schedule(
            context: Context,
            ownerUserId: String,
            senderDeviceId: String,
            delayMillis: Long,
        ) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }
            require(delayMillis >= 0L) { "Retry delay must not be negative" }

            val scheduledAtMillis = System.currentTimeMillis() + delayMillis
            val request = OneTimeWorkRequestBuilder<EncryptedOlmDeliveryRetryWakeWorker>()
                .setInputData(
                    workDataOf(
                        KEY_OWNER_USER_ID to ownerUserId,
                        KEY_SENDER_DEVICE_ID to senderDeviceId,
                    ),
                )
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG)
                .addTag(scopeTag(ownerUserId, senderDeviceId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(ownerUserId, senderDeviceId, scheduledAtMillis),
                // Preserve earlier wake-ups when another pass schedules a later retry.
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, ownerUserId: String, senderDeviceId: String) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }
            WorkManager.getInstance(context.applicationContext)
                .cancelAllWorkByTag(scopeTag(ownerUserId, senderDeviceId))
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(WORK_TAG)
        }

        internal fun workName(ownerUserId: String, senderDeviceId: String, scheduledAtMillis: Long) =
            "encrypted-olm-delivery-retry-$ownerUserId-$senderDeviceId-$scheduledAtMillis"

        private fun scopeTag(ownerUserId: String, senderDeviceId: String) =
            "encrypted_olm_delivery_retry_${ownerUserId}_${senderDeviceId}"
    }
}
