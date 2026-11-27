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

internal enum class EncryptedOlmFcmWakeAuthDecision { RESOLVED, WAIT_FOR_AUTH, RETIRE }

internal data class EncryptedOlmFcmWakeAuthResolution(
    val decision: EncryptedOlmFcmWakeAuthDecision,
    val ownerUserId: String? = null,
)

/** Resolves cold-start account scope only from the restored Firebase session. */
internal fun resolveEncryptedOlmFcmWakeOwner(
    scheduledOwnerUserId: String?,
    authenticatedUserId: String?,
    resolveOwnerFromAuthenticatedSession: Boolean,
): EncryptedOlmFcmWakeAuthResolution {
    val idPattern = Regex("[A-Za-z0-9_-]{1,128}")
    if (resolveOwnerFromAuthenticatedSession) {
        if (authenticatedUserId.isNullOrBlank()) {
            return EncryptedOlmFcmWakeAuthResolution(EncryptedOlmFcmWakeAuthDecision.WAIT_FOR_AUTH)
        }
        if (!authenticatedUserId.matches(idPattern)) {
            return EncryptedOlmFcmWakeAuthResolution(EncryptedOlmFcmWakeAuthDecision.RETIRE)
        }
        return EncryptedOlmFcmWakeAuthResolution(
            EncryptedOlmFcmWakeAuthDecision.RESOLVED,
            authenticatedUserId,
        )
    }
    if (scheduledOwnerUserId?.matches(idPattern) != true) {
        return EncryptedOlmFcmWakeAuthResolution(EncryptedOlmFcmWakeAuthDecision.RETIRE)
    }
    if (authenticatedUserId.isNullOrBlank()) {
        return EncryptedOlmFcmWakeAuthResolution(EncryptedOlmFcmWakeAuthDecision.WAIT_FOR_AUTH)
    }
    if (authenticatedUserId != scheduledOwnerUserId) {
        return EncryptedOlmFcmWakeAuthResolution(EncryptedOlmFcmWakeAuthDecision.RETIRE)
    }
    return EncryptedOlmFcmWakeAuthResolution(
        EncryptedOlmFcmWakeAuthDecision.RESOLVED,
        scheduledOwnerUserId,
    )
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
        val scheduledOwnerUserId = inputData.getString(KEY_OWNER_USER_ID)
        val resolveOwnerFromAuth = inputData.getBoolean(KEY_RESOLVE_OWNER_FROM_AUTH, false)
        val wakeKind = EncryptedOlmFcmWakeKind.from(inputData.getString(KEY_WAKE_KIND).orEmpty())
        val expectedSenderDeviceId = inputData.getString(KEY_EXPECTED_DEVICE_ID)

        if ((!resolveOwnerFromAuth && scheduledOwnerUserId?.matches(USER_ID_PATTERN) != true) ||
            (resolveOwnerFromAuth && !scheduledOwnerUserId.isNullOrBlank()) ||
            wakeKind == null ||
            (wakeKind == EncryptedOlmFcmWakeKind.INBOX && expectedSenderDeviceId != null) ||
            (wakeKind == EncryptedOlmFcmWakeKind.ACKNOWLEDGEMENT &&
                expectedSenderDeviceId?.matches(DEVICE_ID_PATTERN) != true)
        ) {
            Log.e(TAG, "Encrypted FCM wake resolver received invalid scope metadata.")
            return Result.failure()
        }

        val authResolution = resolveEncryptedOlmFcmWakeOwner(
            scheduledOwnerUserId = scheduledOwnerUserId,
            authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid,
            resolveOwnerFromAuthenticatedSession = resolveOwnerFromAuth,
        )
        val ownerUserId = when (authResolution.decision) {
            EncryptedOlmFcmWakeAuthDecision.WAIT_FOR_AUTH -> {
                Log.i(TAG, "Keeping encrypted FCM wake durable until Firebase auth is restored.")
                return Result.retry()
            }
            EncryptedOlmFcmWakeAuthDecision.RETIRE -> {
                Log.i(TAG, "Retiring encrypted FCM wake because its account scope is no longer valid.")
                return Result.success()
            }
            EncryptedOlmFcmWakeAuthDecision.RESOLVED -> requireNotNull(authResolution.ownerUserId)
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
                    wakeReceivedAtMillis = inputData.getLong(KEY_WAKE_RECEIVED_AT, 0L).takeIf { it > 0L },
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
        private const val KEY_RESOLVE_OWNER_FROM_AUTH = "resolve_owner_from_auth"
        private const val KEY_WAKE_RECEIVED_AT = "wake_received_at_millis"
        private const val WORK_TAG = "encrypted_olm_fcm_wake_resolver"
        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        fun scheduleInbox(
            context: Context,
            ownerUserId: String,
            wakeReceivedAtMillis: Long? = null,
        ) {
            enqueue(context, ownerUserId, EncryptedOlmFcmWakeKind.INBOX, null, wakeReceivedAtMillis)
        }

        fun scheduleInboxAfterAuth(context: Context, wakeReceivedAtMillis: Long? = null) {
            enqueueAfterAuth(context, EncryptedOlmFcmWakeKind.INBOX, null, wakeReceivedAtMillis)
        }

        fun scheduleAcknowledgement(
            context: Context,
            ownerUserId: String,
            expectedSenderDeviceId: String,
        ) {
            require(expectedSenderDeviceId.matches(DEVICE_ID_PATTERN))
            enqueue(context, ownerUserId, EncryptedOlmFcmWakeKind.ACKNOWLEDGEMENT, expectedSenderDeviceId)
        }

        fun scheduleAcknowledgementAfterAuth(
            context: Context,
            expectedSenderDeviceId: String,
            wakeReceivedAtMillis: Long? = null,
        ) {
            require(expectedSenderDeviceId.matches(DEVICE_ID_PATTERN))
            enqueueAfterAuth(context, EncryptedOlmFcmWakeKind.ACKNOWLEDGEMENT, expectedSenderDeviceId, wakeReceivedAtMillis)
        }

        private fun enqueue(
            context: Context,
            ownerUserId: String,
            wakeKind: EncryptedOlmFcmWakeKind,
            expectedSenderDeviceId: String?,
            wakeReceivedAtMillis: Long? = null,
        ) {
            require(ownerUserId.matches(USER_ID_PATTERN))
            enqueueRequest(context, ownerUserId, wakeKind, expectedSenderDeviceId, false, wakeReceivedAtMillis,
                ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        private fun enqueueAfterAuth(
            context: Context,
            wakeKind: EncryptedOlmFcmWakeKind,
            expectedSenderDeviceId: String?,
            wakeReceivedAtMillis: Long?,
        ) {
            enqueueRequest(context, null, wakeKind, expectedSenderDeviceId, true, wakeReceivedAtMillis,
                ExistingWorkPolicy.KEEP)
        }

        private fun enqueueRequest(
            context: Context,
            ownerUserId: String?,
            wakeKind: EncryptedOlmFcmWakeKind,
            expectedSenderDeviceId: String?,
            resolveOwnerFromAuth: Boolean,
            wakeReceivedAtMillis: Long?,
            policy: ExistingWorkPolicy,
        ) {
            val request = OneTimeWorkRequestBuilder<EncryptedOlmFcmWakeResolverWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_OWNER_USER_ID to ownerUserId,
                        KEY_WAKE_KIND to wakeKind.value,
                        KEY_EXPECTED_DEVICE_ID to expectedSenderDeviceId,
                        KEY_RESOLVE_OWNER_FROM_AUTH to resolveOwnerFromAuth,
                        KEY_WAKE_RECEIVED_AT to (wakeReceivedAtMillis ?: 0L),
                    ),
                )
                .addTag(WORK_TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(ownerUserId, wakeKind, expectedSenderDeviceId),
                policy,
                request,
            )
        }

        private fun workName(
            ownerUserId: String?,
            wakeKind: EncryptedOlmFcmWakeKind,
            expectedSenderDeviceId: String?,
        ) = ownerUserId?.let { "encrypted-olm-fcm-wake-" + it + "-" + wakeKind.value }
            ?: ("encrypted-olm-fcm-wake-auth-" + wakeKind.value + "-" + (expectedSenderDeviceId ?: "inbox"))
    }
}
