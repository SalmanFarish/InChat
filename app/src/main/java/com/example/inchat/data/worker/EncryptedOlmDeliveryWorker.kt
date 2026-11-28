package com.example.inchat.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.inchat.crypto.DeviceIdentityRepository
import com.example.inchat.crypto.DecryptedGroupMessage
import com.example.inchat.crypto.EncryptedGroupMessageRepository
import com.example.inchat.crypto.EncryptedDirectPendingMessageRepository
import com.example.inchat.crypto.EncryptedDirectPendingMessageRetryLock
import com.example.inchat.crypto.OlmDirectMessageSendCoordinatorFactory
import com.example.inchat.crypto.MegolmGroupMessageSendOutcome
import com.example.inchat.crypto.MegolmPendingGroupIntentRetryLock
import com.example.inchat.crypto.isPendingGroupIntentRetryMembershipCurrent
import com.example.inchat.crypto.isCommittedGroupDeliveryCurrent
import com.example.inchat.crypto.isGroupDeliveryScopeCurrent
import com.example.inchat.crypto.DeviceKeyDirectoryRepository
import com.example.inchat.crypto.MegolmSessionRepository
import com.example.inchat.crypto.MegolmGroupMessageSendCoordinatorFactory
import com.example.inchat.crypto.OlmGroupKeyDistributionRecoveryRunner
import com.example.inchat.crypto.OlmGroupSessionKeyDistributionPreparer
import com.example.inchat.crypto.OlmGroupSessionKeyOutboxCoordinator
import com.example.inchat.crypto.OlmSessionRepository
import com.example.inchat.crypto.RoomOlmGroupKeyDistributionRecoveryStateStore
import com.example.inchat.data.repository.GroupChatRepository
import com.example.inchat.crypto.OlmAuthenticatedMessagePayloadCodec
import com.example.inchat.data.repository.CloudflareWorkerConfig
import com.example.inchat.crypto.TrustedDeviceRepository
import com.example.inchat.crypto.FirebaseOlmEnvelopeTransport
import com.example.inchat.crypto.CloudflareOlmEnvelopeTransport
import com.example.inchat.crypto.PartitionedOlmEnvelopeTransport
import com.example.inchat.crypto.OlmOutgoingDeliveryTransport
import com.example.inchat.crypto.OlmDeliveryQueue
import com.example.inchat.crypto.OlmDeliveryWorkerRunner
import com.example.inchat.crypto.RoomOlmDeliveryQueueStore
import com.example.inchat.crypto.RoomOlmDeliveryAcknowledgementRecorder
import com.example.inchat.data.local.LocalChatDatabase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Sends ciphertext-only per-device deliveries from the account/device-scoped
 * Room outbox and consumes durable recipient acknowledgements.
 *
 * This worker is intentionally separate from PendingMessageDeliveryWorker and
 * never accesses chats/{chatId}/messages. It requires an existing local Olm
 * identity and verifies both account and device again before doing network work.
 */
class EncryptedOlmDeliveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val ownerUserId = inputData.getString(KEY_OWNER_USER_ID).orEmpty()
        val senderDeviceId = inputData.getString(KEY_SENDER_DEVICE_ID).orEmpty()
        if (!ownerUserId.matches(USER_ID_PATTERN) || !senderDeviceId.matches(DEVICE_ID_PATTERN)) {
            Log.e(TAG, "Encrypted delivery work is missing valid account/device identifiers.")
            return Result.failure()
        }

        val authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid
        when (evaluateEncryptedOlmAuthScope(ownerUserId, authenticatedUserId)) {
            EncryptedOlmAuthScopeDecision.WAIT_FOR_AUTH -> {
                // The Application auth listener cancels stale jobs on sign-out
                // and schedules a fresh scope after Firebase restores a session.
                return Result.success()
            }
            EncryptedOlmAuthScopeDecision.RETIRE_STALE_SCOPE -> {
                Log.i(TAG, "Retiring encrypted delivery after the signed-in account changed.")
                cancel(applicationContext, ownerUserId, senderDeviceId)
                return Result.success()
            }
            EncryptedOlmAuthScopeDecision.CHECK_DEVICE -> Unit
        }

        // Qualify Kotlin's Result: CoroutineWorker.Result is the WorkManager
        // outcome type and shadows kotlin.Result inside this subclass.
        val identityLookup: kotlin.Result<String?> = try {
            kotlin.Result.success(
                DeviceIdentityRepository(applicationContext).existingDeviceIdForDelivery(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            kotlin.Result.failure(error)
        }
        val deviceScopeDecision = evaluateEncryptedOlmDeviceScope(
            scheduledSenderDeviceId = senderDeviceId,
            activeDeviceId = identityLookup.getOrNull(),
            lookupFailed = identityLookup.isFailure,
        )
        when (encryptedOlmDeviceScopeAction(deviceScopeDecision)) {
            EncryptedOlmDeviceScopeAction.RETRY -> {
                // A one-shot wake must not be consumed by a transient identity-store
                // failure. Retry with WorkManager backoff; permanent missing/retired
                // identities are handled by RETIRE_STALE_SCOPE below.
                Log.w(
                    TAG,
                    "Encrypted delivery is waiting for a temporary local device-identity lookup failure.",
                    identityLookup.exceptionOrNull(),
                )
                return Result.retry()
            }
            EncryptedOlmDeviceScopeAction.RETIRE -> {
                // A missing/retired native identity cannot ever service this schedule.
                // Cancel only its WorkManager jobs; keep durable Room deliveries intact.
                Log.i(TAG, "Retiring encrypted delivery for a missing or inactive device identity.")
                cancel(applicationContext, ownerUserId, senderDeviceId)
                return Result.success()
            }
            EncryptedOlmDeviceScopeAction.CONTINUE -> Unit
        }

        // The device identity read can suspend while Firebase auth changes. Recheck
        // the scheduled owner before opening Room or constructing any relay/recovery
        // collaborators, so a stale worker cannot act under a newly signed-in account.
        when (evaluateEncryptedOlmAuthScope(
            scheduledOwnerUserId = ownerUserId,
            authenticatedUserId = FirebaseAuth.getInstance().currentUser?.uid,
        )) {
            EncryptedOlmAuthScopeDecision.WAIT_FOR_AUTH -> return Result.success()
            EncryptedOlmAuthScopeDecision.RETIRE_STALE_SCOPE -> {
                Log.i(TAG, "Retiring encrypted delivery after auth changed during device validation.")
                cancel(applicationContext, ownerUserId, senderDeviceId)
                return Result.success()
            }
            EncryptedOlmAuthScopeDecision.CHECK_DEVICE -> Unit
        }

        var groupKeyRecoveryRetryRequired = false
        var directPendingRetryRequired = false
        var groupPendingRetryRequired = false
        return try {
            val database = LocalChatDatabase.getInstance(applicationContext)
            val queue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(database, database.olmDeliveryQueueDao()),
            )
            val metadataStore = database.olmGroupKeyDistributionMetadataStore()
            val groupRepository = GroupChatRepository()

            // Resume local-first direct-message intents before the queue runner.
            // Newly committed Olm envelopes can therefore upload in this same pass.
            try {
                val directRetry = retryPendingDirectMessages(
                    database = database,
                    ownerUserId = ownerUserId,
                    senderDeviceId = senderDeviceId,
                )
                directPendingRetryRequired = directRetry.second
                if (directRetry.first > 0) {
                    Log.i(TAG, "Committed " + directRetry.first + " pending encrypted direct messages.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                directPendingRetryRequired = true
                Log.w(TAG, "Pending encrypted direct-message recovery failed; preserving local intents.", error)
            }

            val trustRepository = TrustedDeviceRepository(applicationContext)
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
            val acknowledgementFollowUp = EncryptedOlmAcknowledgementFollowUp {
                scheduleAfterCurrentPass(
                    applicationContext,
                    ownerUserId,
                    senderDeviceId,
                )
            }
            val result = OlmDeliveryWorkerRunner(
                queue = queue,
                transport = transport,
                senderUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
                validateBeforeUpload = { delivery ->
                    val commits = database.olmDeliveryQueueDao()
                        .getOutgoingGroupCommitsForMessage(
                            ownerUserId = ownerUserId,
                            senderDeviceId = senderDeviceId,
                            messageId = delivery.messageId,
                        )
                    when (commits.size) {
                        0 -> {
                            val groupId = delivery.groupId
                            val membershipEpoch = delivery.membershipEpoch
                            val distribution = metadataStore.get(
                                ownerUserId = ownerUserId,
                                senderDeviceId = senderDeviceId,
                                distributionId = delivery.messageId,
                            )
                            if (groupId == null && membershipEpoch == null) {
                                // Direct scope is persisted at enqueue time so
                                // sender-owned device copies retain their original peer.
                                val peer = delivery.conversationPeerUserId
                                distribution == null && peer != null &&
                                    delivery.conversationId == OlmAuthenticatedMessagePayloadCodec.directConversationId(ownerUserId, peer) &&
                                    (delivery.recipientUserId == ownerUserId || delivery.recipientUserId == peer)
                            } else if (groupId == null || membershipEpoch == null || membershipEpoch <= 0L) {
                                false
                            } else if (distribution == null ||
                                distribution.groupId != groupId ||
                                distribution.membershipEpoch != membershipEpoch
                            ) {
                                false
                            } else {
                                val membership = groupRepository.getGroupCryptoMembershipSnapshot(groupId)
                                    ?: throw IllegalStateException("Current group membership snapshot is unavailable")
                                isGroupDeliveryScopeCurrent(delivery, ownerUserId, membership)
                            }
                        }
                        1 -> {
                            val commit = commits.single()
                            if (commit.ownerUserId != ownerUserId ||
                                commit.senderDeviceId != senderDeviceId ||
                                commit.messageId != delivery.messageId ||
                                commit.groupId != delivery.groupId ||
                                commit.groupId != delivery.conversationId ||
                                delivery.conversationPeerUserId != null ||
                                commit.membershipEpoch != delivery.membershipEpoch
                            ) {
                                false
                            } else {
                                val membership = groupRepository
                                    .getGroupCryptoMembershipSnapshot(commit.groupId)
                                    ?: throw IllegalStateException(
                                        "Current group membership snapshot is unavailable",
                                    )
                                isCommittedGroupDeliveryCurrent(
                                    commit = commit,
                                    delivery = delivery,
                                    authenticatedOwnerUserId = ownerUserId,
                                    authenticatedSenderDeviceId = senderDeviceId,
                                    membership = membership,
                                )
                            }
                        }
                        // Ambiguous message identity is not safe to authorize.
                        else -> false
                    }
                },
                recordAcknowledgement = { acknowledgement, acknowledgedAtMillis ->
                    RoomOlmDeliveryAcknowledgementRecorder(
                        database = database,
                        dao = database.olmDeliveryReceiptDao(),
                    ).record(acknowledgement, acknowledgedAtMillis)
                    // A batch can contain many independent ACKs. Coalesce their
                    // follow-up into one durable sender pass, while allowing another
                    // callback to retry if WorkManager enqueue throws.
                    acknowledgementFollowUp.scheduleOnce()
                },
                syncEncryptedDirectPreview = { delivery ->
                    if (delivery.groupId == null && delivery.recipientUserId != ownerUserId) {
                        EncryptedOlmMessagePreviewWorker.schedule(
                            context = applicationContext,
                            ownerUserId = ownerUserId,
                            senderDeviceId = senderDeviceId,
                            recipientUserId = delivery.recipientUserId,
                            recipientDeviceId = delivery.recipientDeviceId,
                            deliveryId = delivery.deliveryId,
                            messageId = delivery.messageId,
                            conversationId = requireNotNull(delivery.conversationId) {
                                "Encrypted direct delivery is missing its conversation scope"
                            },
                            conversationPeerUserId = requireNotNull(delivery.conversationPeerUserId) {
                                "Encrypted direct delivery is missing its peer scope"
                            },
                            groupId = null,
                            membershipEpoch = null,
                        )
                    }
                },
                syncEncryptedGroupPreview = { delivery ->
                    val groupId = delivery.groupId
                    if (groupId != null && delivery.recipientUserId != ownerUserId) {
                        // The preview worker verifies the immutable group commit.
                        // Group-key distribution rows have no message commit and are ignored.
                        EncryptedOlmMessagePreviewWorker.schedule(
                            context = applicationContext,
                            ownerUserId = ownerUserId,
                            senderDeviceId = senderDeviceId,
                            recipientUserId = delivery.recipientUserId,
                            recipientDeviceId = delivery.recipientDeviceId,
                            deliveryId = delivery.deliveryId,
                            messageId = delivery.messageId,
                            conversationId = requireNotNull(delivery.conversationId) {
                                "Encrypted group delivery is missing its conversation scope"
                            },
                            conversationPeerUserId = null,
                            groupId = groupId,
                            membershipEpoch = delivery.membershipEpoch,
                        )
                    }
                },
                notifyEncryptedRecipient = { delivery ->
                    // FCM is a wake-up hint, not part of ciphertext acceptance.
                    // Persist its dispatch separately so transient Worker/FCM errors
                    // retry without re-uploading or delaying the encrypted outbox.
                    EncryptedOlmMessageWakeWorker.schedule(
                        context = applicationContext,
                        ownerUserId = ownerUserId,
                        senderDeviceId = senderDeviceId,
                        recipientUserId = delivery.recipientUserId,
                        recipientDeviceId = delivery.recipientDeviceId,
                        deliveryId = delivery.deliveryId,
                        messageId = delivery.messageId,
                        conversationId = requireNotNull(delivery.conversationId) {
                            "Encrypted delivery is missing its authenticated conversation scope"
                        },
                        groupId = delivery.groupId,
                        membershipEpoch = delivery.membershipEpoch,
                        conversationPeerUserId = delivery.conversationPeerUserId,
                    )
                },
                onTiming = { stage, durationMillis ->
                    Log.i(TAG, "Encrypted sender " + stage + " duration ms=" + durationMillis)
                },
            ).run()

            // Drain the current durable outbox before doing group-specific recovery.
            // The recovery planner only rebuilds missing rows; its outbox schedules
            // another immediate pass for any newly reconstructed group deliveries.
            val savedGroupKeyDistributions = metadataStore.getAllForDevice(
                ownerUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
            )
            if (savedGroupKeyDistributions.isNotEmpty()) {
                val identityRepository = DeviceIdentityRepository(applicationContext)
                val olmSessions = OlmSessionRepository(applicationContext, identityRepository)
                val megolmSessions = MegolmSessionRepository(
                    context = applicationContext,
                    identityRepository = identityRepository,
                    authenticatedUserId = {
                        FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                    },
                )
                val keyPreparer = OlmGroupSessionKeyDistributionPreparer(
                    directory = DeviceKeyDirectoryRepository(),
                    sessions = olmSessions,
                    trustVerifier = TrustedDeviceRepository(applicationContext),
                )
                val recoveryStateReader = RoomOlmGroupKeyDistributionRecoveryStateStore(
                    database = database,
                    queueDao = database.olmDeliveryQueueDao(),
                    receiptDao = database.olmDeliveryReceiptDao(),
                )
                val groupKeyOutbox = OlmGroupSessionKeyOutboxCoordinator(
                    queue = queue,
                    metadataStore = metadataStore,
                    deliveryReceipts = database.olmDeliveryReceiptDao(),
                    authenticatedUserId = { FirebaseAuth.getInstance().currentUser?.uid.orEmpty() },
                    localDeviceId = {
                        identityRepository.existingDeviceIdForDelivery().orEmpty()
                    },
                    scheduleDelivery = { owner, device ->
                        schedule(applicationContext, owner, device)
                    },
                    recoveryStateReader = recoveryStateReader,
                )
                val recovery = try {
                    OlmGroupKeyDistributionRecoveryRunner(
                        metadataStore = metadataStore,
                        recoveryStateReader = recoveryStateReader,
                        membershipForGroup = groupRepository::getGroupCryptoMembershipSnapshot,
                        authenticatedUserId = {
                            FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
                        },
                        localDeviceId = {
                            identityRepository.existingDeviceIdForDelivery().orEmpty()
                        },
                        existingOutboundSession = { membership, sessionId ->
                            megolmSessions.getExistingOutboundSession(membership, sessionId)
                        },
                        exportOutboundSessionKey = { membership, sessionId ->
                            megolmSessions.exportOutboundSessionKey(membership, sessionId)
                        },
                        prepareDistribution = { distributionId, membership, sessionId, key, issuedAt, selected, expected ->
                            keyPreparer.prepare(
                                distributionId = distributionId,
                                membership = membership,
                                megolmSessionId = sessionId,
                                sessionKey = key,
                                issuedAtMillis = issuedAt,
                                selectedTargetDevices = selected,
                                expectedTargetDevices = expected,
                            )
                        },
                        resumeDistribution = groupKeyOutbox::resume,
                        validatePreparedTargets = keyPreparer::arePreparedTargetsStillTrusted,
                        onDistributionFailure = { metadata, error ->
                            Log.w(
                                TAG,
                                "Group-key recovery failed for " + metadata.groupId +
                                    "/" + metadata.distributionId + ".",
                                error,
                            )
                        },
                    ).run()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A stale/untrusted group distribution must not block the
                    // independent direct-device encrypted outbox from draining.
                    groupKeyRecoveryRetryRequired = true
                    Log.w(TAG, "Group-key recovery pass failed; existing encrypted deliveries will continue.", error)
                    null
                }
                if (recovery != null) {
                    groupKeyRecoveryRetryRequired = recovery.distributionsFailed > 0
                    Log.i(
                        TAG,
                        "Group-key recovery pass completed: scanned=" + recovery.distributionsScanned +
                            ", skipped=" + recovery.distributionsSkipped +
                            ", failed=" + recovery.distributionsFailed +
                            ", queued=" + recovery.deliveriesQueued,
                    )
                }

            }

            Log.i(
                TAG,
                "Encrypted relay pass completed: uploaded=" + result.uploaded +
                    ", acknowledged=" + result.acknowledged +
                    ", unmatchedAcks=" + result.unmatchedAcknowledgements +
                    ", pushDispatchFailures=" + result.notificationDispatchFailures,
            )

            if (result.notificationDispatchFailures > 0) {
                Log.w(TAG, "Encrypted push wake-up dispatch failed; recipient inbox polling remains the recovery path.")
            }

            // Acknowledgement receipts are durable now. Retry waiting group
            // intents in the background even when no GroupChatViewModel exists.
            val retriedGroupMessages = try {
                retryPendingGroupMessages(
                    database = database,
                    ownerUserId = ownerUserId,
                    senderDeviceId = senderDeviceId,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                groupPendingRetryRequired = true
                Log.w(TAG, "Pending encrypted group messages need another recovery pass.", error)
                0
            }
            if (retriedGroupMessages > 0) {
                Log.i(TAG, "Automatically queued " + retriedGroupMessages + " pending encrypted group messages.")
            }

            // Keep batch continuation and delayed retries in separate WorkManager
            // chains. A failed delivery's backoff must never sit in front of a
            // newly committed local-first message in the immediate sender chain.
            if (result.followUpRequired) {
                // Acknowledgement reconciliation may already have requested
                // this same continuation. Share the pass-level gate to avoid
                // appending duplicate sender jobs for one completed batch.
                acknowledgementFollowUp.scheduleOnce()
            }
            val now = System.currentTimeMillis().coerceAtLeast(0L)
            val queueRetryDelay = queue.nextScheduledRetryAtMillis(
                ownerUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
                nowMillis = now,
            )?.let { dueAt -> (dueAt - now).coerceAtLeast(0L) }
            val recoveryRetryRequired = groupKeyRecoveryRetryRequired ||
                directPendingRetryRequired || groupPendingRetryRequired
            val fallbackRetryDelay = if (recoveryRetryRequired ||
                (result.retryRequired && queueRetryDelay == null)
            ) DEFAULT_RETRY_DELAY_MILLIS else null
            val retryDelay = listOfNotNull(queueRetryDelay, fallbackRetryDelay).minOrNull()
            if (retryDelay != null) {
                EncryptedOlmDeliveryRetryWakeWorker.schedule(
                    context = applicationContext,
                    ownerUserId = ownerUserId,
                    senderDeviceId = senderDeviceId,
                    delayMillis = retryDelay,
                )
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Encrypted relay delivery will be scheduled for recovery.", error)
            try {
                EncryptedOlmDeliveryRetryWakeWorker.schedule(
                    context = applicationContext,
                    ownerUserId = ownerUserId,
                    senderDeviceId = senderDeviceId,
                    delayMillis = DEFAULT_RETRY_DELAY_MILLIS,
                )
                Result.success()
            } catch (scheduleError: Exception) {
                Log.w(TAG, "Could not schedule encrypted relay recovery; WorkManager will retry this pass.", scheduleError)
                Result.retry()
            }
        }
    }

    /**
     * Convert locally encrypted direct intents into immutable Olm fan-out commits.
     * A failed item stays encrypted in Room and causes another WorkManager pass;
     * no plaintext Firebase fallback is used.
     */
    private suspend fun retryPendingDirectMessages(
        database: LocalChatDatabase,
        ownerUserId: String,
        senderDeviceId: String,
    ): Pair<Int, Boolean> {
        return EncryptedDirectPendingMessageRetryLock.mutex.withLock {
        val auth = FirebaseAuth.getInstance()
        val identity = DeviceIdentityRepository(applicationContext)
        fun requireActiveIdentity() {
            require(auth.currentUser?.uid == ownerUserId) {
                "Authenticated account changed during background direct-message retry"
            }
            require(identity.existingDeviceIdForDelivery() == senderDeviceId) {
                "Local sender device changed during background direct-message retry"
            }
        }

        requireActiveIdentity()
        val repository = EncryptedDirectPendingMessageRepository(
            database.encryptedDirectPendingMessageDao(),
        )
        val pending = repository.pendingOutgoingIntents(ownerUserId)
        if (pending.isEmpty()) return@withLock 0 to false

        // This worker already owns the active sender pass; do not append a
        // redundant unique-work request for each local intent it commits.
        val sender = OlmDirectMessageSendCoordinatorFactory(
            context = applicationContext,
            database = database,
            auth = auth,
        ).create(scheduleCommittedDelivery = false)
        var committedCount = 0
        var retryRequired = false

        for (intent in pending) {
            requireActiveIdentity()
            try {
                // A clear-chat action may have removed the intent after the
                // initial snapshot. Never send an item that is no longer pending.
                if (!repository.isPendingOutgoingIntent(
                    ownerUserId,
                    intent.conversationId,
                    intent.messageId,
                )) continue

                sender.sendTextMessage(
                    senderUserId = ownerUserId,
                    recipientUserId = intent.conversationPeerUserId,
                    conversationId = intent.conversationId,
                    messageId = intent.messageId,
                    sentAtMillis = intent.sentAtMillis,
                    text = intent.plaintext,
                )
                requireActiveIdentity()
                repository.deletePendingOutgoingIntent(
                    ownerUserId,
                    intent.conversationId,
                    intent.messageId,
                )
                committedCount++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // An account/device change is a hard boundary, never an item failure.
                requireActiveIdentity()
                retryRequired = true
                Log.w(
                    TAG,
                    "Encrypted direct-message retry failed for " +
                        intent.conversationId + "/" + intent.messageId + ".",
                    error,
                )
            }
        }

        requireActiveIdentity()
        committedCount to retryRequired
        }
    }

    private suspend fun retryPendingGroupMessages(
        database: LocalChatDatabase,
        ownerUserId: String,
        senderDeviceId: String,
    ): Int = MegolmPendingGroupIntentRetryLock.mutex.withLock {
        val auth = FirebaseAuth.getInstance()
        val identity = DeviceIdentityRepository(applicationContext)
        fun requireActiveIdentity() {
            require(auth.currentUser?.uid == ownerUserId) {
                "Authenticated account changed during background group-message retry"
            }
        }

        requireActiveIdentity()
        require(identity.existingDeviceIdForDelivery() == senderDeviceId) {
            "Local sender device changed during background group-message retry"
        }

        val groupRepository = GroupChatRepository()
        val messageRepository = EncryptedGroupMessageRepository(database.encryptedGroupMessageDao())
        val coordinator = MegolmGroupMessageSendCoordinatorFactory(
            context = applicationContext,
            database = database,
            groupRepository = groupRepository,
        ).create()

        // Resume native-staged ciphertext first. This is idempotent and must
        // precede any attempt to advance the Megolm ratchet for a waiting intent.
        coordinator.recoverPendingMessages()
        var queuedCount = 0
        var retryRequired = false
        val groupIds = messageRepository.pendingOutgoingIntents(ownerUserId)
            .map { it.groupId }
            .distinct()
            .sorted()

        for (groupId in groupIds) {
            requireActiveIdentity()
            val membership = groupRepository.getGroupCryptoMembershipSnapshot(groupId)
            when (pendingGroupMembershipDecision(membership, groupId, ownerUserId)) {
                PendingGroupMembershipDecision.RETRY -> {
                    retryRequired = true
                    Log.w(TAG, "Pending encrypted group message is waiting for a valid membership snapshot; recovery will retry.")
                    continue
                }
                PendingGroupMembershipDecision.RETIRED -> {
                    Log.e(TAG, "Pending encrypted group message was retained because the sender is no longer a group member.")
                    continue
                }
                PendingGroupMembershipDecision.READY -> Unit
            }
            val currentMembership = requireNotNull(membership)

            // Restore committed timeline echoes independently. One damaged
            // marker must not prevent other groups from making progress.
            try {
                messageRepository.recoverCommittedOutgoingIntents(ownerUserId, groupId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                retryRequired = true
                Log.w(TAG, "Committed outgoing-message recovery failed for group " + groupId + ".", error)
                continue
            }

            val pending = messageRepository.pendingOutgoingIntents(ownerUserId)
                .filter { it.groupId == groupId }
            for (intent in pending) {
                try {
                    requireActiveIdentity()
                    require(identity.existingDeviceIdForDelivery() == senderDeviceId) {
                        "Local sender device changed during background group-message retry"
                    }
                    val latestMembership = groupRepository.getGroupCryptoMembershipSnapshot(groupId)
                    when (pendingGroupMembershipDecision(latestMembership, groupId, ownerUserId)) {
                        PendingGroupMembershipDecision.RETRY -> {
                            retryRequired = true
                            Log.w(TAG, "Group membership changed or became unavailable while retrying an encrypted message; recovery will retry.")
                            continue
                        }
                        PendingGroupMembershipDecision.RETIRED -> {
                            Log.e(TAG, "Encrypted group message remains local because the sender is no longer a group member.")
                            continue
                        }
                        PendingGroupMembershipDecision.READY -> Unit
                    }
                    if (!isPendingGroupIntentRetryMembershipCurrent(
                        expected = currentMembership,
                        latest = requireNotNull(latestMembership),
                        ownerUserId = ownerUserId,
                        groupId = groupId,
                    )) {
                        retryRequired = true
                        Log.i(TAG, "Group membership generation changed during pending-message recovery; retrying against the latest snapshot.")
                        continue
                    }

                    when (val outcome = coordinator.send(
                        groupId = groupId,
                        messageId = intent.messageId,
                        plaintext = intent.plaintext,
                        sentAtMillis = intent.sentAtMillis,
                    )) {
                        is MegolmGroupMessageSendOutcome.AwaitingKeyAcknowledgements -> {
                            // Keep this durable local intent on an active retry schedule.
                            // Otherwise it waits for the 15-minute periodic safety poll
                            // unless a separate acknowledgement wake happens to arrive.
                            retryRequired = true
                        }
                        is MegolmGroupMessageSendOutcome.Queued -> {
                            requireActiveIdentity()
                            require(identity.existingDeviceIdForDelivery() == senderDeviceId) {
                                "Local sender device changed before background timeline commit"
                            }
                            val queued = outcome.result
                            messageRepository.saveOutgoingMessageAndClearPendingIntent(
                                DecryptedGroupMessage(
                                    ownerUserId = ownerUserId,
                                    groupId = groupId,
                                    messageId = queued.messageId,
                                    membershipEpoch = queued.membershipEpoch,
                                    senderUserId = queued.senderUserId,
                                    senderDeviceId = queued.senderDeviceId,
                                    megolmSessionId = queued.megolmSessionId,
                                    messageIndex = queued.messageIndex,
                                    sentAtMillis = queued.sentAtMillis,
                                    text = intent.plaintext,
                                ),
                            )
                            queuedCount++
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // An account/device switch is a hard scope boundary, not an
                    // item-level failure. Stop immediately rather than iterating
                    // over the remaining intents under a stale worker identity.
                    requireActiveIdentity()
                    require(identity.existingDeviceIdForDelivery() == senderDeviceId) {
                        "Local sender device changed during background group-message retry"
                    }
                    // Continue the batch only while the original identity is still
                    // active, but request another WorkManager pass for transient errors.
                    retryRequired = true
                    Log.w(
                        TAG,
                        "Background encrypted group-message retry failed for " +
                            groupId + "/" + intent.messageId + ".",
                        error,
                    )
                }
            }
        }
        requireActiveIdentity()
        require(identity.existingDeviceIdForDelivery() == senderDeviceId) {
            "Local sender device changed before background group-message retry completed"
        }
        if (retryRequired) {
            throw IllegalStateException("One or more pending encrypted group messages need another recovery pass.")
        }
        queuedCount
    }

    companion object {
        private const val TAG = "EncryptedOlmDelivery"
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_SENDER_DEVICE_ID = "sender_device_id"
        private const val PERIODIC_INTERVAL_MINUTES = 15L
        private const val DEFAULT_RETRY_DELAY_MILLIS = 10_000L
        private const val WORK_TAG = "encrypted_olm_delivery"
        private val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        /**
         * Schedule immediate recovery plus a 15-minute poll for late recipient
         * acknowledgements and deliveries left queued while the app was stopped.
         */
        fun schedule(context: Context, ownerUserId: String, senderDeviceId: String) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }

            val appContext = context.applicationContext
            // The shared scheduler is the authoritative point where an account
            // and usable device identity are both known. Register this scope for
            // foreground inbox and ACK listeners as well as durable workers.
            EncryptedOlmForegroundInboxMonitor.setDeviceScope(
                context = appContext,
                accountUserId = ownerUserId,
                localDeviceId = senderDeviceId,
            )
            val data = workDataOf(
                KEY_OWNER_USER_ID to ownerUserId,
                KEY_SENDER_DEVICE_ID to senderDeviceId,
            )
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            scheduleImmediate(appContext, ownerUserId, senderDeviceId)

            val periodic = PeriodicWorkRequestBuilder<EncryptedOlmDeliveryWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setInputData(data)
                .addTag(WORK_TAG)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()

            val workManager = WorkManager.getInstance(appContext)
            workManager.enqueueUniquePeriodicWork(
                periodicWorkName(ownerUserId, senderDeviceId),
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
            )
        }

        /** Schedule an immediate sender pass independently of delayed retry wake-ups. */
        fun scheduleImmediate(context: Context, ownerUserId: String, senderDeviceId: String) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }
            val request = OneTimeWorkRequestBuilder<EncryptedOlmDeliveryWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_OWNER_USER_ID to ownerUserId,
                        KEY_SENDER_DEVICE_ID to senderDeviceId,
                    ),
                )
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                immediateWorkName(ownerUserId, senderDeviceId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        /**
         * Schedule an immediate delivery recovery pass after a local trust decision.
         * APPEND_OR_REPLACE prevents verification events during an active worker pass
         * from being discarded by the normal KEEP scheduling policy.
         */
        fun scheduleRecoveryAfterTrustChange(
            context: Context,
            ownerUserId: String,
            senderDeviceId: String,
        ) {
            scheduleAfterCurrentPass(context, ownerUserId, senderDeviceId)
        }

        /**
         * Schedule a follow-up after this worker has consumed an acknowledgement.
         * APPEND_OR_REPLACE is deliberate: KEEP would discard the request while
         * the current unique work is still RUNNING, losing the acknowledgement
         * wake-up and deferring group-key recovery to the periodic poll.
         */
        private fun scheduleAfterCurrentPass(
            context: Context,
            ownerUserId: String,
            senderDeviceId: String,
        ) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }
            val request = OneTimeWorkRequestBuilder<EncryptedOlmDeliveryWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(
                    workDataOf(
                        KEY_OWNER_USER_ID to ownerUserId,
                        KEY_SENDER_DEVICE_ID to senderDeviceId,
                    ),
                )
                .addTag(WORK_TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                immediateWorkName(ownerUserId, senderDeviceId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        /** Cancel both recovery jobs for one account/device without touching its Room outbox. */
        fun cancel(context: Context, ownerUserId: String, senderDeviceId: String) {
            require(ownerUserId.matches(USER_ID_PATTERN)) { "Owner user ID has an invalid format" }
            require(senderDeviceId.matches(DEVICE_ID_PATTERN)) { "Sender device ID has an invalid format" }

            val workManager = WorkManager.getInstance(context.applicationContext)
            workManager.cancelUniqueWork(immediateWorkName(ownerUserId, senderDeviceId))
            workManager.cancelUniqueWork(periodicWorkName(ownerUserId, senderDeviceId))
            EncryptedOlmDeliveryRetryWakeWorker.cancel(context, ownerUserId, senderDeviceId)
        }

        /** Cancel only encrypted delivery jobs when the Firebase auth scope changes. */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(WORK_TAG)
            EncryptedOlmDeliveryRetryWakeWorker.cancelAll(context)
        }

        private fun immediateWorkName(ownerUserId: String, senderDeviceId: String): String =
            "encrypted-olm-delivery-now-" + ownerUserId + "-" + senderDeviceId

        private fun periodicWorkName(ownerUserId: String, senderDeviceId: String): String =
            "encrypted-olm-delivery-periodic-" + ownerUserId + "-" + senderDeviceId
    }
}
