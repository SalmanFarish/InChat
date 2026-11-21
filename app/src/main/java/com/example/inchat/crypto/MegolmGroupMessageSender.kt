package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/**
 * The supported sender-side entry point for isolated Megolm group text.
 *
 * It verifies that the acknowledged key-distribution object names the exact
 * local outbound session, stages ciphertext in native state, then hands that
 * stage to the durable per-device Olm outbox committer. Plaintext chat and UI
 * repositories are deliberately not dependencies of this class.
 */
internal class MegolmGroupMessageSender(
    private val sessions: MegolmSessionRepository,
    private val outboxCommitter: MegolmGroupMessageOutboxCommitter,
    /** Fail-closed check that all remote member devices acknowledged the exact session key. */
    private val requireKeyDistributionReady: suspend (
        membership: GroupCryptoMembershipSnapshot,
        keyDistribution: OlmGroupKeyDistributionReadinessToken,
    ) -> Unit,
) {
    suspend fun send(
        membership: GroupCryptoMembershipSnapshot,
        messageId: String,
        plaintext: String,
        sentAtMillis: Long,
        keyDistribution: OlmGroupKeyDistributionReadinessToken,
    ): MegolmGroupMessageOutboxCommitResult {
        require(membership.isValid()) {
            "Authoritative group membership snapshot is invalid"
        }
        // Verify current receipts and target roster before touching the Megolm ratchet.
        // The sender only uses the exact already-distributed session; it never creates
        // a substitute session as a side effect of a message send.
        requireKeyDistributionReady(membership, keyDistribution)
        val outboundSession = sessions.getExistingOutboundSession(membership)
        require(
            keyDistribution.groupId == membership.groupId &&
                keyDistribution.membershipEpoch == membership.epoch &&
                isOutboundMegolmSessionReadyFor(
                    session = outboundSession,
                    membership = membership,
                    targetDevices = keyDistribution.targetDevices,
                    expectedSessionId = keyDistribution.megolmSessionId,
                )
        ) {
            "The acknowledged Megolm session is not active for the current membership and trusted-device roster"
        }

        val staged = sessions.prepareOutboundMessage(
            membership = membership,
            messageId = messageId,
            plaintext = plaintext,
            sentAtMillis = sentAtMillis,
        )
        check(staged.encryptedMessage.sessionId == keyDistribution.megolmSessionId) {
            "Staged Megolm ciphertext does not use the distributed group session"
        }
        return outboxCommitter.commit(staged, keyDistribution)
    }

    /**
     * Resume a sender-side crash window using the ciphertext already persisted
     * with the native ratchet. This method never encrypts the plaintext again.
     */
    suspend fun resumePendingMessage(
        messageId: String,
        keyDistribution: OlmGroupKeyDistributionReadinessToken,
    ): MegolmGroupMessageOutboxCommitResult {
        val staged = sessions.readPendingOutboundMessage(messageId)
        require(
            staged.ownerUserId == keyDistribution.ownerUserId &&
                staged.senderDeviceId == keyDistribution.senderDeviceId &&
                staged.groupId == keyDistribution.groupId &&
                staged.membershipEpoch == keyDistribution.membershipEpoch &&
                staged.encryptedMessage.sessionId == keyDistribution.megolmSessionId
        ) {
            "Pending Megolm ciphertext does not match the supplied acknowledged session key"
        }
        return outboxCommitter.commit(staged, keyDistribution)
    }
}
