package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/** Result after every per-device Olm envelope is durably queued. */
internal data class MegolmGroupMessageOutboxCommitResult(
    val messageId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val queuedDeviceDeliveries: Int,
    val recipientDeviceCount: Int,
)

/**
 * Connects an already-staged native Megolm ciphertext to the existing per-device
 * Olm outbox. The native staging entry is cleared only after the full Room
 * outbox operation succeeds. Any failure leaves the exact ciphertext recoverable.
 *
 * This is a crypto/transport coordinator only; it does not call the normal chat
 * repository, plaintext Firebase chat path, or group UI.
 */
internal class MegolmGroupMessageOutboxCommitter(
    private val membershipForGroup: suspend (groupId: String) -> GroupCryptoMembershipSnapshot?,
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
    private val distributionPreparer: OlmGroupMessageDistributionPreparing,
    /** Final live SAS-trusted roster check immediately before outbox insertion. */
    private val currentTrustedTargetDevices: suspend (
        membership: GroupCryptoMembershipSnapshot,
    ) -> Set<Pair<String, String>>,
    private val outboxCoordinator: OlmGroupMessageOutboxCoordinator,
    private val finalizeStagedMessage: (messageId: String) -> Boolean,
) {
    suspend fun commit(
        staged: StagedOutboundMegolmMessage,
        keyDistribution: OlmGroupKeyDistributionReadinessToken,
    ): MegolmGroupMessageOutboxCommitResult {
        val firstSnapshot = requireNotNull(membershipForGroup(staged.groupId)) {
            "Group has no current E2EE membership snapshot"
        }
        require(firstSnapshot.isValid() && firstSnapshot.groupId == staged.groupId) {
            "Current E2EE group membership snapshot is invalid"
        }
        val ownerUid = authenticatedUserId()
        val deviceId = localDeviceId()
        require(ownerUid == staged.ownerUserId && ownerUid == staged.senderUserId &&
            ownerUid == keyDistribution.ownerUserId &&
            deviceId == staged.senderDeviceId &&
            deviceId == keyDistribution.senderDeviceId
        ) {
            "Staged Megolm message or key token belongs to another account/device"
        }
        require(firstSnapshot.epoch == staged.membershipEpoch &&
            keyDistribution.groupId == staged.groupId &&
            keyDistribution.membershipEpoch == staged.membershipEpoch &&
            keyDistribution.megolmSessionId == staged.encryptedMessage.sessionId
        ) {
            "Staged Megolm message does not match the acknowledged key or membership epoch"
        }
        require(ownerUid in firstSnapshot.memberUserIds) {
            "Authenticated sender is no longer a group member"
        }

        val distribution = distributionPreparer.prepare(
            membership = firstSnapshot,
            messageId = staged.messageId,
            encryptedMessage = staged.encryptedMessage,
            sentAtMillis = staged.sentAtMillis,
            expectedTargetDevices = keyDistribution.targetDevices,
        )

        // Re-resolve the live SAS-trusted device set after envelope creation.
        // This closes the directory/trust race before Room outbox insertion.
        val latestTrustedTargets = currentTrustedTargetDevices(firstSnapshot)
        require(latestTrustedTargets == keyDistribution.targetDevices) {
            "Trusted group devices changed while preparing the encrypted message"
        }

        // Re-read the atomic membership snapshot after key-directory/trust
        // lookups and Olm envelope preparation. If any member/role changed meanwhile,
        // preserve the native ciphertext for explicit recovery; do not enqueue it.
        val latestSnapshot = requireNotNull(membershipForGroup(staged.groupId)) {
            "Group membership snapshot disappeared before outbox commit"
        }
        require(latestSnapshot.isValid() &&
            latestSnapshot.groupId == staged.groupId &&
            latestSnapshot.epoch == staged.membershipEpoch &&
            latestSnapshot.members == firstSnapshot.members
        ) {
            "Group membership changed while preparing the encrypted message"
        }

        val queued = outboxCoordinator.enqueue(
            currentMembership = latestSnapshot,
            distribution = distribution,
            keyDistribution = keyDistribution,
        )
        // The outbox coordinator returns only after its Room transaction commits.
        // The native retry copy survives any earlier failure or process death.
        check(finalizeStagedMessage(staged.messageId)) {
            "Group ciphertext is queued, but its native staged copy could not be finalized; retry recovery"
        }
        return MegolmGroupMessageOutboxCommitResult(
            messageId = staged.messageId,
            groupId = staged.groupId,
            membershipEpoch = staged.membershipEpoch,
            queuedDeviceDeliveries = queued,
            recipientDeviceCount = distribution.deliveries.size,
        )
    }
}
