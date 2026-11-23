package com.example.inchat.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.inchat.MainActivity
import com.example.inchat.R
import com.google.firebase.auth.FirebaseAuth

/** Posts a privacy-preserving alert after an encrypted message is committed locally. */
internal class EncryptedMessageNotificationPublisher(context: Context) {
    private val appContext = context.applicationContext

    fun notifyDirectMessage(conversationId: String, senderUserId: String) {
        post(conversationId, senderUserId, "InChat", "New encrypted message",
            InChatFirebaseMessagingService.NOTIFICATION_TYPE_CHAT)
    }

    fun notifyGroupMessage(groupId: String, senderUserId: String) {
        post(groupId, senderUserId, "Group message", "New encrypted group message",
            InChatFirebaseMessagingService.NOTIFICATION_TYPE_GROUP)
    }

    private fun post(
        chatId: String,
        senderUserId: String,
        title: String,
        body: String,
        notificationType: String,
    ) {
        val recipientUserId = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (recipientUserId.isBlank() || chatId.isBlank() || senderUserId.isBlank()) return
        if (ActiveConversationTracker.shouldSuppressNotification(chatId, recipientUserId)) return

        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New InChat messages"
            },
        )
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val tag = NotificationPayloadPolicy.notificationTagForChat(chatId, recipientUserId)
        val notificationId = NotificationPayloadPolicy.notificationIdForChat(
            chatId = chatId,
            fallbackId = DEFAULT_NOTIFICATION_ID,
            recipientUserId = recipientUserId,
        )
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("inchat://encrypted-notification/$tag")
            putExtra(InChatFirebaseMessagingService.EXTRA_NOTIFICATION_TYPE, notificationType)
            putExtra(InChatFirebaseMessagingService.EXTRA_CHAT_ID, chatId)
            putExtra(InChatFirebaseMessagingService.EXTRA_RECIPIENT_USER_ID, recipientUserId)
            putExtra(InChatFirebaseMessagingService.EXTRA_OTHER_USER_ID, senderUserId)
            putExtra(InChatFirebaseMessagingService.EXTRA_OTHER_USER_NICKNAME, "InChat")
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, notificationId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_inchat)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext).notify(tag, notificationId, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "inchat_messages"
        const val DEFAULT_NOTIFICATION_ID = 1001
    }
}
