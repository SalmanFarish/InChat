package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Additional Olm operation needed to encrypt one typed group control payload. */
internal interface OlmGroupKeyOutboundSessionRepository : OlmOutboundSessionRepository {
    fun encryptGroupSessionKeyDistributionEnvelope(
        sessionId: String,
        payload: OlmGroupSessionKeyDistributionPayload,
    ): String
}

internal data class PreparedOlmGroupKeyDelivery(
    val recipientUserId: String,
    val recipientDeviceId: String,
    val sessionId: String,
    /** An Olm envelope; it contains no plaintext Megolm session key. */
    val envelope: String,
)

internal data class PreparedOlmGroupKeyDistribution(
    val distributionId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val deliveries: List<PreparedOlmGroupKeyDelivery>,
    val skippedUntrustedDeviceCount: Int,
)

/**
 * Creates one separately authenticated Olm envelope for each SAS-trusted
 * device belonging to the supplied group-membership snapshot.
 *
 * This prepares envelopes only. The caller still needs a durable, retryable
 * delivery outbox and must re-check the authoritative membership epoch before
 * considering distribution committed or sending Megolm group messages.
 */
internal class OlmGroupSessionKeyDistributionPreparer(
    private val directory: OlmOutboundKeyDirectory,
    private val sessions: OlmGroupKeyOutboundSessionRepository,
    private val trustVerifier: OlmDeviceTrustVerifier,
) {
    suspend fun prepare(
        distributionId: String,
        membership: GroupCryptoMembershipSnapshot,
        megolmSessionId: String,
        sessionKey: String,
        issuedAtMillis: Long,
    ): PreparedOlmGroupKeyDistribution {
        require(membership.isValid()) {
            "Authoritative group crypto membership snapshot is invalid"
        }
        val groupId = membership.groupId
        val membershipEpoch = membership.epoch
        val currentMemberUserIds = membership.memberUserIds
        val senderUserId = directory.authenticatedUserId
        val senderDeviceId = sessions.localDeviceId
        require(senderUserId in currentMemberUserIds) {
            "Authenticated sender is not a current member of this group"
        }

        val otherMemberIds = currentMemberUserIds - senderUserId
        // Require a directory result for every other member. An absent map entry
        // is different from a successfully loaded account with no published keys.
        val recipientDevices = coroutineScope {
            otherMemberIds.sorted().map { memberId ->
                async { memberId to directory.getUserDevices(memberId) }
            }.awaitAll().toMap()
        }
        val senderDevices = directory.getUserDevices(senderUserId)

        val plan = OlmGroupSessionKeyDistributionPlanner.plan(
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            currentMemberUserIds = currentMemberUserIds,
            recipientDevicesByUser = recipientDevices,
            senderDevices = senderDevices,
            trustVerifier = trustVerifier,
        )

        // Validate the shared distribution metadata and signed key before
        // consuming any one-time prekey. Every per-device payload below differs
        // only in its recipient account/device binding.
        val firstTarget = plan.targets.first()
        OlmGroupSessionKeyDistributionPayloadCodec.encode(
            OlmGroupSessionKeyDistributionPayload(
                distributionId = distributionId,
                groupId = groupId,
                membershipEpoch = membershipEpoch,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                recipientUserId = firstTarget.userId,
                recipientDeviceId = firstTarget.device.deviceId,
                megolmSessionId = megolmSessionId,
                sessionKey = sessionKey,
                issuedAtMillis = issuedAtMillis,
            )
        )

        val deliveries = plan.targets.map { target ->
            // Trust is checked again immediately before claiming a prekey and
            // creating a session, rather than relying only on the earlier plan.
            check(
                trustVerifier.isVerified(
                    peerUserId = target.userId,
                    peerDeviceId = target.device.deviceId,
                    peerEd25519IdentityKey = target.device.ed25519IdentityKey,
                )
            ) {
                "Recipient device trust changed before group-key preparation"
            }
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
            val payload = OlmGroupSessionKeyDistributionPayload(
                distributionId = distributionId,
                groupId = groupId,
                membershipEpoch = membershipEpoch,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                recipientUserId = target.userId,
                recipientDeviceId = target.device.deviceId,
                megolmSessionId = megolmSessionId,
                sessionKey = sessionKey,
                issuedAtMillis = issuedAtMillis,
            )
            OlmGroupSessionKeyDistributionPayloadCodec.requireCurrentMembers(
                payload,
                currentMemberUserIds,
            )
            val envelope = sessions.encryptGroupSessionKeyDistributionEnvelope(
                sessionId = sessionId,
                payload = payload,
            )
            PreparedOlmGroupKeyDelivery(
                recipientUserId = target.userId,
                recipientDeviceId = target.device.deviceId,
                sessionId = sessionId,
                envelope = envelope,
            )
        }

        return PreparedOlmGroupKeyDistribution(
            distributionId = distributionId,
            groupId = groupId,
            membershipEpoch = membershipEpoch,
            deliveries = deliveries,
            skippedUntrustedDeviceCount = plan.skippedUntrustedDeviceCount,
        )
    }

    private companion object {
        const val MAX_ONE_TIME_KEYS = 20
    }
}
