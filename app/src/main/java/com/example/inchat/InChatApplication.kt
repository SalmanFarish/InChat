package com.example.inchat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.database.FirebaseDatabase

class InChatApplication : Application() {

    companion object {

        const val MESSAGE_CHANNEL_ID =
            "inchat_messages"

        private const val MESSAGE_CHANNEL_NAME =
            "Messages"

        private const val MESSAGE_CHANNEL_DESCRIPTION =
            "Notifications for new InChat messages"
    }

    override fun onCreate() {
        super.onCreate()

        /*
         * Enable Firebase Realtime Database disk persistence
         * before any FirebaseDatabase reference is created.
         *
         * This allows synchronized data and pending writes
         * to survive temporary offline periods and app restarts.
         */
        FirebaseDatabase
            .getInstance()
            .setPersistenceEnabled(true)

        /*
         * Create the notification channel when the application
         * starts.
         *
         * The Cloudflare Worker sends FCM notifications using
         * the channel ID "inchat_messages", so this channel must
         * exist on the receiver device.
         */
        createMessageNotificationChannel()
    }

    private fun createMessageNotificationChannel() {

        val channel =
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                MESSAGE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    MESSAGE_CHANNEL_DESCRIPTION
            }

        val notificationManager =
            getSystemService(
                NotificationManager::class.java
            )

        notificationManager.createNotificationChannel(
            channel
        )
    }
}