package com.example.inchat.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.crypto.FirebaseOlmEnvelopeTransport
import com.example.inchat.crypto.OlmDeliveryQueue
import com.example.inchat.crypto.OlmDeliveryWorkerRunner
import com.example.inchat.crypto.RoomOlmDeliveryQueueStore
import com.example.inchat.data.local.LocalChatDatabase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Sends ciphertext-only per-device deliveries from the account/device-scoped
 * Room outbox and consumes durable recipient acknowledgements.
 *
 * This worker is intentionally separate from PendingMessageDeliveryWorker and
 * never accesses chats/{chatId}/messages. It requires an existing local Olm
 * identity and verifies both account and device again before doing network work.
 */
class EncryptedOlmDeliveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID).orEmpty()
        val senderDeviceId = inputData.getString(KEY_SENDER_DEVICE_ID).orEmpty()
        if (!ownerUserId.matches(USER_ID_PATTERN) || !senderDeviceId.matches(DEVICE_ID_PATTERN)) {
            Log.e(TAG, "Encrypted delivery work is missing valid account/device identifiers.")
            return Result.failure()
        }

        val auth = FirebaseAuth.getInstance()
        val authenticatedUserId = auth.currentUser?.uid
        if (authenticatedUserId == null) {
            // The Application auth listener cancels remembered/tagged jobs on sign-out
            // and schedules fresh work after Firebase restores an authenticated session.
            return Result.success()
        }
        if (authenticatedUserId != ownerUserId) {
            // A stale account's immediate and periodic jobs must retire themselves.
            Log.i(TAG, "Skipping encrypted delivery after the signed-in account changed.")
            cancel(applicationContext, ownerUserId, senderDeviceId)
            return Result.success()
        }

        val activeDeviceId = try {
            DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery()
        } catch (error: Exception) {
            Log.e(TAG, "The local encrypted device identity is unavailable; delivery is paused.", error)
            return Result.success()
        } ?: return Result.success()

        if (activeDeviceId != senderDeviceId) {
            Log.i(TAG, "Skipping encrypted delivery for a retired or inactive device identity.")
            return Result.success()
        }

        return try {
            val database = LocalChatDatabase.getInstance(applicationContext)
            val queue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(database, database.olmDeliveryQueueDao()),
            )
            val transport = FirebaseOlmEnvelopeTransport()
            val result = OlmDeliveryWorkerRunner(
                queue = queue,
                transport = transport,
                senderUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
            ).run()

            Log.i(
                TAG,
                "Encrypted relay pass completed: uploaded=" + result.uploaded +
                    ", acknowledged=" + result.acknowledged +
                    ", unmatchedAcks=" + result.unmatchedAcknowledgements,
            )
            if (result.retryRequired) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Encrypted relay delivery will be retried.", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EncryptedOlmDelivery"
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_SENDER_DEVICE_ID = "sender_device_id"
        private const val PERIODIC_INTERVAL_MINUTES = 15L
        private const val WORK_TAG = "encrypted_olm_delivery"
        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /**
         * Schedule immediate recovery plus a 15-minute poll for late recipient
         * acknowledgements and deliveries left queued while the app was stopped.
         */
        fun schedule(context: Context, ownerUserId: String, senderDeviceId: String) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }

            val appContext = context.applicationContext
            val data = workDataOf(
                KEY_OWNER_USER_ID to ownerUserId,
                KEY_SENDER_DEVICE_ID to senderDeviceId,
            )
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediate = OneTimeWorkRequestBuilder<EncryptedOlmDeliveryWorker>()
                .setInputData(data)
                .addTag(WORK_TAG)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()

            val periodic = PeriodicWorkRequestBuilder<EncryptedOlmDeliveryWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setInputData(data)
                .addTag(WORK_TAG)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()

            val workManager = WorkManager.getInstance(appContext)
            workManager.enqueueUniqueWork(
                immediateWorkName(ownerUserId, senderDeviceId),
                ExistingWorkPolicy.KEEP,
                immediate,
            )
            workManager.enqueueUniquePeriodicWork(
                periodicWorkName(ownerUserId, senderDeviceId),
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }

        /** Cancel both recovery jobs for one account/device without touching its Room outbox. */
        fun cancel(context: Context, ownerUserId: String, senderDeviceId: String) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }

            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(immediateWorkName(ownerUserId, senderDeviceId))
            workManager.cancelUniqueWork(periodicWorkName(ownerUserId, senderDeviceId))
        }

        /** Cancel only encrypted delivery jobs when the Firebase auth scope changes. */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(WORK_TAG)
        }

        private fun immediateWorkName(ownerUserId: String, senderDeviceId: String): String =
            "encrypted-olm-delivery-now-" + ownerUserId + "-" + senderDeviceId

        private fun periodicWorkName(ownerUserId: String, senderDeviceId: String): String =
            "encrypted-olm-delivery-periodic-" + ownerUserId + "-" + senderDeviceId
    }
}
