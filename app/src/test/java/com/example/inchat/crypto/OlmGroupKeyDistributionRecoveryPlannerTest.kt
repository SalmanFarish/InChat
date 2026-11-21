package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupKeyDistributionRecoveryPlannerTest {
    @Test
    fun returnsOnlyTargetsMissingFromBothQueueAndAcknowledgementLedger() {
        val metadata = metadata()
        val plan = OlmGroupKeyDistributionRecoveryPlanner.plan(
            metadata = metadata,
            queuedTargets = setOf("bob" to "bob-phone"),
            acknowledgedTargets = setOf("alice" to "alice-tablet"),
        )

        assertEquals(listOf("carol" to "carol-phone"), plan.missingTargets)
        assertTrue(plan.failedTargets.isEmpty())
    }

    @Test
    fun returnsNoMissingTargetsWhenEveryTargetIsQueuedOrAcknowledged() {
        val plan = OlmGroupKeyDistributionRecoveryPlanner.plan(
            metadata = metadata(),
            queuedTargets = setOf("bob" to "bob-phone"),
            acknowledgedTargets = setOf("alice" to "alice-tablet", "carol" to "carol-phone"),
        )

        assertTrue(plan.missingTargets.isEmpty())
    }

    @Test
    fun failedTargetsAreReportedAndNeverSilentlyRecreated() {
        val plan = OlmGroupKeyDistributionRecoveryPlanner.plan(
            metadata = metadata(),
            queuedTargets = emptySet(),
            acknowledgedTargets = setOf("alice" to "alice-tablet"),
            failedTargets = setOf("bob" to "bob-phone"),
        )

        assertEquals(listOf("carol" to "carol-phone"), plan.missingTargets)
        assertEquals(setOf("bob" to "bob-phone"), plan.failedTargets)
    }

    @Test
    fun rejectsQueueOrAcknowledgementTargetsOutsideImmutableMetadata() {
        assertRejected {
            OlmGroupKeyDistributionRecoveryPlanner.plan(
                metadata = metadata(),
                queuedTargets = setOf("mallory" to "mallory-phone"),
                acknowledgedTargets = emptySet(),
            )
        }
        assertRejected {
            OlmGroupKeyDistributionRecoveryPlanner.plan(
                metadata = metadata(),
                queuedTargets = emptySet(),
                acknowledgedTargets = setOf("mallory" to "mallory-phone"),
            )
        }
        assertRejected {
            OlmGroupKeyDistributionRecoveryPlanner.plan(
                metadata = metadata(),
                queuedTargets = emptySet(),
                acknowledgedTargets = emptySet(),
                failedTargets = setOf("mallory" to "mallory-phone"),
            )
        }
    }

    private fun metadata() = OlmGroupKeyDistributionRecord(
        ownerUserId = "alice",
        senderDeviceId = "alice-phone",
        distributionId = "group-key-7",
        groupId = "group-1",
        membershipEpoch = 7L,
        megolmSessionId = "1234567890abcdef",
        targetDevices = listOf(
            "alice" to "alice-tablet",
            "bob" to "bob-phone",
            "carol" to "carol-phone",
        ),
    ).toEntity()

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected recovery planner to reject inconsistent state", rejected)
    }
}
