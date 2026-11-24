package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Expected trust/membership prerequisite that should wait for user or directory changes. */
internal class OlmGroupKeyDistributionDeferredException(message: String) :
    IllegalArgumentException(message)

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
    val megolmSessionId: String,
    val deliveries: List<PreparedOlmGroupKeyDelivery>,
    val skippedUntrustedDeviceCount: Int,
    /** Complete immutable trusted target set, even when only a subset is prepared for recovery. */
    val targetDevices: List<Pair<String, String>> = deliveries.map {
        it.recipientUserId to it.recipientDeviceId
    },
)

/**
 * Creates one separately authenticated Olm envelope for each pinned
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
        selectedTargetDevices: Set<Pair<String, String>>? = null,
        expectedTargetDevices: Set<Pair<String, String>>? = null,
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

        val allTargets = plan.targets.map { it.userId to it.device.deviceId }.toSet()
        val immutableTargets = expectedTargetDevices ?: allTargets
        require(immutableTargets.isNotEmpty()) {
            "The immutable group-key target set cannot be empty"
        }
        if (!allTargets.containsAll(immutableTargets)) {
            throw OlmGroupKeyDistributionDeferredException(
                "An original group-key target no longer has the same pinned device identity",
            )
        }
        val selectedTargets = selectedTargetDevices ?: immutableTargets
        require(selectedTargets.isNotEmpty()) {
            "At least one missing group-key target is required for envelope preparation"
        }
        require(selectedTargets.all { it in immutableTargets }) {
            "Recovery selected a device outside the immutable group-key target set"
        }
        val targetsToPrepare = plan.targets.filter {
            (it.userId to it.device.deviceId) in selectedTargets
        }

        // Validate the shared distribution metadata and signed key before
        // consuming any one-time prekey. Every per-device payload below differs
        // only in its recipient account/device binding.
        val firstTarget = targetsToPrepare.first()
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

        val deliveries = targetsToPrepare.map { target ->
            // Trust is checked again immediately before claiming a prekey and
            // creating a session, rather than relying only on the earlier plan.
            if (!trustVerifier.isTrustedForMessaging(
                peerUserId = target.userId,
                peerDeviceId = target.device.deviceId,
                peerEd25519IdentityKey = target.device.ed25519IdentityKey,
            )) {
                throw OlmGroupKeyDistributionDeferredException(
                    "Recipient device identity changed before group-key preparation",
                )
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
                        scope = OlmPreKeyClaimScope.group(
                            groupId = groupId,
                            membershipEpoch = membershipEpoch,
                        ),
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
            megolmSessionId = megolmSessionId,
            deliveries = deliveries,
            skippedUntrustedDeviceCount = plan.skippedUntrustedDeviceCount,
            targetDevices = immutableTargets.sortedWith(
                compareBy<Pair<String, String>> { it.first }.thenBy { it.second },
            ),
        )
    }

    /**
     * Re-read the live public directory and local identity pins immediately before
     * recovered envelopes are committed to the durable outbox. This closes the
     * preparation-to-enqueue window for device removal and trust revocation.
     */
    suspend fun arePreparedTargetsStillTrusted(
        distribution: PreparedOlmGroupKeyDistribution,
    ): Boolean {
        val devicesByUser = distribution.deliveries
            .map { it.recipientUserId }
            .distinct()
            .sorted()
            .associateWith { userId -> directory.getUserDevices(userId) }

        return distribution.deliveries.all { delivery ->
            val currentDevice = devicesByUser[delivery.recipientUserId]
                ?.firstOrNull { it.deviceId == delivery.recipientDeviceId }
                ?: return@all false
            trustVerifier.isTrustedForMessaging(
                peerUserId = delivery.recipientUserId,
                peerDeviceId = delivery.recipientDeviceId,
                peerEd25519IdentityKey = currentDevice.ed25519IdentityKey,
            )
        }
    }

    private companion object {
        const val MAX_ONE_TIME_KEYS = 20
    }
}
