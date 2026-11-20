package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Olm operation required to wrap a Megolm ciphertext for one trusted device. */
internal interface OlmGroupMessageOutboundSessionRepository : OlmOutboundSessionRepository {
    fun encryptGroupMessageEnvelope(
        sessionId: String,
        payload: OlmGroupMessagePayload,
    ): String
}

internal data class PreparedOlmGroupMessageDelivery(
    val recipientUserId: String,
    val recipientDeviceId: String,
    val sessionId: String,
    /** Authenticated Olm envelope; the Megolm ciphertext remains opaque to the relay. */
    val envelope: String,
)

internal data class PreparedOlmGroupMessageDistribution(
    val messageId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val megolmSessionId: String,
    val deliveries: List<PreparedOlmGroupMessageDelivery>,
    val skippedUntrustedDeviceCount: Int,
)

/**
 * Produces a per-device Olm fan-out for one already-encrypted Megolm message.
 *
 * This is preparation only: a durable outbox must persist every delivery before
 * network submission, and the caller must re-check membership epoch before
 * treating the group send as complete.
 */
internal fun interface OlmGroupMessageDistributionPreparing {
    suspend fun prepare(
        membership: GroupCryptoMembershipSnapshot,
        messageId: String,
        encryptedMessage: EncryptedMegolmMessage,
        sentAtMillis: Long,
    ): PreparedOlmGroupMessageDistribution
}

internal class OlmGroupMessageDistributionPreparer(
    private val directory: OlmOutboundKeyDirectory,
    private val sessions: OlmGroupMessageOutboundSessionRepository,
    private val trustVerifier: OlmDeviceTrustVerifier,
) : OlmGroupMessageDistributionPreparing {
    override suspend fun prepare(
        membership: GroupCryptoMembershipSnapshot,
        messageId: String,
        encryptedMessage: EncryptedMegolmMessage,
        sentAtMillis: Long,
    ): PreparedOlmGroupMessageDistribution {
        require(membership.isValid()) { "Authoritative group membership snapshot is invalid" }
        val senderUserId = directory.authenticatedUserId
        val senderDeviceId = sessions.localDeviceId
        require(senderUserId in membership.memberUserIds) {
            "Authenticated sender is not a current group member"
        }

        val otherMembers = membership.memberUserIds - senderUserId
        val recipientDevices = coroutineScope {
            otherMembers.sorted().map { uid ->
                async { uid to directory.getUserDevices(uid) }
            }.awaitAll().toMap()
        }
        val senderDevices = directory.getUserDevices(senderUserId)
        val plan = OlmGroupSessionKeyDistributionPlanner.plan(
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            currentMemberUserIds = membership.memberUserIds,
            recipientDevicesByUser = recipientDevices,
            senderDevices = senderDevices,
            trustVerifier = trustVerifier,
        )

        val deliveries = plan.targets.map { target ->
            check(
                trustVerifier.isVerified(
                    peerUserId = target.userId,
                    peerDeviceId = target.device.deviceId,
                    peerEd25519IdentityKey = target.device.ed25519IdentityKey,
                )
            ) { "Recipient device trust changed before group-message preparation" }
            require(target.device.oneTimeKeys.size <= MAX_ONE_TIME_KEYS) {
                "Recipient device publishes too many one-time prekeys"
            }

            var claimedPreKey: SignedOlmPreKey? = null
            for (candidate in target.device.oneTimeKeys) {
                if (
                    directory.claimOneTimePreKey(
                        targetUid = target.userId,
                        deviceId = target.device.deviceId,
                        keyId = candidate.keyId,
                    )
                ) {
                    claimedPreKey = candidate
                    break
                }
            }
            val selectedPreKey = claimedPreKey ?: requireNotNull(target.device.fallbackKey) {
                "Recipient device has no signed fallback prekey"
            }
            val sessionId = sessions.createOutboundSession(
                remoteDevice = target.device,
                remotePreKey = selectedPreKey,
            )
            val payload = OlmGroupMessagePayload(
                messageId = messageId,
                groupId = membership.groupId,
                membershipEpoch = membership.epoch,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                recipientUserId = target.userId,
                recipientDeviceId = target.device.deviceId,
                megolmSessionId = encryptedMessage.sessionId,
                messageIndex = encryptedMessage.messageIndex,
                ciphertext = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(encryptedMessage.ciphertext),
                sentAtMillis = sentAtMillis,
            )
            OlmGroupMessagePayloadCodec.requireCurrentMembers(payload, membership.memberUserIds)
            val envelope = sessions.encryptGroupMessageEnvelope(sessionId, payload)
            PreparedOlmGroupMessageDelivery(
                recipientUserId = target.userId,
                recipientDeviceId = target.device.deviceId,
                sessionId = sessionId,
                envelope = envelope,
            )
        }

        return PreparedOlmGroupMessageDistribution(
            messageId = messageId,
            groupId = membership.groupId,
            membershipEpoch = membership.epoch,
            megolmSessionId = encryptedMessage.sessionId,
            deliveries = deliveries,
            skippedUntrustedDeviceCount = plan.skippedUntrustedDeviceCount,
        )
    }

    private companion object {
        const val MAX_ONE_TIME_KEYS = 20
    }
}
