package com.example.inchat.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.inchat.MainActivity
import com.example.inchat.data.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        const val EXTRA_OTHER_USER_ID =
            "otherUserId"

        const val EXTRA_OTHER_USER_NICKNAME =
            "otherUserNickname"

        const val NOTIFICATION_TYPE_CHAT =
            "chat_message"
    }

    override fun onMessageReceived(
        remoteMessage: RemoteMessage
    ) {
        val data =
            remoteMessage.data

        val senderName =
            data["senderName"]
                ?.trim()
                ?.removePrefix("@")
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: remoteMessage.notification?.title
                    ?.removePrefix("@")
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: "InChat"

        val messageText =
            data["message"]
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: remoteMessage.notification?.body
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: "You received a new message"

        val chatId =
            data["chatId"]
                ?.trim()
                .orEmpty()

        val senderId =
            data["senderId"]
                ?.trim()
                .orEmpty()

        val messageId =
            data["messageId"]
                ?.trim()
                .orEmpty()

        showNotification(
            senderName =
                senderName,

            messageText =
                messageText,

            chatId =
                chatId,

            otherUserId =
                senderId,

            messageId =
                messageId
        )
    }

    /*
     * Firebase may issue a new FCM token at any time.
     *
     * Token persistence is handled separately by the app's
     * token-registration implementation.
     */
    @Suppress("DEPRECATION")
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
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            UserRepository()
                .saveFcmToken(
                    uid = uid,
                    token = token
                )
        }
    }

    private fun showNotification(
        senderName: String,
        messageText: String,
        chatId: String,
        otherUserId: String,
        messageId: String
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
                    NOTIFICATION_TYPE_CHAT
                )

                putExtra(
                    EXTRA_CHAT_ID,
                    chatId
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
         * Give every message its own PendingIntent request code
         * so tapping one notification does not reuse another
         * message's intent.
         */
        val requestCode =
            if (
                messageId.isNotBlank()
            ) {
                messageId.hashCode()
            } else {
                DEFAULT_NOTIFICATION_ID
            }

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        /*
         * Give every message its own notification ID.
         *
         * This prevents a new message from replacing an older
         * notification.
         */
        val notificationId =
            if (
                messageId.isNotBlank()
            ) {
                messageId.hashCode()
            } else {
                DEFAULT_NOTIFICATION_ID
            }

        val notification =
            NotificationCompat
                .Builder(
                    this,
                    CHANNEL_ID
                )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_info
                )
                .setContentTitle(
                    "@$senderName"
                )
                .setContentText(
                    messageText
                )
                .setStyle(
                    NotificationCompat
                        .BigTextStyle()
                        .bigText(
                            messageText
                        )
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
                .build()

        try {

            NotificationManagerCompat
                .from(this)
                .notify(
                    notificationId,
                    notification
                )

        } catch (
            e: SecurityException
        ) {
            /*
             * Permission may have changed between the permission
             * check and the actual notify() call.
             */
        }
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
