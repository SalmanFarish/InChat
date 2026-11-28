package com.example.inchat.data.worker

import android.content.Context
import android.util.Log
import com.example.inchat.crypto.CloudflareOlmEnvelopeTransport
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.crypto.isCommittedGroupDeliveryCurrent
import com.example.inchat.crypto.FirebaseOlmEnvelopeTransport
import com.example.inchat.crypto.MAX_OLM_DEVICE_DELIVERY_TARGETS
import com.example.inchat.crypto.OlmAuthenticatedMessagePayloadCodec
import com.example.inchat.crypto.OlmDeliveryQueue
import com.example.inchat.crypto.OlmDeliveryWorkerRunResult
import com.example.inchat.crypto.OlmDeliveryWorkerRunner
import com.example.inchat.crypto.OlmOutgoingDeliveryTransport
import com.example.inchat.crypto.PartitionedOlmEnvelopeTransport
import com.example.inchat.crypto.RoomOlmDeliveryQueueStore
import com.example.inchat.crypto.TrustedDeviceRepository
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.repository.CloudflareWorkerConfig
import com.example.inchat.data.repository.GroupChatRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    private const val GROUP_FOREGROUND_UPLOAD_WORKERS = 4

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

    /** Foreground upload for one durable group-key distribution; ACKs remain with WorkManager. */
    suspend fun dispatchGroupKeyDistribution(
        context: Context, ownerUserId: String, senderDeviceId: String,
        groupId: String, distributionId: String, membershipEpoch: Long,
    ): com.example.inchat.crypto.OlmDeliveryWorkerRunResult =
        dispatchGroupScoped(context, ownerUserId, senderDeviceId, groupId, distributionId, membershipEpoch, true)

    /** Foreground upload for the exact group ciphertext just committed to Room. */
    suspend fun dispatchGroupMessage(
        context: Context, ownerUserId: String, senderDeviceId: String,
        groupId: String, messageId: String, membershipEpoch: Long,
    ): com.example.inchat.crypto.OlmDeliveryWorkerRunResult =
        dispatchGroupScoped(context, ownerUserId, senderDeviceId, groupId, messageId, membershipEpoch, false)

    private suspend fun dispatchGroupScoped(
        context: Context, ownerUserId: String, senderDeviceId: String,
        groupId: String, messageId: String, membershipEpoch: Long, keyDistribution: Boolean,
    ): com.example.inchat.crypto.OlmDeliveryWorkerRunResult {
        require(USER_ID_PATTERN.matches(ownerUserId)) { "Sender account ID has an invalid format" }
        require(DEVICE_ID_PATTERN.matches(senderDeviceId)) { "Sender device ID has an invalid format" }
        require(MESSAGE_ID_PATTERN.matches(messageId)) { "Message ID has an invalid format" }
        require(USER_ID_PATTERN.matches(groupId) && membershipEpoch > 0L) { "Group delivery scope is invalid" }

        val appContext = context.applicationContext
        val auth = FirebaseAuth.getInstance()
        require(auth.currentUser?.uid == ownerUserId) { "Authenticated account changed before foreground group delivery" }
        val identity = DeviceIdentityRepository(appContext)
        require(identity.existingDeviceIdForDelivery() == senderDeviceId) {
            "The active encrypted sender device changed before foreground group delivery"
        }
        require(auth.currentUser?.uid == ownerUserId) { "Authenticated account changed during foreground group delivery setup" }

        val database = LocalChatDatabase.getInstance(appContext)
        val dao = database.olmDeliveryQueueDao()
        val queue = OlmDeliveryQueue(
            RoomOlmDeliveryQueueStore(database, dao, database.encryptedOlmMessageDao()),
        )
        val trustRepository = TrustedDeviceRepository(appContext)
        val firebaseTransport = FirebaseOlmEnvelopeTransport(
            trustVerifier = trustRepository, expectedAuthenticatedUserId = ownerUserId,
        )
        val cloudflareTransport = CloudflareOlmEnvelopeTransport(
            trustVerifier = trustRepository, expectedAuthenticatedUserId = ownerUserId,
        )
        val transport: OlmOutgoingDeliveryTransport = PartitionedOlmEnvelopeTransport(
            firebase = firebaseTransport,
            cloudflare = cloudflareTransport,
            cloudflareDirectEnabled = CloudflareWorkerConfig.isDirectOlmMailboxEnabled,
            cloudflareGroupEnabled = CloudflareWorkerConfig.isGroupOlmMailboxEnabled,
        )
        val metadataStore = database.olmGroupKeyDistributionMetadataStore()
        val groupRepository = GroupChatRepository()
        val runner = OlmDeliveryWorkerRunner(
            queue = queue,
            transport = transport,
            senderUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            maxDeliveriesPerRun = MAX_OLM_DEVICE_DELIVERY_TARGETS,
            recordAcknowledgement = { _, _ -> /* Background worker owns ACK reconciliation. */ },
            validateBeforeUpload = { delivery ->
                val exactRow = delivery.messageId == messageId &&
                    delivery.ownerUserId == ownerUserId &&
                    delivery.senderDeviceId == senderDeviceId &&
                    delivery.groupId == groupId &&
                    delivery.membershipEpoch == membershipEpoch &&
                    delivery.conversationId == groupId &&
                    delivery.conversationPeerUserId == null
                if (!exactRow || auth.currentUser?.uid != ownerUserId ||
                    identity.existingDeviceIdForDelivery() != senderDeviceId
                ) {
                    false
                } else {
                    val membership = groupRepository.getGroupCryptoMembershipSnapshot(groupId)
                        ?: throw IllegalStateException("Current group membership snapshot is unavailable for foreground delivery")
                    val current = membership.isValid() && membership.groupId == groupId &&
                        membership.epoch == membershipEpoch && ownerUserId in membership.memberUserIds &&
                        delivery.recipientUserId in membership.memberUserIds
                    if (!current) {
                        false
                    } else {
                        val commits = dao.getOutgoingGroupCommitsForMessage(ownerUserId, senderDeviceId, messageId)
                        if (keyDistribution) {
                            val metadata = metadataStore.get(ownerUserId, senderDeviceId, messageId)
                            commits.isEmpty() && metadata != null &&
                                metadata.groupId == groupId && metadata.membershipEpoch == membershipEpoch
                        } else {
                            val commit = commits.singleOrNull()
                            commit != null && commit.groupId == groupId &&
                                commit.membershipEpoch == membershipEpoch &&
                                isCommittedGroupDeliveryCurrent(
                                    commit, delivery, ownerUserId, senderDeviceId, membership,
                                )
                        }
                    }
                }
            },
            syncEncryptedGroupPreview = { delivery ->
                if (!keyDistribution && delivery.recipientUserId != ownerUserId) {
                    EncryptedOlmMessagePreviewWorker.schedule(
                        context = appContext, ownerUserId = ownerUserId, senderDeviceId = senderDeviceId,
                        recipientUserId = delivery.recipientUserId, recipientDeviceId = delivery.recipientDeviceId,
                        deliveryId = delivery.deliveryId, messageId = delivery.messageId,
                        conversationId = requireNotNull(delivery.conversationId), conversationPeerUserId = null,
                        groupId = groupId, membershipEpoch = membershipEpoch,
                    )
                }
            },
            notifyEncryptedRecipient = { delivery ->
                EncryptedOlmMessageWakeWorker.schedule(
                    context = appContext, ownerUserId = ownerUserId, senderDeviceId = senderDeviceId,
                    recipientUserId = delivery.recipientUserId, recipientDeviceId = delivery.recipientDeviceId,
                    deliveryId = delivery.deliveryId, messageId = delivery.messageId,
                    conversationId = requireNotNull(delivery.conversationId),
                    groupId = groupId, membershipEpoch = membershipEpoch, conversationPeerUserId = null,
                )
            },
            onTiming = { stage, elapsedMillis ->
                Log.i(TAG, "Foreground encrypted group " + stage + " duration ms=" + elapsedMillis)
            },
            messageIdFilter = messageId,
            processAcknowledgements = false,
        )

        // Room's transactional lease is the cross-worker claim authority. Running
        // a small pool of the same runner overlaps independent relay writes while
        // WorkManager can safely compete for other rows without duplicate uploads.
        val workerResults = coroutineScope {
            List(GROUP_FOREGROUND_UPLOAD_WORKERS) {
                async(Dispatchers.IO) { runner.run() }
            }.awaitAll()
        }
        val runResult = OlmDeliveryWorkerRunResult(
            uploaded = workerResults.sumOf { it.uploaded },
            acknowledged = workerResults.sumOf { it.acknowledged },
            unmatchedAcknowledgements = workerResults.sumOf { it.unmatchedAcknowledgements },
            retryRequired = workerResults.any { it.retryRequired },
            followUpRequired = workerResults.any { it.followUpRequired },
            notificationDispatchFailures = workerResults.sumOf { it.notificationDispatchFailures },
        )
        Log.i(
            TAG,
            "Foreground group delivery complete: group=" + groupId + ", message=" + messageId +
                ", keyDistribution=" + keyDistribution + ", uploaded=" + runResult.uploaded +
                ", retryRequired=" + runResult.retryRequired,
        )
        return runResult
    }

}
