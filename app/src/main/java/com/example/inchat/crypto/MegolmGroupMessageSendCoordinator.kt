package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes the complete pending-intent -> Megolm send -> timeline commit transaction. */
internal object MegolmPendingGroupIntentRetryLock {
    val mutex = Mutex()
}

/**
 * A pending intent may advance the Megolm ratchet only while its captured
 * membership generation and roster remain current. Invalid snapshots stay durable.
 */
internal fun isPendingGroupIntentRetryMembershipCurrent(
    expected: GroupCryptoMembershipSnapshot,
    latest: GroupCryptoMembershipSnapshot?,
    ownerUserId: String,
    groupId: String,
): Boolean {
    if (!expected.isValid() || expected.groupId != groupId ||
        ownerUserId !in expected.memberUserIds
    ) return false
    return latest != null && latest.isValid() &&
        latest.groupId == expected.groupId &&
        latest.epoch == expected.epoch &&
        latest.members == expected.members &&
        ownerUserId in latest.memberUserIds
}

/** Result of a guarded group-message send attempt. */
internal sealed class MegolmGroupMessageSendOutcome {
    data class AwaitingMailboxAcceptance(
        val groupId: String,
        val distributionId: String,
        val pendingTargets: Set<Pair<String, String>>,
        val newlyQueuedKeyDeliveries: Int,
    ) : MegolmGroupMessageSendOutcome()

    data class Queued(
        val result: MegolmGroupMessageOutboxCommitResult,
    ) : MegolmGroupMessageSendOutcome()
}

/**
 * Joins the existing group-key distributor, durable receipt readiness gate and
 * isolated Megolm sender into one operation.
 *
 * If the current session has no saved key-distribution metadata, this starts
 * distribution once. It advances the Megolm ratchet only after every target
 * mailbox has accepted the pairwise-encrypted key envelope. Offline devices do
 * not block sends; their mailbox retains key then group message in order.
 *
 * GroupChatViewModel invokes this coordinator for group sends; the legacy plaintext
 * group-message upload path is deliberately bypassed.
 */
internal class MegolmGroupMessageSendCoordinator(
    private val membershipForGroup: suspend (groupId: String) -> GroupCryptoMembershipSnapshot?,
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
    private val currentTrustedTargetDevices: suspend (
        membership: GroupCryptoMembershipSnapshot,
    ) -> Set<Pair<String, String>>,
    private val ensureOutboundSession: (
        membership: GroupCryptoMembershipSnapshot,
        trustedTargets: Set<Pair<String, String>>,
    ) -> OutboundMegolmSessionInfo,
    private val evaluateKeyReadiness: (
        ownerUserId: String,
        senderDeviceId: String,
        membership: GroupCryptoMembershipSnapshot,
        megolmSessionId: String,
    ) -> OlmGroupKeyDistributionReadinessState,
    private val distributeKey: suspend (
        groupId: String,
        distributionId: String,
        issuedAtMillis: Long,
    ) -> MegolmGroupKeyDistributionResult,
    private val sendEncryptedMessage: suspend (
        membership: GroupCryptoMembershipSnapshot,
        messageId: String,
        plaintext: String,
        sentAtMillis: Long,
        keyDistribution: OlmGroupKeyDistributionReadinessToken,
    ) -> MegolmGroupMessageOutboxCommitResult,
    private val recoverPendingOutboundMessages: suspend () -> Int = { 0 },
    private val observeAcknowledgementCount: (String, String) -> Flow<Int> = { _, _ -> flowOf(0) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ensureLocalDevicePublished: suspend () -> Unit = {},
    private val dispatchGroupKeyDistributionImmediately: suspend (String, String, Long) -> Unit = { _, _, _ -> },
    private val dispatchGroupMessageImmediately: suspend (String, String, Long) -> Unit = { _, _, _ -> },
    private val onTiming: (String, Long) -> Unit = { _, _ -> },
) {
    /** Resume exact native-staged ciphertexts after process death; never re-encrypt. */
    suspend fun recoverPendingMessages(): Int = sendMutex.withLock {
        recoverPendingOutboundMessages()
    }

    fun observeAcknowledgementChanges(ownerUserId: String): Flow<Int> {
        require(ownerUserId.matches(ID)) { "Acknowledgement observer owner ID is invalid" }
        require(authenticatedUserId() == ownerUserId) {
            "Authenticated account does not own the acknowledgement observer"
        }
        val senderDevice = localDeviceId()
        require(senderDevice.matches(DEVICE_ID)) { "Acknowledgement observer device ID is invalid" }
        return observeAcknowledgementCount(ownerUserId, senderDevice)
    }

    suspend fun send(
        groupId: String,
        messageId: String,
        plaintext: String,
        sentAtMillis: Long,
    ): MegolmGroupMessageSendOutcome = sendMutex.withLock {
        sendLocked(groupId, messageId, plaintext, sentAtMillis)
    }

    private suspend fun sendLocked(
        groupId: String,
        messageId: String,
        plaintext: String,
        sentAtMillis: Long,
    ): MegolmGroupMessageSendOutcome {
        require(groupId.matches(ID)) { "Group ID is invalid" }
        require(messageId.matches(ID)) { "Group message ID is invalid" }
        require(plaintext.isNotEmpty()) { "Group message cannot be empty" }
        require(sentAtMillis >= 0L) { "Group message timestamp is invalid" }

        val requestedOwnerUid = authenticatedUserId()
        measure("local_device_publication") { ensureLocalDevicePublished() }
        val ownerUid = authenticatedUserId()
        require(ownerUid == requestedOwnerUid) {
            "Authenticated account changed while preparing secure group messaging"
        }
        val senderDevice = localDeviceId()
        require(ownerUid.matches(ID) && senderDevice.matches(DEVICE_ID)) {
            "Authenticated group sender identity is invalid"
        }
        val membership = measure("membership_snapshot") {
            requireNotNull(membershipForGroup(groupId)) {
                "Current E2EE group membership snapshot is unavailable"
            }
        }
        require(membership.isValid() && membership.groupId == groupId) {
            "Current E2EE group membership snapshot is invalid"
        }
        require(ownerUid in membership.memberUserIds) {
            "Authenticated sender is no longer a group member"
        }

        val trustedTargets = measure("trusted_device_lookup") { currentTrustedTargetDevices(membership) }
        require(authenticatedUserId() == ownerUid && localDeviceId() == senderDevice) {
            "Authenticated group sender identity changed during trusted-device lookup"
        }
        requireValidTargets(membership, ownerUid, senderDevice, trustedTargets)
        val session = measure("megolm_session_prepare") { ensureOutboundSession(membership, trustedTargets) }
        require(authenticatedUserId() == ownerUid && localDeviceId() == senderDevice) {
            "Authenticated group sender identity changed while preparing the outbound session"
        }
        val expectedFingerprint = GroupDeviceRosterFingerprint.create(membership, trustedTargets)
        require(
            session.groupId == groupId &&
                session.membershipEpoch == membership.epoch &&
                session.sessionId.matches(SESSION_ID) &&
                session.targetRosterFingerprint == expectedFingerprint
        ) {
            "Active Megolm session is not bound to the current membership and trusted-device roster"
        }
        val distributionId = OlmGroupSessionKeyDistributionId.create(
            membership = membership,
            senderUserId = ownerUid,
            senderDeviceId = senderDevice,
            megolmSessionId = session.sessionId,
        )

        var state = measure("group_key_readiness") {
            evaluateKeyReadiness(ownerUid, senderDevice, membership, session.sessionId)
        }
        var newlyQueued = 0
        if (state is OlmGroupKeyDistributionReadinessState.Missing) {
            require(state.distributionId == distributionId) {
                "Readiness lookup returned a different group-key distribution ID"
            }
            val distributionResult = measure("group_key_prepare_and_queue") {
                distributeKey(groupId, distributionId, nowMillis().coerceAtLeast(0L))
            }
            require(
                distributionResult.distributionId == distributionId &&
                    distributionResult.groupId == groupId &&
                    distributionResult.membershipEpoch == membership.epoch &&
                    distributionResult.megolmSessionId == session.sessionId
            ) {
                "Group-key distributor returned metadata for a different session"
            }
            newlyQueued = distributionResult.newlyQueuedDeliveryCount
            // Attempt immediate delivery for the new key envelopes before checking
            // readiness again. WorkManager remains the retry/ACK/recovery authority.
            measure("foreground_group_key_delivery") {
                dispatchGroupKeyDistributionImmediately(groupId, distributionId, membership.epoch)
            }

            // Do not proceed using the pre-distribution snapshot if membership or
            // trust changed while key envelopes were being prepared and queued.
            val latestMembership = requireNotNull(membershipForGroup(groupId)) {
                "Group membership disappeared during key distribution"
            }
            requireSameMembership(membership, latestMembership)
            require(currentTrustedTargetDevices(latestMembership) == trustedTargets) {
                "Trusted group devices changed during key distribution"
            }
            state = measure("group_key_readiness_after_foreground_delivery") {
                evaluateKeyReadiness(ownerUid, senderDevice, latestMembership, session.sessionId)
            }
        }

        when (state) {
            is OlmGroupKeyDistributionReadinessState.Missing ->
                error("Group-key distributor completed without persisting its distribution metadata")

            is OlmGroupKeyDistributionReadinessState.AwaitingMailboxAcceptance -> {
                require(
                    state.distributionId == distributionId &&
                        state.pendingTargets.isNotEmpty() &&
                        trustedTargets.containsAll(state.pendingTargets)
                ) {
                    "Pending mailbox targets do not match the active group-key distribution"
                }
                return MegolmGroupMessageSendOutcome.AwaitingMailboxAcceptance(
                    groupId = groupId,
                    distributionId = distributionId,
                    pendingTargets = state.pendingTargets,
                    newlyQueuedKeyDeliveries = newlyQueued,
                )
            }

            is OlmGroupKeyDistributionReadinessState.Ready -> {
                val token = state.token
                require(
                    token.ownerUserId == ownerUid &&
                        token.senderDeviceId == senderDevice &&
                        token.distributionId == distributionId &&
                        token.groupId == groupId &&
                        token.membershipEpoch == membership.epoch &&
                        token.megolmSessionId == session.sessionId &&
                        token.targetDevices == trustedTargets
                ) {
                    "Durable key-readiness token does not match the active session or trusted roster"
                }
                val latestMembership = requireNotNull(membershipForGroup(groupId)) {
                    "Group membership disappeared before encrypted message send"
                }
                requireSameMembership(membership, latestMembership)
                require(currentTrustedTargetDevices(latestMembership) == token.targetDevices) {
                    "Trusted group devices changed before encrypted message send"
                }
                require(authenticatedUserId() == ownerUid && localDeviceId() == senderDevice) {
                    "Authenticated group sender identity changed before encrypted message send"
                }
                val result = measure("group_message_encrypt_and_commit") {
                    sendEncryptedMessage(latestMembership, messageId, plaintext, sentAtMillis, token)
                }
                require(
                    result.messageId == messageId &&
                        result.groupId == groupId &&
                        result.membershipEpoch == membership.epoch
                ) {
                    "Encrypted group sender returned a result for a different message or group"
                }
                measure("foreground_group_message_delivery") {
                    dispatchGroupMessageImmediately(groupId, messageId, membership.epoch)
                }
                return MegolmGroupMessageSendOutcome.Queued(result)
            }
        }
    }

    private suspend fun <T> measure(stage: String, operation: suspend () -> T): T {
        val startedAtNanos = System.nanoTime()
        return try {
            operation()
        } finally {
            val elapsedMillis = ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)
            runCatching { onTiming(stage, elapsedMillis) }
        }
    }

    private fun requireValidTargets(
        membership: GroupCryptoMembershipSnapshot,
        ownerUid: String,
        senderDevice: String,
        targets: Set<Pair<String, String>>,
    ) {
        require(targets.isNotEmpty() && targets.size <= MAX_TARGETS) {
            "Current trusted group device roster has an invalid size"
        }
        require(targets.all { (userId, deviceId) ->
            userId in membership.memberUserIds &&
                userId.matches(ID) &&
                deviceId.matches(DEVICE_ID) &&
                !(userId == ownerUid && deviceId == senderDevice)
        }) {
            "Current trusted group device roster contains an invalid or non-member target"
        }
        require((membership.memberUserIds - ownerUid).all { memberId ->
            targets.any { it.first == memberId }
        }) {
            "Every current remote group member must have a trusted device"
        }
    }

    private fun requireSameMembership(
        expected: GroupCryptoMembershipSnapshot,
        latest: GroupCryptoMembershipSnapshot,
    ) {
        require(
            latest.isValid() &&
                latest.groupId == expected.groupId &&
                latest.epoch == expected.epoch &&
                latest.members == expected.members
        ) {
            "Group membership changed during encrypted group send preparation"
        }
    }

    private companion object {
        val sendMutex = Mutex()
        const val MAX_TARGETS = MAX_OLM_DEVICE_DELIVERY_TARGETS
        val ID = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
    }
}
