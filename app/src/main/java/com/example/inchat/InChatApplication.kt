package com.example.inchat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.worker.PendingMessageDeliveryWorker
import com.example.inchat.data.worker.EncryptedOlmDeliveryWorker
import com.example.inchat.data.worker.isEncryptedOlmAuthResolutionCurrent
import com.example.inchat.crypto.DeviceIdentityRepository
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

        private const val ENCRYPTED_DELIVERY_PREFS = "encrypted_olm_delivery_scope"
        private const val KEY_ENCRYPTED_DELIVERY_OWNER = "owner_user_id"
        private const val KEY_ENCRYPTED_DELIVERY_DEVICE = "sender_device_id"
    }

    private val backgroundScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Auth callbacks and background identity checks must not resurrect stale
    // account/device work after a sign-out or account switch.
    private val encryptedDeliveryWorkLock = Any()
    private var encryptedDeliveryAuthGeneration = 0L

    private fun cancelRememberedEncryptedDeliveryWork() {
        val preferences = getSharedPreferences(ENCRYPTED_DELIVERY_PREFS, MODE_PRIVATE)
        val ownerUserId = preferences.getString(KEY_ENCRYPTED_DELIVERY_OWNER, null)
        val senderDeviceId = preferences.getString(KEY_ENCRYPTED_DELIVERY_DEVICE, null)
        if (!ownerUserId.isNullOrBlank() && !senderDeviceId.isNullOrBlank()) {
            EncryptedOlmDeliveryWorker.cancel(
                context = applicationContext,
                ownerUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
            )
        }
        preferences.edit().clear().apply()
    }

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
            val ownerUserId = firebaseAuth.currentUser?.uid
            val generation = synchronized(encryptedDeliveryWorkLock) {
                encryptedDeliveryAuthGeneration += 1
                if (ownerUserId == null) {
                    cancelRememberedEncryptedDeliveryWork()
                    EncryptedOlmDeliveryWorker.cancelAll(applicationContext)
                }
                encryptedDeliveryAuthGeneration
            }

            if (ownerUserId == null) {
                return@addAuthStateListener
            }

            backgroundScope.launch {
                try {
                    // This lookup never creates a device ID or native identity.
                    // A failed read keeps the current account's existing schedule;
                    // an account change still retires the previous account's work.
                    val identityLookup = runCatching {
                        DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery()
                    }
                    identityLookup.exceptionOrNull()?.let { error ->
                        Log.w(
                            "InChatApplication",
                            "Could not inspect the existing encrypted device identity.",
                            error,
                        )
                    }

                    synchronized(encryptedDeliveryWorkLock) {
                        val authStillMatches = isEncryptedOlmAuthResolutionCurrent(
                            requestGeneration = generation,
                            currentGeneration = encryptedDeliveryAuthGeneration,
                            requestedOwnerUserId = ownerUserId,
                            authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid,
                        )
                        if (authStillMatches) {
                            if (identityLookup.isSuccess) {
                                // Cancel the previous account/device scope, then
                                // cancel any tagged jobs left by an interrupted
                                // preference update. Neither operation deletes Room rows.
                                cancelRememberedEncryptedDeliveryWork()
                                EncryptedOlmDeliveryWorker.cancelAll(applicationContext)

                                val senderDeviceId = identityLookup.getOrNull()
                                if (senderDeviceId != null) {
                                    // Also retire legacy untagged work from an earlier
                                    // app version before installing the current schedule.
                                    EncryptedOlmDeliveryWorker.cancel(
                                        context = applicationContext,
                                        ownerUserId = ownerUserId,
                                        senderDeviceId = senderDeviceId,
                                    )
                                    EncryptedOlmDeliveryWorker.schedule(
                                        context = applicationContext,
                                        ownerUserId = ownerUserId,
                                        senderDeviceId = senderDeviceId,
                                    )
                                    val saved = getSharedPreferences(
                                        ENCRYPTED_DELIVERY_PREFS,
                                        MODE_PRIVATE,
                                    ).edit()
                                        .putString(KEY_ENCRYPTED_DELIVERY_OWNER, ownerUserId)
                                        .putString(KEY_ENCRYPTED_DELIVERY_DEVICE, senderDeviceId)
                                        .commit()
                                    if (!saved) {
                                        Log.w(
                                            "InChatApplication",
                                            "Could not persist the encrypted delivery work scope.",
                                        )
                                    }
                                }
                            } else {
                                val rememberedOwner = getSharedPreferences(
                                    ENCRYPTED_DELIVERY_PREFS,
                                    MODE_PRIVATE,
                                ).getString(KEY_ENCRYPTED_DELIVERY_OWNER, null)
                                if (rememberedOwner != null && rememberedOwner != ownerUserId) {
                                    cancelRememberedEncryptedDeliveryWork()
                                    EncryptedOlmDeliveryWorker.cancelAll(applicationContext)
                                }
                            }
                        }
                    }

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
