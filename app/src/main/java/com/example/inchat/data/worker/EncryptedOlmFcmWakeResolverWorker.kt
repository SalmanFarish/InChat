package com.example.inchat.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.inchat.crypto.DeviceIdentityRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal enum class EncryptedOlmFcmWakeKind(val value: String) {
    INBOX("inbox"),
    ACKNOWLEDGEMENT("acknowledgement");

    companion object {
        fun from(value: String): EncryptedOlmFcmWakeKind? =
            entries.firstOrNull { it.value == value }
    }
}

/** Validate the account/device fence after a resolver has read local identity state. */
internal fun isValidEncryptedOlmFcmWakeScope(
    ownerUserId: String,
    authenticatedUserId: String?,
    localDeviceId: String?,
    wakeKind: EncryptedOlmFcmWakeKind,
    expectedSenderDeviceId: String?,
): Boolean {
    val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
    val devicePattern = Regex("[A-Za-z0-9_-]{1,64}")
    if (!ownerUserId.matches(idPattern) || authenticatedUserId != ownerUserId ||
        localDeviceId?.matches(devicePattern) != true
    ) return false

    return when (wakeKind) {
        EncryptedOlmFcmWakeKind.INBOX -> expectedSenderDeviceId == null
        EncryptedOlmFcmWakeKind.ACKNOWLEDGEMENT ->
            expectedSenderDeviceId?.matches(devicePattern) == true &&
                expectedSenderDeviceId == localDeviceId
    }
}

/**
 * Resolves the current device identity inside durable WorkManager work.
 *
 * FirebaseMessagingService has a short callback budget. It must never drop an
 * encrypted inbox/ACK wake merely because a transient Keystore or local-storage
 * read failed in that callback. This worker retries the lookup, fences account
 * changes, then schedules the existing account/device-scoped durable pipeline.
 */
internal class EncryptedOlmFcmWakeResolverWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID).orEmpty()
        val wakeKind = EncryptedOlmFcmWakeKind.from(inputData.getString(KEY_WAKE_KIND).orEmpty())
        val expectedSenderDeviceId = inputData.getString(KEY_EXPECTED_DEVICE_ID)

        if (!ownerUserId.matches(USER_ID_PATTERN) || wakeKind == null ||
            (wakeKind == EncryptedOlmFcmWakeKind.INBOX && expectedSenderDeviceId != null) ||
            (wakeKind == EncryptedOlmFcmWakeKind.ACKNOWLEDGEMENT &&
                expectedSenderDeviceId?.matches(DEVICE_ID_PATTERN) != true)
        ) {
            Log.e(TAG, "Encrypted FCM wake resolver received invalid scope metadata.")
            return Result.failure()
        }

        val authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (authenticatedUserId.isNullOrBlank()) {
            Log.i(TAG, "Deferring encrypted FCM wake resolution until Firebase auth is restored.")
            return Result.retry()
        }
        if (authenticatedUserId != ownerUserId) {
            Log.i(TAG, "Retiring encrypted FCM wake after the signed-in account changed.")
            return Result.success()
        }

        val deviceLookup: kotlin.Result<String?> = try {
            kotlin.Result.success(
                DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            kotlin.Result.failure(error)
        }
        val localDeviceId = deviceLookup.getOrNull()
        if (deviceLookup.isFailure || localDeviceId.isNullOrBlank() ||
            !localDeviceId.matches(DEVICE_ID_PATTERN)
        ) {
            Log.w(
                TAG,
                "Encrypted FCM wake is waiting for the existing local device identity.",
                deviceLookup.exceptionOrNull(),
            )
            return Result.retry()
        }

        val latestAuthenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (latestAuthenticatedUserId != ownerUserId) {
            Log.i(TAG, "Retiring encrypted FCM wake after auth changed during device lookup.")
            return Result.success()
        }
        if (!isValidEncryptedOlmFcmWakeScope(
                ownerUserId = ownerUserId,
                authenticatedUserId = latestAuthenticatedUserId,
                localDeviceId = localDeviceId,
                wakeKind = wakeKind,
                expectedSenderDeviceId = expectedSenderDeviceId,
            )
        ) {
            Log.i(TAG, "Ignoring an encrypted acknowledgement wake addressed to another local device.")
            return Result.success()
        }

        return try {
            when (wakeKind) {
                EncryptedOlmFcmWakeKind.INBOX -> EncryptedOlmInboxWorker.schedule(
                    context = applicationContext,
                    ownerUserId = ownerUserId,
                    recipientDeviceId = localDeviceId,
                )
                EncryptedOlmFcmWakeKind.ACKNOWLEDGEMENT ->
                    EncryptedOlmDeliveryWorker.scheduleRecoveryAfterTrustChange(
                        context = applicationContext,
                        ownerUserId = ownerUserId,
                        senderDeviceId = localDeviceId,
                    )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not hand off the encrypted FCM wake to its durable pipeline.", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EncryptedOlmFcmWakeResolver"
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_WAKE_KIND = "wake_kind"
        private const val KEY_EXPECTED_DEVICE_ID = "expected_sender_device_id"
        private const val WORK_TAG = "encrypted_olm_fcm_wake_resolver"
        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        fun scheduleInbox(context: Context, ownerUserId: String) {
            enqueue(context, ownerUserId, EncryptedOlmFcmWakeKind.INBOX, null)
        }

        fun scheduleAcknowledgement(
            context: Context,
            ownerUserId: String,
            expectedSenderDeviceId: String,
        ) {
            require(expectedSenderDeviceId.matches(DEVICE_ID_PATTERN))
            enqueue(context, ownerUserId, EncryptedOlmFcmWakeKind.ACKNOWLEDGEMENT, expectedSenderDeviceId)
        }

        private fun enqueue(
            context: Context,
            ownerUserId: String,
            wakeKind: EncryptedOlmFcmWakeKind,
            expectedSenderDeviceId: String?,
        ) {
            require(ownerUserId.matches(USER_ID_PATTERN))
            val request = OneTimeWorkRequestBuilder<EncryptedOlmFcmWakeResolverWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_OWNER_USER_ID to ownerUserId,
                        KEY_WAKE_KIND to wakeKind.value,
                        KEY_EXPECTED_DEVICE_ID to expectedSenderDeviceId,
                    ),
                )
                .addTag(WORK_TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(ownerUserId, wakeKind),
                // A repeated push arriving while resolution is active must leave
                // another reconciliation behind it; inbox work itself coalesces.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        private fun workName(ownerUserId: String, wakeKind: EncryptedOlmFcmWakeKind) =
            "encrypted-olm-fcm-wake-$ownerUserId-${wakeKind.value}"
    }
}
