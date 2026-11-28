package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val existingOutboundSession: (
        GroupCryptoMembershipSnapshot,
        expectedSessionId: String,
    ) -> OutboundMegolmSessionInfo?,
    private val exportOutboundSessionKey: (
        GroupCryptoMembershipSnapshot,
        expectedSessionId: String,
    ) -> ByteArray,
    private val prepareDistribution: suspend (
        distributionId: String,
        membership: GroupCryptoMembershipSnapshot,
        megolmSessionId: String,
        sessionKey: String,
        issuedAtMillis: Long,
        selectedTargetDevices: Set<Pair<String, String>>,
        expectedTargetDevices: Set<Pair<String, String>>,
    ) -> PreparedOlmGroupKeyDistribution,
    private val resumeDistribution: (
        membership: GroupCryptoMembershipSnapshot,
        distribution: PreparedOlmGroupKeyDistribution,
    ) -> Int,
    private val validatePreparedTargets: suspend (PreparedOlmGroupKeyDistribution) -> Boolean = { true },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onDistributionFailure: (OlmGroupKeyDistributionEntity, Exception) -> Unit = { _, _ -> },
) {
    suspend fun run(): OlmGroupKeyRecoveryRunResult =
        RECOVERY_MUTEX.withLock { runLocked() }

    private suspend fun runLocked(): OlmGroupKeyRecoveryRunResult {
        val ownerUid = authenticatedUserId()
        val senderDeviceId = localDeviceId()
        val records = metadataStore.getAllForDevice(ownerUid, senderDeviceId)
        var skipped = 0
        var failed = 0
        var queued = 0

        records.forEach { metadata ->
            require(
                authenticatedUserId() == ownerUid && localDeviceId() == senderDeviceId
            ) {
                "Authenticated sender identity changed during group-key recovery"
            }
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
            // Membership lookup suspends. Fence the account/device immediately
            // before reading or exporting the native Megolm session key; otherwise
            // an auth switch during this lookup can continue under stale scope.
            require(
                authenticatedUserId() == ownerUid && localDeviceId() == senderDeviceId
            ) {
                "Authenticated sender identity changed during group membership lookup"
            }
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

            val session = existingOutboundSession(initial, metadata.megolmSessionId)
            if (session == null || session.groupId != metadata.groupId ||
                session.membershipEpoch != metadata.membershipEpoch ||
                session.sessionId != metadata.megolmSessionId
            ) {
                // The native session was rotated/lost. Do not substitute a new
                // session key into an old immutable distribution.
                skipped += 1
                return@forEach
            }

            val keyBytes = exportOutboundSessionKey(initial, metadata.megolmSessionId)
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
                metadata.targetDevicePairs().toSet(),
            )
            // Device-directory/prekey preparation can suspend. Do not continue
            // trust validation or queue recovery after an account/device switch.
            require(
                authenticatedUserId() == ownerUid && localDeviceId() == senderDeviceId
            ) {
                "Authenticated sender identity changed during group-key envelope preparation"
            }
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

            val preparedTargetsStillTrusted = validatePreparedTargets(prepared)
            // Trust validation is suspendable too. A stale account must abort
            // this recovery pass even when the callback reports an untrusted
            // target; silently counting that as a normal skip hides scope loss.
            require(
                authenticatedUserId() == ownerUid && localDeviceId() == senderDeviceId
            ) {
                "Authenticated sender identity changed during group-key trust validation"
            }
            if (!preparedTargetsStillTrusted) {
                skipped += 1
                return@forEach
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
            } catch (_: OlmGroupKeyDistributionDeferredException) {
                // A missing/revoked SAS trust prerequisite is expected to wait
                // for user action or directory change, not trigger rapid retries.
                skipped += 1
            } catch (error: Exception) {
                // Account/device changes are scope boundaries, not recoverable
                // per-record failures. Stop before reading or exporting another
                // distribution under the stale identity.
                require(
                    authenticatedUserId() == ownerUid && localDeviceId() == senderDeviceId
                ) {
                    "Authenticated sender identity changed during group-key recovery"
                }
                failed += 1
                try {
                    onDistributionFailure(metadata, error)
                } catch (_: Exception) {
                    // Diagnostic callbacks must not stop recovery of other rows.
                }
                require(
                    authenticatedUserId() == ownerUid && localDeviceId() == senderDeviceId
                ) {
                    "Authenticated sender identity changed during group-key recovery"
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
        // Immediate and periodic encrypted-delivery workers can overlap. A
        // process-wide lock prevents two recovery passes from simultaneously
        // exporting the same Megolm key, claiming Olm prekeys, and rebuilding
        // envelopes for the same immutable distribution snapshot.
        val RECOVERY_MUTEX = Mutex()
        const val MIN_KEY_BYTES = 32
        const val MAX_KEY_BYTES = 1024
    }
}
