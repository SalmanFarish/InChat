package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupKeyDistributionRecoveryStateReducerTest {
    @Test
    fun reconcilesQueuedFailedAndAcknowledgedTargets() {
        val state = OlmGroupKeyDistributionRecoveryStateReducer.reduce(
            queueRows = listOf(
                row("bob", "bob-phone", "QUEUED"),
                row("carol", "carol-phone", "FAILED"),
                row("dave", "dave-phone", "ACKNOWLEDGED"),
            ),
            acknowledgedTargets = setOf("dave" to "dave-phone", "erin" to "erin-phone"),
        )
        assertEquals(setOf("bob" to "bob-phone"), state.queuedTargets)
        assertEquals(setOf("dave" to "dave-phone", "erin" to "erin-phone"), state.acknowledgedTargets)
        assertEquals(setOf("carol" to "carol-phone"), state.failedTargets)
    }

    @Test
    fun rejectsAcknowledgedQueueRowWithoutDurableReceipt() {
        assertRejected {
            OlmGroupKeyDistributionRecoveryStateReducer.reduce(
                listOf(row("bob", "bob-phone", "ACKNOWLEDGED")), emptySet(),
            )
        }
    }

    @Test
    fun rejectsQueueOrFailedRowThatAlsoHasAcknowledgementReceipt() {
        listOf("QUEUED", "IN_FLIGHT", "UPLOADED", "FAILED").forEach { status ->
            assertRejected {
                OlmGroupKeyDistributionRecoveryStateReducer.reduce(
                    listOf(row("bob", "bob-phone", status)),
                    setOf("bob" to "bob-phone"),
                )
            }
        }
    }

    @Test
    fun rejectsDuplicateTargetsAndUnknownStatuses() {
        assertRejected {
            OlmGroupKeyDistributionRecoveryStateReducer.reduce(
                listOf(row("bob", "bob-phone", "QUEUED"), row("bob", "bob-phone", "UPLOADED")),
                emptySet(),
            )
        }
        assertRejected {
            OlmGroupKeyDistributionRecoveryStateReducer.reduce(
                listOf(row("bob", "bob-phone", "MYSTERY")), emptySet(),
            )
        }
    }

    private fun row(user: String, device: String, status: String) =
        OlmDeliveryQueueTargetState(user, device, status)

    private fun assertRejected(block: () -> Unit) {
        assertTrue("Expected inconsistent recovery state to be rejected", runCatching(block).isFailure)
    }
}
