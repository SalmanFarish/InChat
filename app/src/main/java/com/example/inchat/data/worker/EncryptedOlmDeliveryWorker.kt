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
import com.example.inchat.data.repository.CloudflareNotificationRepository
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
        when (deviceScopeDecision) {
            EncryptedOlmDeviceScopeDecision.DEFER_LOOKUP -> {
                // Preserve the schedule for transient storage failures or a temporary
                // identity-transfer lock; a later pass can recover after the lock clears.
                Log.e(
                    TAG,
                    "The local encrypted device identity is unavailable; delivery is paused.",
                    identityLookup.exceptionOrNull(),
                )
                return Result.success()
            }
            EncryptedOlmDeviceScopeDecision.RETIRE_STALE_SCOPE -> {
                // A missing/retired native identity cannot ever service this schedule.
                // Cancel only its WorkManager jobs; keep durable Room deliveries intact.
                Log.i(TAG, "Retiring encrypted delivery for a missing or inactive device identity.")
                cancel(applicationContext, ownerUserId, senderDeviceId)
                return Result.success()
            }
            EncryptedOlmDeviceScopeDecision.KEEP_ACTIVE -> Unit
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
        return try {
            val database = LocalChatDatabase.getInstance(applicationContext)
            val queue = OlmDeliveryQueue(
                RoomOlmDeliveryQueueStore(database, database.olmDeliveryQueueDao()),
            )
            val metadataStore = database.olmGroupKeyDistributionMetadataStore()
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
                val groupRepository = GroupChatRepository()
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

            // Recover exact native-staged Megolm ciphertexts before draining
            // the Olm queue. A process death after ratchet advancement but before
            // Room outbox commit must not strand or re-encrypt that message.
            try {
                val recoveryCoordinator = MegolmGroupMessageSendCoordinatorFactory(
                    context = applicationContext,
                    database = database,
                    groupRepository = GroupChatRepository(),
                ).create()
                val recovered = recoveryCoordinator.recoverPendingMessages()
                if (recovered > 0) {
                    Log.i(TAG, "Recovered " + recovered + " staged encrypted group messages.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                groupKeyRecoveryRetryRequired = true
                Log.w(TAG, "Staged encrypted group-message recovery failed; retrying.", error)
            }

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
            val groupRepository = GroupChatRepository()
            val notifications = CloudflareNotificationRepository()
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
                    // Key-distribution acknowledgements can make a previously
                    // blocked Megolm send ready. Queue a distinct follow-up pass
                    // behind this running worker so recovery runs immediately
                    // instead of waiting for the 15-minute safety poll.
                    scheduleAfterCurrentPass(
                        applicationContext,
                        ownerUserId,
                        senderDeviceId,
                    )
                },
                syncEncryptedDirectPreview = { delivery ->
                    if (delivery.groupId == null && delivery.recipientUserId != ownerUserId) {
                        val conversationId = requireNotNull(delivery.conversationId) {
                            "Encrypted direct delivery is missing its conversation scope"
                        }
                        val commit = requireNotNull(
                            database.olmDeliveryQueueDao().getOutgoingDirectCommit(
                                ownerUserId = ownerUserId,
                                conversationId = conversationId,
                                messageId = delivery.messageId,
                            ),
                        ) {
                            "Encrypted direct delivery has no immutable outgoing commit marker"
                        }
                        notifications.syncEncryptedDirectMessagePreview(
                            senderUserId = ownerUserId,
                            chatId = conversationId,
                            messageId = delivery.messageId,
                            sentAtMillis = commit.sentAtMillis,
                            deliveryId = delivery.deliveryId,
                            senderDeviceId = senderDeviceId,
                            recipientDeviceId = delivery.recipientDeviceId,
                        ).getOrThrow()
                    }
                },
                syncEncryptedGroupPreview = groupPreview@{ delivery ->
                    val groupId = delivery.groupId
                    if (groupId != null && delivery.recipientUserId != ownerUserId) {
                        // Group-key distributions use the same Olm device outbox
                        // but are not chat messages. Only a committed Megolm
                        // message may update the conversation preview.
                        val commit = database.olmDeliveryQueueDao().getOutgoingGroupCommit(
                            ownerUserId = ownerUserId,
                            groupId = groupId,
                            messageId = delivery.messageId,
                        ) ?: return@groupPreview
                        check(
                            commit.senderDeviceId == senderDeviceId &&
                                commit.membershipEpoch == delivery.membershipEpoch,
                        ) {
                            "Encrypted group delivery does not match its durable group-message commit"
                        }
                        notifications.syncEncryptedGroupMessagePreview(
                            senderUserId = ownerUserId,
                            groupId = groupId,
                            messageId = delivery.messageId,
                            sentAtMillis = commit.sentAtMillis,
                            membershipEpoch = commit.membershipEpoch,
                            deliveryId = delivery.deliveryId,
                            recipientUserId = delivery.recipientUserId,
                            senderDeviceId = senderDeviceId,
                            recipientDeviceId = delivery.recipientDeviceId,
                        ).getOrThrow()
                    }
                },
                notifyEncryptedRecipient = { delivery ->
                    if (delivery.groupId == null) {
                        notifications.notifyEncryptedDelivery(
                            senderUserId = ownerUserId,
                            chatId = requireNotNull(delivery.conversationId) {
                                "Encrypted delivery is missing its authenticated conversation scope"
                            },
                            recipientUserId = delivery.recipientUserId,
                            deliveryId = delivery.deliveryId,
                            messageId = delivery.messageId,
                            senderDeviceId = senderDeviceId,
                            recipientDeviceId = delivery.recipientDeviceId,
                        ).getOrThrow()
                    } else {
                        notifications.notifyEncryptedGroupDelivery(
                            senderUserId = ownerUserId,
                            groupId = delivery.groupId,
                            membershipEpoch = requireNotNull(delivery.membershipEpoch) {
                                "Encrypted group delivery is missing its membership epoch"
                            },
                            recipientUserId = delivery.recipientUserId,
                            deliveryId = delivery.deliveryId,
                            messageId = delivery.messageId,
                            senderDeviceId = senderDeviceId,
                            recipientDeviceId = delivery.recipientDeviceId,
                        ).getOrThrow()
                    }
                },
            ).run()

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
            val retriedGroupMessages = retryPendingGroupMessages(
                database = database,
                ownerUserId = ownerUserId,
                senderDeviceId = senderDeviceId,
            )
            if (retriedGroupMessages > 0) {
                Log.i(TAG, "Automatically queued " + retriedGroupMessages + " pending encrypted group messages.")
            }
            if (result.retryRequired || groupKeyRecoveryRetryRequired || directPendingRetryRequired) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Encrypted relay delivery will be retried.", error)
            Result.retry()
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
    ): Pair<Int, Boolean> = EncryptedDirectPendingMessageRetryLock.mutex.withLock {
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
        if (pending.isEmpty()) return 0 to false

        val sender = OlmDirectMessageSendCoordinatorFactory(
            context = applicationContext,
            database = database,
            auth = auth,
        ).create()
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
        return committedCount to retryRequired
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
                ?: continue
            if (!membership.isValid() || membership.groupId != groupId ||
                ownerUserId !in membership.memberUserIds
            ) continue

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
                        ?: continue
                    if (!isPendingGroupIntentRetryMembershipCurrent(
                        expected = membership,
                        latest = latestMembership,
                        ownerUserId = ownerUserId,
                        groupId = groupId,
                    )) continue

                    when (val outcome = coordinator.send(
                        groupId = groupId,
                        messageId = intent.messageId,
                        plaintext = intent.plaintext,
                        sentAtMillis = intent.sentAtMillis,
                    )) {
                        is MegolmGroupMessageSendOutcome.AwaitingKeyAcknowledgements -> Unit
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
            val data = workDataOf(
                KEY_OWNER_USER_ID to ownerUserId,
                KEY_SENDER_DEVICE_ID to senderDeviceId,
            )
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val immediate = OneTimeWorkRequestBuilder<EncryptedOlmDeliveryWorker>()
                .setInputData(data)
                .addTag(WORK_TAG)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .build()

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
            workManager.enqueueUniqueWork(
                immediateWorkName(ownerUserId, senderDeviceId),
                ExistingWorkPolicy.KEEP,
                immediate,
            )
            workManager.enqueueUniquePeriodicWork(
                periodicWorkName(ownerUserId, senderDeviceId),
                ExistingPeriodicWorkPolicy.KEEP,
                periodic,
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
        }

        /** Cancel only encrypted delivery jobs when the Firebase auth scope changes. */
        fun cancelAll(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(WORK_TAG)
        }

        private fun immediateWorkName(ownerUserId: String, senderDeviceId: String): String =
            "encrypted-olm-delivery-now-" + ownerUserId + "-" + senderDeviceId

        private fun periodicWorkName(ownerUserId: String, senderDeviceId: String): String =
            "encrypted-olm-delivery-periodic-" + ownerUserId + "-" + senderDeviceId
    }
}
