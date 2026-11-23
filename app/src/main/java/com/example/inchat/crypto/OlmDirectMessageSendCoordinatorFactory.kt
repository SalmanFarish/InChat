package com.example.inchat.crypto

import android.content.Context
import android.util.Log
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.worker.EncryptedOlmDeliveryWorker
import com.google.firebase.auth.FirebaseAuth

/**
 * Builds the production direct-text sender from the already-established identity,
 * signed directory, SAS trust store, Olm sessions, Room outbox, and local message vault.
 *
 * Device publication and SAS verification remain explicit user-driven setup steps.
 * This factory never publishes a device or silently creates a trust decision.
 */
internal class OlmDirectMessageSendCoordinatorFactory(
    context: Context,
    private val database: LocalChatDatabase,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val appContext = context.applicationContext

    fun create(): OlmDirectMessageSendCoordinator {
        val identity = DeviceIdentityRepository(appContext)
        val directory = DeviceKeyDirectoryRepository(auth = auth)
        val trust = TrustedDeviceRepository(appContext)
        val olmSessions = OlmSessionRepository(appContext, identity)
        val sessionAdapter = object : OlmOutboundSessionRepository {
            override val localDeviceId: String
                get() = olmSessions.localDeviceId

            override fun createOutboundSession(
                remoteDevice: PublicDeviceKeyBundle,
                remotePreKey: SignedOlmPreKey,
            ): String = olmSessions.createOutboundSession(remoteDevice, remotePreKey)

            override fun encryptAuthenticatedEnvelope(
                sessionId: String,
                payload: OlmAuthenticatedMessagePayload,
            ): String = olmSessions.encryptAuthenticatedEnvelope(sessionId, payload)
        }
        val queue = OlmDeliveryQueue(
            RoomOlmDeliveryQueueStore(
                database = database,
                dao = database.olmDeliveryQueueDao(),
                encryptedMessageDao = database.encryptedOlmMessageDao(),
            ),
        )
        val localMessages = EncryptedOlmMessageRepository(
            dao = database.encryptedOlmMessageDao(),
            authenticatedUserId = { auth.currentUser?.uid },
        )
        val scheduleDelivery: (String, String) -> Unit = { ownerUserId, senderDeviceId ->
            // The durable transaction has already committed when this is called.
            // A WorkManager scheduling failure must not tell the composer that
            // its message was not queued; periodic/startup recovery can retry it.
            runCatching {
                EncryptedOlmDeliveryWorker.schedule(appContext, ownerUserId, senderDeviceId)
            }.onFailure { error ->
                Log.e(TAG, "Encrypted direct-message delivery was committed but scheduling failed.", error)
            }
        }
        val outbound = OlmOutboundDeliveryCoordinator(
            preparer = OlmOutboundDeliveryPreparer(
                directory = directory,
                sessions = sessionAdapter,
                trustVerifier = trust,
            ),
            queue = queue,
            trustVerifier = trust,
            scheduleDelivery = scheduleDelivery,
            senderMessageRepository = localMessages,
        )
        return OlmDirectMessageSendCoordinator(
            identity = identity,
            directory = directory,
            outbound = outbound,
            auth = auth,
        )
    }

    private companion object {
        const val TAG = "OlmDirectMessageSender"
    }
}

/** Resolves current signed device bundles immediately before each direct send. */
internal class OlmDirectMessageSendCoordinator(
    private val identity: DeviceIdentityRepository,
    private val directory: DeviceKeyDirectoryRepository,
    private val outbound: OlmOutboundDeliveryCoordinator,
    private val auth: FirebaseAuth,
) {
    suspend fun sendTextMessage(
        senderUserId: String,
        recipientUserId: String,
        conversationId: String,
        messageId: String,
        sentAtMillis: Long,
        text: String,
    ): Int {
        require(senderUserId.isNotBlank() && recipientUserId.isNotBlank()) {
            "Direct message participants are missing"
        }
        require(senderUserId != recipientUserId) {
            "A direct message must target another account"
        }
        require(auth.currentUser?.uid == senderUserId) {
            "Authenticated account changed before encrypted direct-message send"
        }
        require(
            conversationId == OlmAuthenticatedMessagePayloadCodec.directConversationId(
                senderUserId,
                recipientUserId,
            )
        ) {
            "Direct conversation does not match its authenticated participants"
        }

        val senderDeviceId = identity.existingDeviceIdForDelivery()
            ?: throw IllegalStateException(
                "This device has no published Olm identity. Open Device Verification and publish this device first.",
            )
        val senderDevices = directory.getUserDevices(senderUserId)
        val recipientDevices = directory.getUserDevices(recipientUserId)
        require(auth.currentUser?.uid == senderUserId) {
            "Authenticated account changed while resolving encrypted recipient devices"
        }
        require(senderDevices.any { it.deviceId == senderDeviceId }) {
            "This device is not present in the signed device directory. Publish it from Device Verification first."
        }
        require(recipientDevices.isNotEmpty()) {
            "The recipient has no published encrypted devices yet."
        }

        return outbound.enqueueDirectTextMessage(
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            recipientUserId = recipientUserId,
            messageId = messageId,
            conversationId = conversationId,
            sentAtMillis = sentAtMillis,
            text = text,
            recipientDevices = recipientDevices,
            senderDevices = senderDevices,
        )
    }
}
