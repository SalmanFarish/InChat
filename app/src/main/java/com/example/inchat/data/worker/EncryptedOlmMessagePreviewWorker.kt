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
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.repository.CloudflareNotificationRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

internal fun isValidEncryptedOlmPreviewScope(
    ownerUserId: String,
    recipientUserId: String,
    conversationId: String,
    conversationPeerUserId: String,
    groupId: String?,
    membershipEpoch: Long,
): Boolean {
    val userPattern = Regex("[A-Za-z0-9_-]{1,128}")
    val conversationPattern = Regex("[A-Za-z0-9_-]{3,257}")
    if (!ownerUserId.matches(userPattern) ||
        !recipientUserId.matches(userPattern) ||
        !conversationId.matches(conversationPattern) ||
        recipientUserId == ownerUserId
    ) return false

    return if (groupId != null) {
        groupId.matches(conversationPattern) &&
            conversationId == groupId &&
            membershipEpoch > 0L &&
            conversationPeerUserId.isEmpty()
    } else {
        membershipEpoch == 0L &&
            isValidEncryptedOlmDirectWakeupScope(
                ownerUserId = ownerUserId,
                recipientUserId = recipientUserId,
                conversationId = conversationId,
                conversationPeerUserId = conversationPeerUserId,
            )
    }
}

/**
 * Synchronizes generic inbox metadata independently from ciphertext upload.
 *
 * The relay upload and recipient wake-up are already durable before this worker
 * runs. Preview failures therefore retry only this metadata operation and never
 * re-upload an accepted ciphertext envelope.
 */
class EncryptedOlmMessagePreviewWorker(
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
            !isValidEncryptedOlmPreviewScope(
                ownerUserId = ownerUserId,
                recipientUserId = recipientUserId,
                conversationId = conversationId,
                conversationPeerUserId = conversationPeerUserId,
                groupId = groupId,
                membershipEpoch = membershipEpoch,
            )
        ) {
            Log.e(TAG, "Encrypted message preview work has an invalid scope.")
            return Result.failure()
        }

        val authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid
        if (authenticatedUserId.isNullOrBlank()) {
            Log.i(TAG, "Deferring encrypted message preview until Firebase auth is restored.")
            return Result.retry()
        }
        if (authenticatedUserId != ownerUserId) {
            Log.i(TAG, "Retiring encrypted message preview after the signed-in account changed.")
            return Result.success()
        }

        val activeDeviceId = try {
            DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not resolve sender device for encrypted message preview.", error)
            return Result.retry()
        }
        if (activeDeviceId != senderDeviceId) {
            Log.i(TAG, "Retiring encrypted message preview for a different local sender device.")
            return Result.success()
        }

        return try {
            val dao = LocalChatDatabase.getInstance(applicationContext).olmDeliveryQueueDao()
            val notifications = CloudflareNotificationRepository()
            val dispatch = if (groupId != null) {
                val commit = dao.getOutgoingGroupCommit(
                    ownerUserId = ownerUserId,
                    groupId = groupId,
                    messageId = messageId,
                ) ?: return Result.success() // Group-key distribution is not a chat message.
                if (commit.ownerUserId != ownerUserId ||
                    commit.senderDeviceId != senderDeviceId ||
                    commit.groupId != groupId ||
                    commit.membershipEpoch != membershipEpoch ||
                    commit.messageId != messageId ||
                    commit.sentAtMillis <= 0L
                ) {
                    Log.e(TAG, "Encrypted group preview does not match its immutable message commit.")
                    return Result.failure()
                }
                notifications.syncEncryptedGroupMessagePreview(
                    senderUserId = ownerUserId,
                    groupId = groupId,
                    messageId = messageId,
                    sentAtMillis = commit.sentAtMillis,
                    membershipEpoch = commit.membershipEpoch,
                    deliveryId = deliveryId,
                    recipientUserId = recipientUserId,
                    senderDeviceId = senderDeviceId,
                    recipientDeviceId = recipientDeviceId,
                )
            } else {
                val commit = dao.getOutgoingDirectCommit(
                    ownerUserId = ownerUserId,
                    conversationId = conversationId,
                    messageId = messageId,
                ) ?: return Result.success()
                if (commit.ownerUserId != ownerUserId ||
                    commit.senderDeviceId != senderDeviceId ||
                    commit.conversationId != conversationId ||
                    commit.conversationPeerUserId != conversationPeerUserId ||
                    commit.messageId != messageId ||
                    commit.sentAtMillis <= 0L
                ) {
                    Log.e(TAG, "Encrypted direct preview does not match its immutable message commit.")
                    return Result.failure()
                }
                notifications.syncEncryptedDirectMessagePreview(
                    senderUserId = ownerUserId,
                    chatId = conversationId,
                    messageId = messageId,
                    sentAtMillis = commit.sentAtMillis,
                    deliveryId = deliveryId,
                    senderDeviceId = senderDeviceId,
                    recipientDeviceId = recipientDeviceId,
                )
            }

            val failure = dispatch.exceptionOrNull()
            when {
                failure == null -> Result.success()
                notifications.isRetryableNotificationFailure(failure) -> {
                    Log.w(TAG, "Encrypted message preview will be retried independently.", failure)
                    Result.retry()
                }
                else -> {
                    Log.e(TAG, "Encrypted message preview was rejected permanently.", failure)
                    Result.failure()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Encrypted message preview will be retried independently.", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EncryptedOlmPreview"
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
        private const val WORK_TAG = "encrypted_olm_message_preview"
        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val DELIVERY_ID_PATTERN = Regex("[A-Fa-f0-9]{64}")
        private val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

        fun schedule(
            context: Context,
            ownerUserId: String,
            senderDeviceId: String,
            recipientUserId: String,
            recipientDeviceId: String,
            deliveryId: String,
            messageId: String,
            conversationId: String,
            conversationPeerUserId: String?,
            groupId: String?,
            membershipEpoch: Long?,
        ) {
            require(ownerUserId.matches(USER_ID_PATTERN))
            require(senderDeviceId.matches(DEVICE_ID_PATTERN))
            require(recipientUserId.matches(USER_ID_PATTERN))
            require(recipientDeviceId.matches(DEVICE_ID_PATTERN))
            require(deliveryId.matches(DELIVERY_ID_PATTERN))
            require(messageId.matches(MESSAGE_ID_PATTERN))
            require(
                isValidEncryptedOlmPreviewScope(
                    ownerUserId = ownerUserId,
                    recipientUserId = recipientUserId,
                    conversationId = conversationId,
                    conversationPeerUserId = conversationPeerUserId.orEmpty(),
                    groupId = groupId,
                    membershipEpoch = membershipEpoch ?: 0L,
                ),
            )

            val request = OneTimeWorkRequestBuilder<EncryptedOlmMessagePreviewWorker>()
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
                        KEY_MEMBERSHIP_EPOCH to (membershipEpoch ?: 0L),
                    ),
                )
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(ownerUserId, senderDeviceId, deliveryId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        private fun workName(ownerUserId: String, senderDeviceId: String, deliveryId: String) =
            "encrypted-olm-message-preview-" + ownerUserId + "-" + senderDeviceId + "-" + deliveryId
    }
}
