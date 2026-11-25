package com.example.inchat.crypto

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.worker.EncryptedOlmDeliveryWorker
import com.example.inchat.data.worker.EncryptedOlmDeviceWorkScheduler
import com.google.firebase.auth.FirebaseAuth

/**
 * Builds the production direct-text sender using automatic device provisioning,
 * first-use identity pins, Olm sessions, the Room outbox, and the local message vault.
 *
 * SAS comparison remains optional; ordinary messaging never marks a device
 * SAS-verified without the users explicitly comparing and confirming the code.
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
            onDeviceReady = { ownerUserId, deviceId ->
                runCatching {
                    EncryptedOlmDeviceWorkScheduler.schedule(
                        context = appContext,
                        ownerUserId = ownerUserId,
                        deviceId = deviceId,
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Encrypted inbox and push registration could not be scheduled yet.", error)
                }
            },
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
    private val onDeviceReady: (String, String) -> Unit = { _, _ -> },
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

        // First-use setup is automatic. This is also a race-safe fallback when
        // the user sends before the application startup provisioning finishes.
        val senderBundle = try {
            directory.ensureMyDevicePublished(identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (setupError: Exception) {
            Log.w("OlmDirectMessageSender", "Automatic secure-device setup failed before direct send.", setupError)
            throw IllegalStateException(
                "Secure messaging is still being set up. Check your connection and try again.",
                setupError,
            )
        }
        val senderDeviceId = senderBundle.deviceId
        // A newly provisioned device needs both its outbound queue and inbox
        // poller. Keep this best-effort: the message queue owns send success.
        onDeviceReady(senderUserId, senderDeviceId)
        require(auth.currentUser?.uid == senderUserId) {
            "Authenticated account changed while preparing secure messaging"
        }
        val senderDevices = directory.getUserDevices(senderUserId)
        val recipientDevices = directory.getUserDevices(recipientUserId)
        require(auth.currentUser?.uid == senderUserId) {
            "Authenticated account changed while resolving encrypted recipient devices"
        }
        require(senderDevices.any { it.deviceId == senderDeviceId }) {
            "InChat couldn't finish setting up secure messaging on this phone. Please try again."
        }
        require(recipientDevices.isNotEmpty()) {
            "This person needs to open InChat once before encrypted messages can be delivered."
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
