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
        val outboundSession = sessions.ensureOutboundSession(membership)
        require(
            keyDistribution.groupId == membership.groupId &&
                keyDistribution.membershipEpoch == membership.epoch &&
                keyDistribution.megolmSessionId == outboundSession.sessionId
        ) {
            "The current outbound Megolm session key has not been distributed for this membership epoch"
        }

        // Check durable key receipts before advancing the native Megolm ratchet.
        // The outbox committer repeats the check after envelope preparation.
        requireKeyDistributionReady(membership, keyDistribution)

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
