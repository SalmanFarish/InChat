package com.example.inchat.data.worker

import kotlinx.coroutines.CancellationException

/**
 * Runs the durable local receipt handoff even when one mailbox item fails after
 * other items have already been staged. The original receive failure remains
 * available to the worker so WorkManager can retry the unfinished relay work.
 */
internal data class EncryptedInboxPassOutcome<ReceiveResult, ProcessingResult>(
    val receiveResult: ReceiveResult?,
    val receiveFailure: Exception?,
    val processingResult: ProcessingResult,
)

internal suspend fun <ReceiveResult, ProcessingResult> receiveAndProcessDurableInbox(
    receive: suspend () -> ReceiveResult,
    processDurableReceipts: suspend () -> ProcessingResult,
): EncryptedInboxPassOutcome<ReceiveResult, ProcessingResult> {
    var receiveResult: ReceiveResult? = null
    var receiveFailure: Exception? = null
    try {
        receiveResult = receive()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        receiveFailure = failure
    }

    // Deliberately outside the receive try/catch: processing failures must retain
    // their normal worker retry/error path, not be mistaken for relay failures.
    val processingResult = processDurableReceipts()
    return EncryptedInboxPassOutcome(
        receiveResult = receiveResult,
        receiveFailure = receiveFailure,
        processingResult = processingResult,
    )
}
