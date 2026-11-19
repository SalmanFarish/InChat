package com.example.inchat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.worker.PendingMessageDeliveryWorker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InChatApplication : Application() {

    companion object {

        lateinit var instance: InChatApplication
            private set

        const val MESSAGE_CHANNEL_ID =
            "inchat_messages"

        private const val MESSAGE_CHANNEL_NAME =
            "Messages"

        private const val MESSAGE_CHANNEL_DESCRIPTION =
            "Notifications for new InChat messages"
    }

    private val backgroundScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

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
         * WorkManager jobs survive process death, but a process can be
         * terminated after Room commits a message and before its job is
         * enqueued. Once Firebase restores the signed-in account, scan that
         * account's durable Room outbox and recreate any missing jobs.
         */
        FirebaseAuth.getInstance().addAuthStateListener { firebaseAuth ->
            val ownerUserId = firebaseAuth.currentUser?.uid ?: return@addAuthStateListener
            backgroundScope.launch {
                try {
                    val pendingMessages = LocalChatDatabase
                        .getInstance(applicationContext)
                        .messageDao()
                        .getAllPendingMessages(ownerUserId)

                    pendingMessages.forEach { pending ->
                        if (FirebaseAuth.getInstance().currentUser?.uid != ownerUserId) {
                            return@forEach
                        }
                        PendingMessageDeliveryWorker.enqueue(
                            context = applicationContext,
                            ownerUserId = ownerUserId,
                            chatId = pending.chatId,
                            messageId = pending.messageId
                        )
                    }
                } catch (error: Exception) {
                    Log.e(
                        "InChatApplication",
                        "Could not restore pending message delivery jobs.",
                        error
                    )
                }
            }
        }

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
