package com.example.inchat.crypto

/**
 * Computes only targets that have neither a durable queue row nor a durable
 * acknowledgement. Failed rows are reported separately and deliberately
 * block automatic recreation until an explicit retry policy exists.
 */
internal data class OlmGroupKeyDistributionRecoveryPlan(
    val missingTargets: List<Pair<String, String>>,
    val failedTargets: Set<Pair<String, String>>,
)

/** Pure fail-closed planner for recovering an immutable group-key target set. */
internal object OlmGroupKeyDistributionRecoveryPlanner {
    fun plan(
        metadata: OlmGroupKeyDistributionEntity,
        queuedTargets: Set<Pair<String, String>>,
        acknowledgedTargets: Set<Pair<String, String>>,
        failedTargets: Set<Pair<String, String>> = emptySet(),
    ): OlmGroupKeyDistributionRecoveryPlan {
        metadata.requireValidForRecovery()
        val immutableTargets = metadata.targetDevicePairs().toSet()
        require(queuedTargets.all { it in immutableTargets }) {
            "Outbox contains a target outside the persisted group-key distribution"
        }
        require(acknowledgedTargets.all { it in immutableTargets }) {
            "Acknowledgement ledger contains a target outside the persisted group-key distribution"
        }
        require(failedTargets.all { it in immutableTargets }) {
            "Failed outbox state contains a target outside the persisted group-key distribution"
        }

        val accountedFor = queuedTargets + acknowledgedTargets + failedTargets
        val missing = immutableTargets - accountedFor
        return OlmGroupKeyDistributionRecoveryPlan(
            missingTargets = missing.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }),
            failedTargets = failedTargets,
        )
    }
}
