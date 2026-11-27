package com.example.inchat.data.worker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EncryptedInboxReceiveHandoffTest {
    @Test
    fun receiveFailureDoesNotSkipProcessingOfDurablyStagedReceipts() = runBlocking {
        val relayFailure = IllegalStateException("one inbox item failed during remote removal")
        var processedReceiptCount = 0

        val outcome = receiveAndProcessDurableInbox(
            receive = {
                throw relayFailure
            },
            processDurableReceipts = {
                // Represents the Room-backed pending receipt processor. These
                // receipts were safely staged before the failed relay operation.
                processedReceiptCount += 2
                "committed"
            },
        )

        assertNull(outcome.receiveResult)
        assertSame(relayFailure, outcome.receiveFailure)
        assertEquals("committed", outcome.processingResult)
        assertEquals(2, processedReceiptCount)
    }

    @Test
    fun successfulReceiveAndProcessingPreserveBothResults() = runBlocking {
        val outcome = receiveAndProcessDurableInbox(
            receive = { "received" },
            processDurableReceipts = { "processed" },
        )

        assertEquals("received", outcome.receiveResult)
        assertNull(outcome.receiveFailure)
        assertEquals("processed", outcome.processingResult)
    }

    @Test
    fun receiveCancellationPropagatesWithoutStartingReceiptProcessing() = runBlocking {
        var processingStarted = false
        val cancellation = CancellationException("worker stopped")

        try {
            receiveAndProcessDurableInbox(
                receive = { throw cancellation },
                processDurableReceipts = {
                    processingStarted = true
                    Unit
                },
            )
            throw AssertionError("Expected cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }

        assertEquals(false, processingStarted)
    }

    @Test
    fun receiptProcessingFailureIsNotHiddenByTheReceiveRecoveryBoundary() = runBlocking {
        val processingFailure = IllegalArgumentException("receipt processing failed")

        try {
            receiveAndProcessDurableInbox(
                receive = { throw IllegalStateException("relay acknowledgement failed") },
                processDurableReceipts = { throw processingFailure },
            )
            throw AssertionError("Expected receipt processing failure to propagate")
        } catch (actual: IllegalArgumentException) {
            assertSame(processingFailure, actual)
        }
    }
}
