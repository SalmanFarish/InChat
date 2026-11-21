package com.example.inchat.crypto

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

class OlmPendingReceiptProcessorConcurrencyTest {
    @Test
    fun separateProcessorInstancesDoNotAdvanceOnePendingReceiptConcurrently() = runBlocking {
        val dao = InMemoryReceiptDao()
        val encodedEnvelope = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = "sender-device",
                recipientDeviceId = "recipient-device",
                sessionId = "session-sender-device",
                ciphertext = byteArrayOf(1, 7),
            ),
        )
        val receiptId = olmInboundReceiptId("alice", "sender-device", "message-1", "recipient-device")
        dao.insertIfAbsent(
            OlmInboundReceiptEntity(
                receiptId = receiptId,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
                messageId = "message-1",
                recipientDeviceId = "recipient-device",
                envelope = encodedEnvelope,
                envelopeSha256 = sha256(encodedEnvelope),
                status = OlmInboundReceiptStatus.PENDING.name,
                receivedAtMillis = 1L,
                processedAtMillis = null,
            ),
        )

        val handoffEntered = CompletableDeferred<Unit>()
        val releaseHandoff = CompletableDeferred<Unit>()
        val activeHandoffs = AtomicInteger()
        val maximumConcurrentHandoffs = AtomicInteger()
        val handoffAttempts = AtomicInteger()
        val remoteDevice = PublicDeviceKeyBundle(
            deviceId = "sender-device",
            ed25519IdentityKey = "sender-identity",
            curve25519IdentityKey = "sender-curve",
            curve25519IdentityKeySignature = "sender-signature",
            fallbackKey = SignedOlmPreKey("fallback", "fallback-id", "fallback-key", "fallback-signature"),
            oneTimeKeys = emptyList(),
        )

        fun processor() = OlmPendingReceiptProcessor(
            receiptDao = dao,
            remoteDevicesForUser = { listOf(remoteDevice) },
            trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
            handoff = OlmInboundReceiptHandoff { currentReceiptId, _, _, _, _ ->
                val active = activeHandoffs.incrementAndGet()
                maximumConcurrentHandoffs.updateAndGet { previous -> maxOf(previous, active) }
                handoffAttempts.incrementAndGet()
                handoffEntered.complete(Unit)
                try {
                    releaseHandoff.await()
                    check(dao.markProcessed(currentReceiptId, 10L) == 1)
                    OlmInboundHandoffResult.COMMITTED
                } finally {
                    activeHandoffs.decrementAndGet()
                }
            },
            finalizer = OlmCommittedReceiptFinalizer { currentReceiptId, _ ->
                dao.markFinalized(currentReceiptId) == 1
            },
            authenticatedLocalUserId = { "recipient" },
        )

        val first = async { processor().processPending() }
        handoffEntered.await()
        val secondStarted = CompletableDeferred<Unit>()
        val second = async {
            secondStarted.complete(Unit)
            processor().processPending()
        }
        secondStarted.await()
        yield()
        assertEquals(1, activeHandoffs.get())

        releaseHandoff.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, handoffAttempts.get())
        assertEquals(1, maximumConcurrentHandoffs.get())
        assertEquals(OlmInboundReceiptStatus.FINALIZED.name, dao.getByReceiptId(receiptId)?.status)
    }

    private class InMemoryReceiptDao : OlmInboundReceiptDao {
        private val rows = linkedMapOf<String, OlmInboundReceiptEntity>()

        @Synchronized
        override fun insertIfAbsent(receipt: OlmInboundReceiptEntity): Long {
            if (rows.containsKey(receipt.receiptId)) return -1L
            rows[receipt.receiptId] = receipt
            return 1L
        }

        @Synchronized
        override fun getByReceiptId(receiptId: String): OlmInboundReceiptEntity? = rows[receiptId]

        @Synchronized
        override fun getPending(limit: Int): List<OlmInboundReceiptEntity> =
            rows.values.filter { it.status == OlmInboundReceiptStatus.PENDING.name }
                .sortedWith(compareBy<OlmInboundReceiptEntity> { it.receivedAtMillis }.thenBy { it.receiptId })
                .take(limit)

        @Synchronized
        override fun getWaitingForDevice(limit: Int): List<OlmInboundReceiptEntity> =
            rows.values.filter { it.status == OlmInboundReceiptStatus.WAITING_FOR_DEVICE.name }
                .sortedWith(compareBy<OlmInboundReceiptEntity> { it.receivedAtMillis }.thenBy { it.receiptId })
                .take(limit)

        @Synchronized
        override fun markWaitingForDevice(receiptId: String): Int =
            update(receiptId, OlmInboundReceiptStatus.PENDING) {
                it.copy(status = OlmInboundReceiptStatus.WAITING_FOR_DEVICE.name)
            }

        @Synchronized
        override fun restorePending(receiptId: String): Int =
            update(receiptId, OlmInboundReceiptStatus.WAITING_FOR_DEVICE) {
                it.copy(status = OlmInboundReceiptStatus.PENDING.name)
            }

        @Synchronized
        override fun markRejected(receiptId: String): Int {
            val row = rows[receiptId] ?: return 0
            if (row.status != OlmInboundReceiptStatus.PENDING.name &&
                row.status != OlmInboundReceiptStatus.WAITING_FOR_DEVICE.name
            ) return 0
            rows[receiptId] = row.copy(status = OlmInboundReceiptStatus.REJECTED.name, processedAtMillis = null)
            return 1
        }

        @Synchronized
        override fun getAwaitingFinalization(limit: Int): List<OlmInboundReceiptEntity> =
            rows.values.filter { it.status == OlmInboundReceiptStatus.PROCESSED.name }
                .sortedWith(compareBy<OlmInboundReceiptEntity> { it.processedAtMillis }.thenBy { it.receiptId })
                .take(limit)

        @Synchronized
        override fun markFinalized(receiptId: String): Int =
            update(receiptId, OlmInboundReceiptStatus.PROCESSED) {
                it.copy(status = OlmInboundReceiptStatus.FINALIZED.name)
            }

        @Synchronized
        override fun markProcessed(receiptId: String, processedAtMillis: Long): Int =
            update(receiptId, OlmInboundReceiptStatus.PENDING) {
                it.copy(status = OlmInboundReceiptStatus.PROCESSED.name, processedAtMillis = processedAtMillis)
            }

        private fun update(
            receiptId: String,
            expectedStatus: OlmInboundReceiptStatus,
            transform: (OlmInboundReceiptEntity) -> OlmInboundReceiptEntity,
        ): Int {
            val row = rows[receiptId] ?: return 0
            if (row.status != expectedStatus.name) return 0
            rows[receiptId] = transform(row)
            return 1
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
}
