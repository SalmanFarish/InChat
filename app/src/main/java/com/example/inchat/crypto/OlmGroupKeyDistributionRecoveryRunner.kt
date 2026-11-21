package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64

internal data class OlmGroupKeyRecoveryRunResult(
    val distributionsScanned: Int,
    val distributionsSkipped: Int,
    val distributionsFailed: Int,
    val deliveriesQueued: Int,
)

/**
 * Rebuilds only missing per-device envelopes for durable, validated group-key
 * distribution records. It never creates a new distribution ID or exports a
 * key unless the native Megolm session still matches the persisted session.
 */
internal class OlmGroupKeyDistributionRecoveryRunner(
    private val metadataStore: OlmGroupKeyDistributionMetadataStore,
    private val recoveryStateReader: OlmGroupKeyDistributionRecoveryStateReader,
    private val membershipForGroup: suspend (String) -> GroupCryptoMembershipSnapshot?,
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
    private val ensureOutboundSession: (GroupCryptoMembershipSnapshot) -> OutboundMegolmSessionInfo,
    private val exportOutboundSessionKey: (GroupCryptoMembershipSnapshot) -> ByteArray,
    private val prepareDistribution: suspend (
        distributionId: String,
        membership: GroupCryptoMembershipSnapshot,
        megolmSessionId: String,
        sessionKey: String,
        issuedAtMillis: Long,
        selectedTargetDevices: Set<Pair<String, String>>,
    ) -> PreparedOlmGroupKeyDistribution,
    private val resumeDistribution: (
        membership: GroupCryptoMembershipSnapshot,
        distribution: PreparedOlmGroupKeyDistribution,
    ) -> Int,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onDistributionFailure: (OlmGroupKeyDistributionEntity, Exception) -> Unit = { _, _ -> },
) {
    suspend fun run(): OlmGroupKeyRecoveryRunResult {
        val ownerUid = authenticatedUserId()
        val senderDeviceId = localDeviceId()
        val records = metadataStore.getAllForDevice(ownerUid, senderDeviceId)
        var skipped = 0
        var failed = 0
        var queued = 0

        records.forEach { metadata ->
            try {
            metadata.requireValidForRecovery()
            require(metadata.ownerUserId == ownerUid && metadata.senderDeviceId == senderDeviceId) {
                "Recovery metadata escaped the active account/device scope"
            }

            val state = recoveryStateReader.read(metadata)
            val plan = OlmGroupKeyDistributionRecoveryPlanner.plan(
                metadata = metadata,
                queuedTargets = state.queuedTargets,
                acknowledgedTargets = state.acknowledgedTargets,
                failedTargets = state.failedTargets,
            )
            if (plan.missingTargets.isEmpty()) {
                skipped += 1
                return@forEach
            }

            val initial = membershipForGroup(metadata.groupId)
            if (initial == null || !initial.isValid() ||
                initial.groupId != metadata.groupId ||
                initial.epoch != metadata.membershipEpoch ||
                ownerUid !in initial.memberUserIds
            ) {
                // Membership changes retire old distributions. Never send an old
                // group key to a newly changed membership snapshot.
                skipped += 1
                return@forEach
            }

            val expectedDistributionId = OlmGroupSessionKeyDistributionId.create(
                membership = initial,
                senderUserId = ownerUid,
                senderDeviceId = senderDeviceId,
                megolmSessionId = metadata.megolmSessionId,
            )
            if (expectedDistributionId != metadata.distributionId) {
                skipped += 1
                return@forEach
            }

            val session = ensureOutboundSession(initial)
            if (session.groupId != metadata.groupId ||
                session.membershipEpoch != metadata.membershipEpoch ||
                session.sessionId != metadata.megolmSessionId
            ) {
                // The native session was rotated/lost. Do not substitute a new
                // session key into an old immutable distribution.
                skipped += 1
                return@forEach
            }

            val keyBytes = exportOutboundSessionKey(initial)
            val encodedKey = try {
                require(keyBytes.size in MIN_KEY_BYTES..MAX_KEY_BYTES) {
                    "Native Megolm session key has an invalid size"
                }
                Base64.getUrlEncoder().withoutPadding().encodeToString(keyBytes)
            } finally {
                keyBytes.fill(0)
            }

            val prepared = prepareDistribution(
                metadata.distributionId,
                initial,
                metadata.megolmSessionId,
                encodedKey,
                nowMillis().coerceAtLeast(0L),
                plan.missingTargets.toSet(),
            )
            require(
                prepared.distributionId == metadata.distributionId &&
                    prepared.groupId == metadata.groupId &&
                    prepared.membershipEpoch == metadata.membershipEpoch &&
                    prepared.megolmSessionId == metadata.megolmSessionId &&
                    prepared.targetDevices == metadata.targetDevicePairs() &&
                    prepared.deliveries.isNotEmpty()
            ) {
                "Recovered envelopes do not match the immutable distribution metadata"
            }
            val preparedTargets = prepared.deliveries.map {
                it.recipientUserId to it.recipientDeviceId
            }.toSet()
            require(preparedTargets.size == prepared.deliveries.size &&
                preparedTargets.all { it in plan.missingTargets }
            ) {
                "Recovery preparer returned a target that is not currently missing"
            }

            val latest = membershipForGroup(metadata.groupId)
            require(
                latest != null &&
                    latest.isValid() &&
                    latest.groupId == initial.groupId &&
                    latest.epoch == initial.epoch &&
                    latest.members == initial.members &&
                    authenticatedUserId() == ownerUid &&
                    localDeviceId() == senderDeviceId
            ) {
                "Membership or local identity changed during group-key recovery"
            }

            // Coordinator re-reads durable queue/ack state before accepting a
            // partial resume, closing the race with another worker pass.
            queued += resumeDistribution(latest!!, prepared)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failed += 1
                try {
                    onDistributionFailure(metadata, error)
                } catch (_: Exception) {
                    // Diagnostic callbacks must not stop recovery of other rows.
                }
            }
        }

        return OlmGroupKeyRecoveryRunResult(
            distributionsScanned = records.size,
            distributionsSkipped = skipped,
            distributionsFailed = failed,
            deliveriesQueued = queued,
        )
    }

    private companion object {
        const val MIN_KEY_BYTES = 32
        const val MAX_KEY_BYTES = 1024
    }
}
