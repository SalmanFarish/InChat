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
import com.example.inchat.data.local.CachedMessageEntity
import com.example.inchat.data.local.LocalChatDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Delivers one Room outbox entry without depending on an open chat screen.
 *
 * WorkManager persists the request across process death and waits for network
 * connectivity. The original message ID is always reused, making retries
 * idempotent at the Realtime Database path.
 */
class PendingMessageDeliveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID).orEmpty()
        val chatId = inputData.getString(KEY_CHAT_ID).orEmpty()
        val messageId = inputData.getString(KEY_MESSAGE_ID).orEmpty()

        if (ownerUserId.isBlank() || chatId.isBlank() || messageId.isBlank()) {
            Log.e(TAG, "Pending delivery work is missing required identifiers.")
            return Result.failure()
        }

        val auth = FirebaseAuth.getInstance()
        val currentUserId = auth.currentUser?.uid
        if (currentUserId == null) {
            // Firebase Auth may still be restoring its persisted session.
            return Result.retry()
        }
        if (currentUserId != ownerUserId) {
            // Never replay one account's queued message using another account.
            Log.i(TAG, "Skipping pending message after the signed-in account changed.")
            return Result.failure()
        }

        val dao = LocalChatDatabase.getInstance(applicationContext).messageDao()
        val localMessage = dao.getMessage(ownerUserId, chatId, messageId)
            ?: return Result.success()

        if (!localMessage.isPending) {
            return Result.success()
        }
        if (localMessage.senderId != ownerUserId) {
            Log.e(TAG, "Pending message sender does not match its Room owner.")
            return Result.failure()
        }

        return try {
            val messageRef = FirebaseDatabase.getInstance()
                .getReference("chats")
                .child(chatId)
                .child("messages")
                .child(messageId)

            // Recover writes that reached Firebase before the app process died
            // but whose Room pending flag was not yet cleared.
            val remoteSnapshot = messageRef.get().await()
            if (remoteSnapshot.exists()) {
                val remoteId = remoteSnapshot.child("id").getValue(String::class.java)
                val remoteSender = remoteSnapshot.child("senderId").getValue(String::class.java)
                if (remoteId == messageId && remoteSender == ownerUserId) {
                    if (!enqueueMessageEvent(ownerUserId, chatId, messageId)) {
                        return Result.retry()
                    }
                    dao.markMessageSent(ownerUserId, chatId, messageId)
                    return Result.success()
                }

                // Never overwrite an existing record that cannot be proven
                // to be this account's original message.
                Log.e(TAG, "Refusing to overwrite a conflicting remote message at $chatId/$messageId.")
                return Result.failure()
            }

            if (auth.currentUser?.uid != ownerUserId) {
                return Result.failure()
            }

            val messageWrite = messageRef.setValue(localMessage.toFirebasePayload())

            messageWrite.await()

            if (auth.currentUser?.uid != ownerUserId) {
                // The server write may have succeeded, but do not mutate the
                // now inactive account's local delivery state from this session.
                return Result.failure()
            }

            // The notification event is scheduled only after the message
            // write is acknowledged. If scheduling fails, retrying this worker
            // will find the existing message and repair the event.
            if (!enqueueMessageEvent(ownerUserId, chatId, messageId)) {
                return Result.retry()
            }

            dao.markMessageSent(ownerUserId, chatId, messageId)
            Log.i(TAG, "Delivered pending Room message $messageId in chat $chatId.")
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Pending message delivery will be retried: $messageId", error)
            Result.retry()
        }
    }

    private fun enqueueMessageEvent(
        ownerUserId: String,
        chatId: String,
        messageId: String
    ): Boolean =
        try {
            MessageNotificationWorker.enqueue(
                context = applicationContext,
                senderId = ownerUserId,
                chatId = chatId,
                messageId = messageId
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not schedule the message event for $messageId.", error)
            false
        }

    companion object {
        private const val TAG = "PendingMessageDelivery"
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_CHAT_ID = "chat_id"
        private const val KEY_MESSAGE_ID = "message_id"

        fun enqueue(
            context: Context,
            ownerUserId: String,
            chatId: String,
            messageId: String
        ) {
            require(ownerUserId.isNotBlank() && chatId.isNotBlank() && messageId.isNotBlank()) {
                "Pending message identifiers cannot be blank."
            }

            val request = OneTimeWorkRequestBuilder<PendingMessageDeliveryWorker>()
                .setInputData(
                    workDataOf(
                        KEY_OWNER_USER_ID to ownerUserId,
                        KEY_CHAT_ID to chatId,
                        KEY_MESSAGE_ID to messageId
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                // Give the foreground Firebase write a chance to finish first.
                // If Android kills the process, the persisted job remains as fallback.
                .setInitialDelay(15L, TimeUnit.SECONDS)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30L,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    uniqueWorkName(ownerUserId, chatId, messageId),
                    ExistingWorkPolicy.KEEP,
                    request
                )
        }

        private fun uniqueWorkName(
            ownerUserId: String,
            chatId: String,
            messageId: String
        ): String = "pending-message-$ownerUserId-$chatId-$messageId"
    }
}

/**
 * Build the same Realtime Database message shape used by foreground direct
 * and group sends. ServerValue.TIMESTAMP remains authoritative after delivery.
 */
private fun CachedMessageEntity.toFirebasePayload(): Map<String, Any> =
    mutableMapOf<String, Any>(
        "id" to messageId,
        "senderId" to senderId,
        "senderNickname" to senderNickname,
        "text" to text,
        "timestamp" to ServerValue.TIMESTAMP
    ).apply {
        if (attachmentType.isNotBlank()) {
            put(
                "attachment",
                mapOf(
                    "type" to attachmentType,
                    "storageKey" to attachmentStorageKey,
                    "fileName" to attachmentFileName,
                    "mimeType" to attachmentMimeType,
                    "sizeBytes" to attachmentSizeBytes
                )
            )
        }

        if (replyMessageId.isNotBlank()) {
            put(
                "replyTo",
                mapOf(
                    "messageId" to replyMessageId,
                    "senderId" to replySenderId,
                    "senderNickname" to replySenderNickname,
                    "text" to replyText
                )
            )
        }
    }
