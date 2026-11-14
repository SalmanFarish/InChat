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
import com.example.inchat.data.repository.CloudflareNotificationRepository
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

/**
 * Delivers the trusted message event after the message itself has been saved.
 *
 * The Worker owns the retry lifecycle so sending a message does not wait for
 * notification/network retries. The same event also updates the inbox preview
 * and unread count on the server, so it must not be skipped.
 */
class MessageNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val senderId = inputData.getString(KEY_SENDER_ID).orEmpty()
        val chatId = inputData.getString(KEY_CHAT_ID).orEmpty()
        val messageId = inputData.getString(KEY_MESSAGE_ID).orEmpty()

        if (senderId.isBlank() || chatId.isBlank() || messageId.isBlank()) {
            Log.e(TAG, "Message event work is missing required identifiers.")
            return Result.failure()
        }

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            // The sender may sign back in while this persisted work is waiting.
            return Result.retry()
        }
        if (currentUser.uid != senderId) {
            // Never send an old user's event using a different account's token.
            Log.w(TAG, "Skipping message event because the signed-in account changed.")
            return Result.failure()
        }

        Log.i(TAG, "Processing message event in background for chat=$chatId message=$messageId")
        val notifications = CloudflareNotificationRepository()
        val result = notifications.notifyMessage(chatId, messageId)

        return result.fold(
            onSuccess = {
                Log.i(TAG, "Message event endpoint completed for chat=$chatId message=$messageId")
                Result.success()
            },
            onFailure = { error ->
                if (notifications.isRetryableNotificationFailure(error)) {
                    Log.w(TAG, "Temporary message event failure; WorkManager will retry.", error)
                    Result.retry()
                } else {
                    Log.e(TAG, "Message event failed permanently; message remains saved.", error)
                    Result.failure()
                }
            }
        )
    }

    companion object {
        private const val TAG = "MessageNotificationWork"
        private const val KEY_SENDER_ID = "sender_id"
        private const val KEY_CHAT_ID = "chat_id"
        private const val KEY_MESSAGE_ID = "message_id"

        fun enqueue(
            context: Context,
            senderId: String,
            chatId: String,
            messageId: String
        ) {
            require(senderId.isNotBlank() && chatId.isNotBlank() && messageId.isNotBlank()) {
                "Message event identifiers cannot be blank."
            }

            val request = OneTimeWorkRequestBuilder<MessageNotificationWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SENDER_ID to senderId,
                        KEY_CHAT_ID to chatId,
                        KEY_MESSAGE_ID to messageId
                    )
                )
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

            val uniqueName = "message-event-$senderId-$chatId-$messageId"
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
        }
    }
}
