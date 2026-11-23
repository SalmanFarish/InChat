package com.example.inchat.crypto

import android.content.Context
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.repository.GroupChatRepository
import com.example.inchat.data.worker.EncryptedOlmDeliveryWorker
import com.google.firebase.auth.FirebaseAuth

/**
 * Builds the production Android adapters for the isolated Megolm group sender.
 *
 * Device publication and SAS verification remain explicit setup operations in
 * Device Verification. This factory never silently creates trust or publishes
 * a peer identity; send preflight fails closed until every current member has a
 * usable, SAS-trusted device.
 */
internal class MegolmGroupMessageSendCoordinatorFactory(
    context: Context,
    private val database: LocalChatDatabase,
    private val groupRepository: GroupChatRepository,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    private val appContext = context.applicationContext

    fun create(): MegolmGroupMessageSendCoordinator {
        val identity = DeviceIdentityRepository(appContext)
        val directory = DeviceKeyDirectoryRepository(auth = auth)
        val trust = TrustedDeviceRepository(appContext)
        val olmSessions = OlmSessionRepository(appContext, identity)
        val megolmSessions = MegolmSessionRepository(
            context = appContext,
            identityRepository = identity,
            authenticatedUserId = { auth.currentUser?.uid.orEmpty() },
        )
        val queue = OlmDeliveryQueue(
            RoomOlmDeliveryQueueStore(database, database.olmDeliveryQueueDao()),
        )
        val authenticatedUserId = { auth.currentUser?.uid.orEmpty() }
        val localDeviceId = { identity.currentDeviceId() }
        val scheduleDelivery: (String, String) -> Unit = { ownerUserId, senderDeviceId ->
            EncryptedOlmDeliveryWorker.schedule(appContext, ownerUserId, senderDeviceId)
        }

        val readiness = database.olmGroupKeyDistributionReadiness()
        val messageDistributionPreparer = OlmGroupMessageDistributionPreparer(
            directory = directory,
            sessions = olmSessions,
            trustVerifier = trust,
        )
        val keyDistributionPreparer = OlmGroupSessionKeyDistributionPreparer(
            directory = directory,
            sessions = olmSessions,
            trustVerifier = trust,
        )
        val recoveryStateReader = RoomOlmGroupKeyDistributionRecoveryStateStore(
            database = database,
            queueDao = database.olmDeliveryQueueDao(),
            receiptDao = database.olmDeliveryReceiptDao(),
        )
        val keyDistributionOutbox = OlmGroupSessionKeyOutboxCoordinator(
            queue = queue,
            metadataStore = database.olmGroupKeyDistributionMetadataStore(),
            deliveryReceipts = database.olmDeliveryReceiptDao(),
            authenticatedUserId = authenticatedUserId,
            localDeviceId = localDeviceId,
            scheduleDelivery = scheduleDelivery,
            recoveryStateReader = recoveryStateReader,
        )
        val keyDistributor = MegolmGroupSessionKeyDistributor(
            membershipForGroup = groupRepository::getGroupCryptoMembershipSnapshot,
            authenticatedUserId = authenticatedUserId,
            localDeviceId = localDeviceId,
            currentTrustedTargetDevices = messageDistributionPreparer::currentTrustedTargetDevices,
            ensureOutboundSession = megolmSessions::ensureOutboundSession,
            exportOutboundSessionKey = { membership, sessionId ->
                megolmSessions.exportOutboundSessionKey(membership, sessionId)
            },
            prepareDistribution = { distributionId, membership, sessionId, sessionKey, issuedAtMillis ->
                keyDistributionPreparer.prepare(
                    distributionId = distributionId,
                    membership = membership,
                    megolmSessionId = sessionId,
                    sessionKey = sessionKey,
                    issuedAtMillis = issuedAtMillis,
                )
            },
            enqueueDistribution = keyDistributionOutbox::enqueue,
            resumeDistribution = keyDistributionOutbox::resume,
        )
        val messageOutbox = OlmGroupMessageOutboxCoordinator(
            queue = queue,
            authenticatedUserId = authenticatedUserId,
            localDeviceId = localDeviceId,
            keyDistributionReadiness = readiness,
            scheduleDelivery = scheduleDelivery,
        )
        val messageCommitter = MegolmGroupMessageOutboxCommitter(
            membershipForGroup = groupRepository::getGroupCryptoMembershipSnapshot,
            authenticatedUserId = authenticatedUserId,
            localDeviceId = localDeviceId,
            distributionPreparer = messageDistributionPreparer,
            currentTrustedTargetDevices = messageDistributionPreparer::currentTrustedTargetDevices,
            outboxCoordinator = messageOutbox,
            finalizeStagedMessage = megolmSessions::finalizeOutboundMessage,
        )
        val sendPreflight = RoomOlmGroupMessageSendPreflight(
            readiness = readiness,
            authenticatedUserId = authenticatedUserId,
            localDeviceId = localDeviceId,
            currentTrustedTargets = messageDistributionPreparer::currentTrustedTargetDevices,
        )
        val sender = MegolmGroupMessageSender(
            sessions = megolmSessions,
            outboxCommitter = messageCommitter,
            requireKeyDistributionReady = sendPreflight::requireReady,
        )

        return MegolmGroupMessageSendCoordinator(
            membershipForGroup = groupRepository::getGroupCryptoMembershipSnapshot,
            authenticatedUserId = authenticatedUserId,
            localDeviceId = localDeviceId,
            currentTrustedTargetDevices = messageDistributionPreparer::currentTrustedTargetDevices,
            ensureOutboundSession = megolmSessions::ensureOutboundSession,
            evaluateKeyReadiness = { ownerUserId, senderDeviceId, membership, sessionId ->
                readiness.evaluate(ownerUserId, senderDeviceId, membership, sessionId)
            },
            distributeKey = keyDistributor::distribute,
            sendEncryptedMessage = sender::send,
            observeAcknowledgementCount = { owner, device ->
                database.olmDeliveryReceiptDao().observeAcknowledgementCount(owner, device)
            },
            recoverPendingOutboundMessages = {
                val owner = authenticatedUserId()
                val device = localDeviceId()
                require(owner.isNotBlank() && device.isNotBlank()) {
                    "Authenticated sender identity is unavailable for staged-message recovery"
                }
                var recovered = 0
                var firstFailure: Exception? = null
                for (messageId in megolmSessions.pendingOutboundMessageIds()) {
                    try {
                        require(authenticatedUserId() == owner && localDeviceId() == device) {
                            "Authenticated sender identity changed during staged-message recovery"
                        }
                        val staged = megolmSessions.readPendingOutboundMessage(messageId)
                        // The native store may contain entries outside this worker's
                        // account/device scope. Never inspect or resume those entries.
                        if (staged.ownerUserId != owner ||
                            staged.senderUserId != owner ||
                            staged.senderDeviceId != device
                        ) continue
                        val membership = groupRepository.getGroupCryptoMembershipSnapshot(staged.groupId)
                            ?: continue
                        if (!membership.isValid() ||
                            membership.groupId != staged.groupId ||
                            membership.epoch != staged.membershipEpoch ||
                            owner !in membership.memberUserIds
                        ) continue
                        val readinessState = readiness.evaluate(
                            owner,
                            device,
                            membership,
                            staged.encryptedMessage.sessionId,
                        )
                        val token = (readinessState as? OlmGroupKeyDistributionReadinessState.Ready)
                            ?.token ?: continue
                        require(authenticatedUserId() == owner && localDeviceId() == device) {
                            "Authenticated sender identity changed before staged-message recovery"
                        }
                        sender.resumePendingMessage(messageId, membership, token)
                        recovered++
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // One corrupt/stale staging entry must not starve later
                        // recoverable messages. Preserve retry after the full pass.
                        if (firstFailure == null) firstFailure = error
                    }
                }
                firstFailure?.let { throw it }
                recovered
            },
        )
    }
}
