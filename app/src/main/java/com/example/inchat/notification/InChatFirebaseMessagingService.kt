package com.example.inchat.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.inchat.MainActivity
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.data.worker.EncryptedOlmInboxWorker
import com.example.inchat.data.worker.FcmTokenRegistrationWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class InChatFirebaseMessagingService :
    FirebaseMessagingService() {

    companion object {

        private const val CHANNEL_ID =
            "inchat_messages"

        private const val CHANNEL_NAME =
            "Messages"

        private const val CHANNEL_DESCRIPTION =
            "New InChat messages"

        private const val DEFAULT_NOTIFICATION_ID =
            1001

        /*
         * Intent extras used when a notification is tapped.
         */
        const val EXTRA_NOTIFICATION_TYPE =
            "notification_type"

        const val EXTRA_CHAT_ID =
            "chatId"

        const val EXTRA_RECIPIENT_USER_ID =
            "recipientUserId"

        const val EXTRA_OTHER_USER_ID =
            "otherUserId"

        const val EXTRA_OTHER_USER_NICKNAME =
            "otherUserNickname"

        const val NOTIFICATION_TYPE_CHAT =
            "chat_message"

        const val NOTIFICATION_TYPE_GROUP =
            "group_message"

    }

    override fun onMessageReceived(
        remoteMessage: RemoteMessage
    ) {
        // The encrypted wake-up type is reserved. A malformed instance must
        // be discarded, never reinterpreted as a legacy plaintext notification.
        if (NotificationPayloadPolicy.isEncryptedOlmWakeupType(remoteMessage.data)) {
            if (NotificationPayloadPolicy.isEncryptedOlmWakeup(remoteMessage.data)) {
                scheduleEncryptedInboxWake(remoteMessage.data)
            } else {
                Log.w("InChatFCM", "Ignored an invalid encrypted message wake-up.")
            }
            return
        }

        val recipientUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (!NotificationPayloadPolicy.isIntendedForAccount(remoteMessage.data, recipientUserId)) {
            Log.w("InChatFCM", "Ignored a message notification addressed to another account.")
            return
        }

        val presentation = NotificationPayloadPolicy.from(
            data = remoteMessage.data,
            fallbackTitle = remoteMessage.notification?.title,
            fallbackBody = remoteMessage.notification?.body,
        )

        // Do not log conversation IDs or message content. Encrypted pushes are
        // intentionally indistinguishable in system-visible notification text.
        Log.i(
            "InChatFCM",
            "Push received: type=" + (remoteMessage.data["type"] ?: "unknown")
        )

        if (ActiveConversationTracker.shouldSuppressNotification(presentation.chatId)) {
            Log.i("InChatFCM", "Suppressed push because this chat is currently open.")
            return
        }

        showNotification(
            senderName = presentation.senderName,
            notificationTitle = presentation.title,
            notificationBody = presentation.body,
            messageText = presentation.messageText,
            notificationType = presentation.notificationType,
            chatId = presentation.chatId,
            otherUserId = presentation.senderId,
            messageId = presentation.messageId,
            includeMessageHistory = presentation.includeMessageHistory && recipientUserId.isNotBlank(),
            recipientUserId = recipientUserId,
        )
    }

    private fun scheduleEncryptedInboxWake(data: Map<String, String>) {
        if (!NotificationPayloadPolicy.isEncryptedOlmWakeup(data)) {
            Log.w("InChatFCM", "Ignored an invalid encrypted message wake-up.")
            return
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Log.i("InChatFCM", "Encrypted message wake-up arrived before account restoration.")
            return
        }

        val deviceId = runCatching {
            DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery()
        }.onFailure { error ->
            Log.w("InChatFCM", "Could not resolve this installation's encrypted inbox.", error)
        }.getOrNull() ?: return

        runCatching {
            EncryptedOlmInboxWorker.schedule(
                context = applicationContext,
                ownerUserId = uid,
                recipientDeviceId = deviceId,
            )
        }.onFailure { error ->
            Log.w("InChatFCM", "Could not schedule encrypted inbox recovery.", error)
        }
    }

    /*
     * Firebase may rotate the registration token at any time. Queue durable
     * work instead of launching an untracked coroutine; the worker fetches
     * the latest token again when it runs and retries database/topic failures.
     */
    override fun onNewToken(
        token: String
    ) {
        val uid =
            FirebaseAuth
                .getInstance()
                .currentUser
                ?.uid
                .orEmpty()

        if (uid.isBlank()) {
            Log.i(
                "InChatFCM",
                "FCM token refreshed while signed out; it will be registered after the next login."
            )
            return
        }

        Log.i(
            "InChatFCM",
            "FCM token refreshed; scheduling durable registration (length=${token.length})."
        )

        try {
            FcmTokenRegistrationWorker.enqueue(
                context = this,
                uid = uid
            )
        } catch (error: Exception) {
            Log.e(
                "InChatFCM",
                "Could not schedule refreshed FCM token registration.",
                error
            )
        }
    }

    private fun showNotification(
        senderName: String,
        notificationTitle: String,
        notificationBody: String,
        messageText: String,
        notificationType: String,
        chatId: String,
        otherUserId: String,
        messageId: String,
        includeMessageHistory: Boolean,
        recipientUserId: String,
    ) {
        createNotificationChannel()

        /*
         * Android 13+ requires POST_NOTIFICATIONS.
         */
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("InChatFCM", "Push arrived, but POST_NOTIFICATIONS permission is not granted.")
            return
        }

        /*
         * The notification opens MainActivity.
         *
         * MainActivity will read these extras and navigate to
         * the exact chat after authentication state is ready.
         */
        val intent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP

                putExtra(
                    EXTRA_NOTIFICATION_TYPE,
                    notificationType
                )

                putExtra(
                    EXTRA_CHAT_ID,
                    chatId
                )

                putExtra(
                    EXTRA_RECIPIENT_USER_ID,
                    recipientUserId
                )

                putExtra(
                    EXTRA_OTHER_USER_ID,
                    otherUserId
                )

                putExtra(
                    EXTRA_OTHER_USER_NICKNAME,
                    senderName
                )
            }

        /*
         * Reuse one notification and PendingIntent per conversation.
         * New messages update that conversation instead of creating
         * another notification for every incoming message.
         */
        // A conversation's notification and PendingIntent IDs must be stable
        // across process restarts, but String.hashCode() has easy collisions
        // (for example, distinct strings can overwrite one another's alerts).
        val notificationTag = NotificationPayloadPolicy.notificationTagForChat(
            chatId = chatId,
            recipientUserId = recipientUserId,
        )
        // PendingIntent matching ignores extras. A stable data URI keeps
        // otherwise-identical MainActivity intents distinct per account/chat.
        intent.data = android.net.Uri.parse("inchat://notification/$notificationTag")

        val notificationId = NotificationPayloadPolicy.notificationIdForChat(
            chatId = chatId,
            fallbackId = DEFAULT_NOTIFICATION_ID,
            recipientUserId = recipientUserId,
        )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val notificationBuilder =
            NotificationCompat
                .Builder(
                    this,
                    CHANNEL_ID
                )
                .setSmallIcon(
                    com.example.inchat.R.drawable.ic_stat_inchat
                )
                .setContentTitle(
                    notificationTitle
                )
                .setContentText(
                    notificationBody
                )
                .setPriority(
                    NotificationCompat
                        .PRIORITY_HIGH
                )
                .setCategory(
                    NotificationCompat
                        .CATEGORY_MESSAGE
                )
                .setAutoCancel(
                    true
                )
                .setContentIntent(
                    pendingIntent
                )
                .setVisibility(
                    NotificationCompat
                        .VISIBILITY_PRIVATE
                )

        // Legacy plaintext pushes retain their conversation history. Encrypted
        // pushes never create MessagingStyle entries or persist message text.
        if (includeMessageHistory) {
            notificationBuilder.setStyle(
                buildMessagingStyle(
                    chatId = chatId,
                    senderName = senderName,
                    messageText = messageText,
                    messageId = messageId,
                    recipientUserId = recipientUserId,
                    isGroupMessage = notificationType == NOTIFICATION_TYPE_GROUP,
                    groupName = notificationTitle
                )
            )
        }

        val notification = notificationBuilder.build()

        try {

            NotificationManagerCompat
                .from(this)
                .notify(
                    notificationTag,
                    notificationId,
                    notification
                )

            Log.i("InChatFCM", "Notification posted: id=$notificationId type=$notificationType")
        } catch (
            e: SecurityException
        ) {
            Log.e("InChatFCM", "Android rejected the notification post.", e)
        }
    }


    /**
     * Retains a short, recent message history for each conversation so
     * repeated pushes update one Android MessagingStyle notification.
     * Message IDs prevent duplicate entries if FCM redelivers a push.
     */
    private fun buildMessagingStyle(
        chatId: String,
        senderName: String,
        messageText: String,
        messageId: String,
        recipientUserId: String,
        isGroupMessage: Boolean,
        groupName: String
    ): NotificationCompat.MessagingStyle {
        val preferences =
            getSharedPreferences(
                "inchat_notification_history",
                MODE_PRIVATE
            )

        // Legacy unscoped history is intentionally not reused: it cannot be
        // safely attributed after multiple accounts have used this installation.
        val historyKey = NotificationPayloadPolicy.notificationHistoryKey(
            recipientUserId = recipientUserId,
            chatId = chatId,
        )
        val now = System.currentTimeMillis()
        val cutoff = now - 24L * 60L * 60L * 1000L

        val storedHistory =
            runCatching {
                org.json.JSONArray(
                    preferences.getString(historyKey, "[]")
                )
            }.getOrElse {
                org.json.JSONArray()
            }

        val recentHistory = org.json.JSONArray()
        var alreadyStored = false

        for (index in 0 until storedHistory.length()) {
            val item = storedHistory.optJSONObject(index) ?: continue
            if (item.optLong("timestamp") < cutoff) continue

            recentHistory.put(item)
            if (messageId.isNotBlank() && item.optString("id") == messageId) {
                alreadyStored = true
            }
        }

        if (!alreadyStored) {
            recentHistory.put(
                org.json.JSONObject()
                    .put("id", messageId)
                    .put("sender", senderName)
                    .put("text", messageText)
                    .put("timestamp", now)
            )
        }

        while (recentHistory.length() > 8) {
            recentHistory.remove(0)
        }

        preferences.edit()
            .putString(historyKey, recentHistory.toString())
            .apply()

        val style =
            NotificationCompat.MessagingStyle(
                androidx.core.app.Person.Builder()
                    .setName("You")
                    .build()
            ).setGroupConversation(isGroupMessage)

        if (isGroupMessage) {
            style.setConversationTitle(groupName)
        }

        for (index in 0 until recentHistory.length()) {
            val item = recentHistory.optJSONObject(index) ?: continue
            val person =
                androidx.core.app.Person.Builder()
                    .setName(item.optString("sender", senderName))
                    .build()

            style.addMessage(
                item.optString("text"),
                item.optLong("timestamp"),
                person
            )
        }

        return style
    }

    private fun createNotificationChannel() {

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager
                    .IMPORTANCE_HIGH
            ).apply {

                description =
                    CHANNEL_DESCRIPTION
            }

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(
            channel
        )
    }
}
