package com.example.inchat.data.worker

import android.content.Context
import android.os.SystemClock
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
import com.example.inchat.crypto.OlmAuthenticatedMessagePayloadCodec
import com.example.inchat.data.repository.CloudflareNotificationRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Validates a direct-chat wake-up against its actual conversation peer.
 *
 * A recipient may be the peer account or another device owned by the sender.
 * The conversation ID always identifies the two chat participants, not the
 * recipient device's account, which can be the sender account itself.
 */
internal fun isValidEncryptedOlmDirectWakeupScope(
    ownerUserId: String,
    recipientUserId: String,
    conversationId: String,
    conversationPeerUserId: String,
): Boolean {
    val userIdPattern = Regex("[A-Za-z0-9_-]{1,128}")
    return ownerUserId.matches(userIdPattern) &&
        recipientUserId.matches(userIdPattern) &&
        conversationPeerUserId.matches(userIdPattern) &&
        conversationPeerUserId != ownerUserId &&
        (recipientUserId == ownerUserId || recipientUserId == conversationPeerUserId) &&
        conversationId == OlmAuthenticatedMessagePayloadCodec.directConversationId(
            ownerUserId,
            conversationPeerUserId,
        )
}

/**
 * Retries the metadata-only FCM wake-up independently from ciphertext relay upload.
 *
 * The durable Room outbox and remote inbox remain authoritative. This worker only
 * prompts the recipient to inspect its encrypted inbox; an FCM failure never causes
 * a duplicate ciphertext commit or plaintext fallback.
 */
class EncryptedOlmMessageWakeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID).orEmpty()
        val senderDeviceId = inputData.getString(KEY_SENDER_DEVICE_ID).orEmpty()
        val recipientUserId = inputData.getString(KEY_RECIPIENT_USER_ID).orEmpty()
        val recipientDeviceId = inputData.getString(KEY_RECIPIENT_DEVICE_ID).orEmpty()
        val deliveryId = inputData.getString(KEY_DELIVERY_ID).orEmpty()
        val messageId = inputData.getString(KEY_MESSAGE_ID).orEmpty()
        val conversationId = inputData.getString(KEY_CONVERSATION_ID).orEmpty()
        val conversationPeerUserId = inputData.getString(KEY_CONVERSATION_PEER_USER_ID).orEmpty()
        val groupId = inputData.getString(KEY_GROUP_ID)
        val membershipEpoch = inputData.getLong(KEY_MEMBERSHIP_EPOCH, 0L)

        if (!ownerUserId.matches(USER_ID_PATTERN) ||
            !senderDeviceId.matches(DEVICE_ID_PATTERN) ||
            !recipientUserId.matches(USER_ID_PATTERN) ||
            !recipientDeviceId.matches(DEVICE_ID_PATTERN) ||
            !deliveryId.matches(DELIVERY_ID_PATTERN) ||
            !messageId.matches(MESSAGE_ID_PATTERN) ||
            !conversationId.matches(CONVERSATION_ID_PATTERN) ||
            (recipientUserId == ownerUserId && recipientDeviceId == senderDeviceId)
        ) {
            Log.e(TAG, "Encrypted message wake-up work has invalid identifiers.")
            return Result.failure()
        }

        if (groupId != null) {
            if (!groupId.matches(CONVERSATION_ID_PATTERN) ||
                conversationId != groupId ||
                membershipEpoch <= 0L ||
                conversationPeerUserId.isNotEmpty()
            ) {
                Log.e(TAG, "Encrypted group wake-up work has an invalid membership scope.")
                return Result.failure()
            }
        } else {
            if (membershipEpoch != 0L ||
                !isValidEncryptedOlmDirectWakeupScope(
                    ownerUserId = ownerUserId,
                    recipientUserId = recipientUserId,
                    conversationId = conversationId,
                    conversationPeerUserId = conversationPeerUserId,
                )
            ) {
                Log.e(TAG, "Encrypted direct wake-up work has an invalid conversation scope.")
                return Result.failure()
            }
        }

        val authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (authenticatedUserId.isNullOrBlank()) {
            Log.i(TAG, "Deferring encrypted message wake-up until Firebase auth is restored.")
            return Result.retry()
        }
        if (authenticatedUserId != ownerUserId) {
            Log.i(TAG, "Retiring encrypted message wake-up after the signed-in account changed.")
            return Result.success()
        }

        val activeDeviceId = try {
            DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not resolve the sender device for encrypted message wake-up.", error)
            return Result.retry()
        }
        if (activeDeviceId != senderDeviceId) {
            Log.i(TAG, "Retiring encrypted message wake-up for a different local sender device.")
            return Result.success()
        }

        return try {
            val dispatchStartedAt = SystemClock.elapsedRealtime()
            val notifications = CloudflareNotificationRepository()
            val dispatch = if (groupId != null) {
                notifications.notifyEncryptedGroupDelivery(
                    senderUserId = ownerUserId,
                    groupId = groupId,
                    membershipEpoch = membershipEpoch,
                    recipientUserId = recipientUserId,
                    deliveryId = deliveryId,
                    messageId = messageId,
                    senderDeviceId = senderDeviceId,
                    recipientDeviceId = recipientDeviceId,
                )
            } else {
                notifications.notifyEncryptedDelivery(
                    senderUserId = ownerUserId,
                    chatId = conversationId,
                    recipientUserId = recipientUserId,
                    deliveryId = deliveryId,
                    messageId = messageId,
                    senderDeviceId = senderDeviceId,
                    recipientDeviceId = recipientDeviceId,
                )
            }
            val failure = dispatch.exceptionOrNull()
            Log.i(
                TAG,
                "Encrypted FCM dispatch duration ms=" +
                    (SystemClock.elapsedRealtime() - dispatchStartedAt).coerceAtLeast(0L) +
                    ", accepted=" + (failure == null),
            )
            when {
                failure == null -> Result.success()
                notifications.isRetryableNotificationFailure(failure) -> {
                    if (runAttemptCount < MAX_WAKE_ATTEMPTS) {
                        Log.w(TAG, "Encrypted message wake-up will be retried.", failure)
                        Result.retry()
                    } else {
                        // FCM is only a wake-up hint. The durable inbox and periodic
                        // reconciliation remain authoritative when a token stays invalid.
                        Log.w(TAG, "Stopping repeated FCM wake-up attempts; durable inbox recovery remains active.", failure)
                        Result.success()
                    }
                }
                else -> {
                    Log.e(TAG, "Encrypted message wake-up was rejected permanently.", failure)
                    Result.failure()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Encrypted message wake-up will be retried.", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EncryptedOlmMessageWake"
        private const val MAX_WAKE_ATTEMPTS = 8
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_SENDER_DEVICE_ID = "sender_device_id"
        private const val KEY_RECIPIENT_USER_ID = "recipient_user_id"
        private const val KEY_RECIPIENT_DEVICE_ID = "recipient_device_id"
        private const val KEY_DELIVERY_ID = "delivery_id"
        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_CONVERSATION_ID = "conversation_id"
        private const val KEY_CONVERSATION_PEER_USER_ID = "conversation_peer_user_id"
        private const val KEY_GROUP_ID = "group_id"
        private const val KEY_MEMBERSHIP_EPOCH = "membership_epoch"
        private const val WORK_TAG = "encrypted_olm_message_wakeup"
        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val DELIVERY_ID_PATTERN = Regex("[A-Fa-f0-9]{64}")
        private val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val CONVERSATION_ID_PATTERN = Regex("[A-Za-z0-9_-]{3,257}")

        fun schedule(
            context: Context,
            ownerUserId: String,
            senderDeviceId: String,
            recipientUserId: String,
            recipientDeviceId: String,
            deliveryId: String,
            messageId: String,
            conversationId: String,
            groupId: String?,
            membershipEpoch: Long?,
            conversationPeerUserId: String?,
        ) {
            require(ownerUserId.matches(USER_ID_PATTERN))
            require(senderDeviceId.matches(DEVICE_ID_PATTERN))
            require(recipientUserId.matches(USER_ID_PATTERN))
            require(recipientDeviceId.matches(DEVICE_ID_PATTERN))
            require(recipientUserId != ownerUserId || recipientDeviceId != senderDeviceId)
            require(deliveryId.matches(DELIVERY_ID_PATTERN))
            require(messageId.matches(MESSAGE_ID_PATTERN))
            require(conversationId.matches(CONVERSATION_ID_PATTERN))
            if (groupId != null) {
                require(groupId.matches(CONVERSATION_ID_PATTERN))
                require(conversationId == groupId && membershipEpoch != null && membershipEpoch > 0L)
                require(conversationPeerUserId == null)
            } else {
                require(membershipEpoch == null)
                require(
                    isValidEncryptedOlmDirectWakeupScope(
                        ownerUserId = ownerUserId,
                        recipientUserId = recipientUserId,
                        conversationId = conversationId,
                        conversationPeerUserId = conversationPeerUserId.orEmpty(),
                    ),
                )
            }

            val request = OneTimeWorkRequestBuilder<EncryptedOlmMessageWakeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_OWNER_USER_ID to ownerUserId,
                        KEY_SENDER_DEVICE_ID to senderDeviceId,
                        KEY_RECIPIENT_USER_ID to recipientUserId,
                        KEY_RECIPIENT_DEVICE_ID to recipientDeviceId,
                        KEY_DELIVERY_ID to deliveryId,
                        KEY_MESSAGE_ID to messageId,
                        KEY_CONVERSATION_ID to conversationId,
                        KEY_CONVERSATION_PEER_USER_ID to conversationPeerUserId,
                        KEY_GROUP_ID to groupId,
                        KEY_MEMBERSHIP_EPOCH to membershipEpoch,
                    ),
                )
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(ownerUserId, senderDeviceId, deliveryId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun workName(ownerUserId: String, senderDeviceId: String, deliveryId: String): String =
            "encrypted-olm-message-wakeup-" + ownerUserId + "-" + senderDeviceId + "-" + deliveryId
    }
}
