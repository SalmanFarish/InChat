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
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.data.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Keeps the signed-in account's FCM token and server-managed group
 * notification subscriptions synchronized.
 *
 * Only the UID is persisted in WorkManager input. The FCM registration token
 * is fetched when the worker runs so a rotated token is never stored in the
 * WorkManager database or replayed after a newer token has been issued.
 */
class FcmTokenRegistrationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val uid = inputData.getString(KEY_UID).orEmpty()
        if (uid.isBlank()) {
            Log.e(TAG, "FCM registration work is missing the account UID.")
            return Result.failure()
        }

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid == null) {
            // Firebase Auth may still be restoring a persisted session after
            // process start. Retry; logout explicitly cancels this work.
            Log.i(TAG, "Waiting for the signed-in account before registering its FCM token.")
            return Result.retry()
        }
        if (currentUid != uid) {
            Log.i(TAG, "Skipping stale FCM registration work after the account changed.")
            return Result.failure()
        }

        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isBlank() || token.length > MAX_TOKEN_LENGTH) {
                throw IllegalStateException("Firebase returned an invalid FCM registration token.")
            }

            // Recheck before writing in case the account changed while the
            // asynchronous token request was in progress.
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) {
                Log.i(TAG, "Discarding FCM token because the signed-in account changed.")
                return Result.failure()
            }

            // Encrypted delivery addresses a specific published device. Register
            // this token under that device only when an existing Olm identity is
            // already available; never create crypto identity state from FCM work.
            val olmDeviceId = runCatching {
                DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery()
            }.onFailure { error ->
                Log.w(TAG, "Could not resolve the existing Olm device for push registration.", error)
            }.getOrNull()

            val registration = UserRepository().saveFcmToken(
                uid = uid,
                token = token,
                olmDeviceId = olmDeviceId,
            )
            val error = registration.exceptionOrNull()
            if (error != null) {
                if (error is CancellationException) throw error
                Log.w(TAG, "FCM token or group subscription registration failed; retrying.", error)
                Result.retry()
            } else {
                Log.i(TAG, "FCM token and group notification subscriptions are synchronized.")
                Result.success()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not synchronize FCM registration; retrying.", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FcmTokenRegistration"
        private const val KEY_UID = "uid"
        private const val MAX_TOKEN_LENGTH = 4096

        private fun uniqueWorkName(uid: String): String =
            "fcm-token-registration-$uid"

        /**
         * Replace older pending registration work for this account. The
         * worker fetches the latest Firebase token when it actually executes.
         */
        fun enqueue(
            context: Context,
            uid: String
        ) {
            require(uid.isNotBlank()) { "Account UID cannot be blank." }

            val request = OneTimeWorkRequestBuilder<FcmTokenRegistrationWorker>()
                .setInputData(workDataOf(KEY_UID to uid))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30L,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    uniqueWorkName(uid),
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }

        fun cancel(
            context: Context,
            uid: String
        ) {
            if (uid.isBlank()) return

            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueWorkName(uid))
        }
    }
}
