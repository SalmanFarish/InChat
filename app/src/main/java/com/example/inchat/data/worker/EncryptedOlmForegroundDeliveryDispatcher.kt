package com.example.inchat.data.worker

import android.content.Context
import android.util.Log
import com.example.inchat.crypto.CloudflareOlmEnvelopeTransport
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.crypto.FirebaseOlmEnvelopeTransport
import com.example.inchat.crypto.MAX_OLM_DEVICE_DELIVERY_TARGETS
import com.example.inchat.crypto.OlmAuthenticatedMessagePayloadCodec
import com.example.inchat.crypto.OlmDeliveryQueue
import com.example.inchat.crypto.OlmDeliveryWorkerRunner
import com.example.inchat.crypto.OlmOutgoingDeliveryTransport
import com.example.inchat.crypto.PartitionedOlmEnvelopeTransport
import com.example.inchat.crypto.RoomOlmDeliveryQueueStore
import com.example.inchat.crypto.TrustedDeviceRepository
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.repository.CloudflareWorkerConfig
import com.google.firebase.auth.FirebaseAuth

/**
 * Sends one freshly committed direct message on the foreground coroutine instead
 * of waiting for WorkManager to start its sender. WorkManager remains scheduled
 * by the caller as the process-death and delayed-retry safety net.
 *
 * The Room lease is still the concurrency authority. The message filter prevents
 * this fast path from claiming unrelated group/direct rows, and ACK reconciliation
 * remains with the normal sender worker to avoid two concurrent ACK consumers.
 */
internal object EncryptedOlmForegroundDeliveryDispatcher {
    private const val TAG = "OlmForegroundDispatch"
    private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    private val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

    suspend fun dispatch(
        context: Context,
        ownerUserId: String,
        senderDeviceId: String,
        messageId: String,
    ): com.example.inchat.crypto.OlmDeliveryWorkerRunResult {
        require(USER_ID_PATTERN.matches(ownerUserId)) { "Sender account ID has an invalid format" }
        require(DEVICE_ID_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }
        require(MESSAGE_ID_PATTERN.matches(messageId)) { "Message ID has an invalid format" }

        val appContext = context.applicationContext
        val auth = FirebaseAuth.getInstance()
        require(auth.currentUser?.uid == ownerUserId) {
            "Authenticated account changed before foreground encrypted delivery"
        }
        val identity = DeviceIdentityRepository(appContext)
        require(identity.existingDeviceIdForDelivery() == senderDeviceId) {
            "The active encrypted sender device changed before foreground delivery"
        }
        require(auth.currentUser?.uid == ownerUserId) {
            "Authenticated account changed during foreground encrypted delivery setup"
        }

        val database = LocalChatDatabase.getInstance(appContext)
        val dao = database.olmDeliveryQueueDao()
        val queue = OlmDeliveryQueue(
            RoomOlmDeliveryQueueStore(
                database = database,
                dao = dao,
                encryptedMessageDao = database.encryptedOlmMessageDao(),
            ),
        )
        val trustRepository = TrustedDeviceRepository(appContext)
        val firebaseTransport = FirebaseOlmEnvelopeTransport(
            trustVerifier = trustRepository,
            expectedAuthenticatedUserId = ownerUserId,
        )
        val cloudflareTransport = CloudflareOlmEnvelopeTransport(
            trustVerifier = trustRepository,
            expectedAuthenticatedUserId = ownerUserId,
        )
        val transport: OlmOutgoingDeliveryTransport = PartitionedOlmEnvelopeTransport(
            firebase = firebaseTransport,
            cloudflare = cloudflareTransport,
            cloudflareDirectEnabled = CloudflareWorkerConfig.isDirectOlmMailboxEnabled,
            cloudflareGroupEnabled = CloudflareWorkerConfig.isGroupOlmMailboxEnabled,
        )
        val groupMetadataStore = database.olmGroupKeyDistributionMetadataStore()

        return OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            maxDeliveriesPerRun = MAX_OLM_DEVICE_DELIVERY_TARGETS,
            recordAcknowledgement = { _, _ ->
                // The background sender owns ACK reconciliation; this foreground
                // pass only uploads the new message's immutable device fan-out.
            },
            validateBeforeUpload = { delivery ->
                // This path is intentionally limited to the one direct message
                // that was just committed. Never consume a group-key or group-chat
                // row without the full membership/epoch recovery validation.
                if (delivery.messageId != messageId ||
                    delivery.groupId != null ||
                    delivery.membershipEpoch != null
                ) {
                    false
                } else {
                    val groupCommits = dao.getOutgoingGroupCommitsForMessage(
                        ownerUserId = ownerUserId,
                        senderDeviceId = senderDeviceId,
                        messageId = delivery.messageId,
                    )
                    val distribution = groupMetadataStore.get(
                        ownerUserId = ownerUserId,
                        senderDeviceId = senderDeviceId,
                        distributionId = delivery.messageId,
                    )
                    val peerUserId = delivery.conversationPeerUserId
                    groupCommits.isEmpty() &&
                        distribution == null &&
                        peerUserId != null &&
                        peerUserId != ownerUserId &&
                        delivery.conversationId ==
                            OlmAuthenticatedMessagePayloadCodec.directConversationId(ownerUserId, peerUserId) &&
                        (delivery.recipientUserId == ownerUserId ||
                            delivery.recipientUserId == peerUserId)
                }
            },
            syncEncryptedDirectPreview = { delivery ->
                if (delivery.recipientUserId != ownerUserId) {
                    EncryptedOlmMessagePreviewWorker.schedule(
                        context = appContext,
                        ownerUserId = ownerUserId,
                        senderDeviceId = senderDeviceId,
                        recipientUserId = delivery.recipientUserId,
                        recipientDeviceId = delivery.recipientDeviceId,
                        deliveryId = delivery.deliveryId,
                        messageId = delivery.messageId,
                        conversationId = requireNotNull(delivery.conversationId),
                        conversationPeerUserId = requireNotNull(delivery.conversationPeerUserId),
                        groupId = null,
                        membershipEpoch = null,
                    )
                }
            },
            notifyEncryptedRecipient = { delivery ->
                EncryptedOlmMessageWakeWorker.schedule(
                    context = appContext,
                    ownerUserId = ownerUserId,
                    senderDeviceId = senderDeviceId,
                    recipientUserId = delivery.recipientUserId,
                    recipientDeviceId = delivery.recipientDeviceId,
                    deliveryId = delivery.deliveryId,
                    messageId = delivery.messageId,
                    conversationId = requireNotNull(delivery.conversationId),
                    groupId = null,
                    membershipEpoch = null,
                    conversationPeerUserId = requireNotNull(delivery.conversationPeerUserId),
                )
            },
            messageIdFilter = messageId,
            processAcknowledgements = false,
            onTiming = { stage, durationMillis ->
                Log.i(TAG, "Foreground encrypted $stage duration ms=$durationMillis")
            },
        ).run().also { result ->
            Log.i(
                TAG,
                "Foreground encrypted delivery pass completed: message=$messageId, " +
                    "uploaded=${result.uploaded}, retryRequired=${result.retryRequired}, " +
                    "pushDispatchFailures=${result.notificationDispatchFailures}",
            )
        }
    }
}
