package com.example.inchat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseSasVerificationTransportTest {

    @Test
    fun readCleansExpiredRowsKeepsMalformedRowsAndSortsValidMessages() = runBlocking {
        val store = InMemoryRelayStore()
        val expired = message("expired-transaction", createdAt = 100L, expiresAt = 900L)
        val later = message("later-transaction", createdAt = 800L, expiresAt = 5_000L)
        val earlier = message("earlier-transaction", createdAt = 600L, expiresAt = 5_000L)
        val malformed = relayRecord(message("malformed-transaction", createdAt = 700L, expiresAt = 5_000L))
            .copy(fields = relayRecord(message("malformed-transaction", createdAt = 700L, expiresAt = 5_000L))
                .fields + ("unexpected" to true))

        store.enqueue("bob", "device-bob", relayRecord(expired))
        store.enqueue("bob", "device-bob", relayRecord(later))
        store.enqueue("bob", "device-bob", relayRecord(earlier))
        store.enqueue("bob", "device-bob", malformed)

        val received = transport(store).readInbox("device-bob")

        assertEquals(listOf(earlier.deliveryId, later.deliveryId), received.map { it.deliveryId })
        assertEquals(listOf(expired.deliveryId), store.removedDeliveryIds)
        assertTrue(store.contains("bob", "device-bob", malformed.deliveryId))
    }

    @Test
    fun accountSwitchDuringInboxReadFailsClosedBeforeReturningOrDeletingRows() = runBlocking {
        val store = InMemoryRelayStore()
        val expired = message("expired-during-read", createdAt = 100L, expiresAt = 900L)
        store.enqueue("bob", "device-bob", relayRecord(expired))
        var signedInUser = "bob"
        store.afterRead = { signedInUser = "mallory" }

        val result = runCatching {
            transport(store, currentUser = { signedInUser }).readInbox("device-bob")
        }

        assertTrue(result.isFailure)
        assertEquals(emptyList<String>(), store.removedDeliveryIds)
        assertTrue(store.contains("bob", "device-bob", expired.deliveryId))
    }

    @Test
    fun accountSwitchImmediatelyBeforeExpiredRowDeletionPreventsTheDelete() = runBlocking {
        val store = InMemoryRelayStore()
        val expired = message("expired-before-delete", createdAt = 100L, expiresAt = 900L)
        store.enqueue("bob", "device-bob", relayRecord(expired))
        var authCheck = 0
        val currentUser = {
            authCheck++
            if (authCheck >= 3) "mallory" else "bob"
        }

        val result = runCatching {
            transport(store, currentUser = currentUser).readInbox("device-bob")
        }

        assertTrue(result.isFailure)
        assertEquals(3, authCheck)
        assertTrue(store.removedDeliveryIds.isEmpty())
        assertTrue(store.contains("bob", "device-bob", expired.deliveryId))
    }

    @Test
    fun transientReadFailureLeavesRelayAvailableForTheNextPoll() = runBlocking {
        val store = InMemoryRelayStore()
        val pending = message("retry-read-transaction", createdAt = 800L, expiresAt = 5_000L)
        store.enqueue("bob", "device-bob", relayRecord(pending))
        store.failNextRead = true
        val transport = transport(store)

        assertTrue(runCatching { transport.readInbox("device-bob") }.isFailure)
        val retried = transport.readInbox("device-bob")

        assertEquals(listOf(pending.deliveryId), retried.map { it.deliveryId })
        assertTrue(store.contains("bob", "device-bob", pending.deliveryId))
        assertEquals(2, store.readCount)
    }

    @Test
    fun transientAcknowledgementFailureDoesNotLoseRelayAndCanBeRetried() = runBlocking {
        val store = InMemoryRelayStore()
        val pending = message("retry-ack-transaction", createdAt = 800L, expiresAt = 5_000L)
        store.enqueue("bob", "device-bob", relayRecord(pending))
        store.failNextRemove = true
        val transport = transport(store)

        assertTrue(runCatching {
            transport.acknowledge("device-bob", pending.deliveryId)
        }.isFailure)
        assertTrue(store.contains("bob", "device-bob", pending.deliveryId))

        transport.acknowledge("device-bob", pending.deliveryId)
        assertTrue(!store.contains("bob", "device-bob", pending.deliveryId))
        assertEquals(listOf(pending.deliveryId), store.removedDeliveryIds)
    }

    @Test
    fun accountSwitchImmediatelyBeforeUploadPreventsRemoteWrite() = runBlocking {
        val store = InMemoryRelayStore()
        var authCheck = 0
        val currentUser = {
            authCheck++
            if (authCheck >= 2) "mallory" else "alice"
        }
        val outbound = message(
            transactionId = "account-switch-upload",
            senderUserId = "alice",
            senderDeviceId = "device-alice",
            recipientUserId = "bob",
            recipientDeviceId = "device-bob",
            createdAt = 800L,
            expiresAt = 5_000L,
        )

        val result = runCatching {
            transport(store, localUser = "alice", localDevice = "device-alice", currentUser = currentUser)
                .upload(outbound)
        }

        assertTrue(result.isFailure)
        assertTrue(store.rows.isEmpty())
        assertEquals(2, authCheck)
    }

    @Test
    fun accountSwitchImmediatelyBeforeAcknowledgementPreventsRemoteDelete() = runBlocking {
        val store = InMemoryRelayStore()
        val pending = message("account-switch-ack", createdAt = 800L, expiresAt = 5_000L)
        store.enqueue("bob", "device-bob", relayRecord(pending))
        var authCheck = 0
        val currentUser = {
            authCheck++
            if (authCheck >= 2) "mallory" else "bob"
        }

        val result = runCatching {
            transport(store, currentUser = currentUser).acknowledge("device-bob", pending.deliveryId)
        }

        assertTrue(result.isFailure)
        assertTrue(store.contains("bob", "device-bob", pending.deliveryId))
        assertTrue(store.removedDeliveryIds.isEmpty())
    }

    private fun transport(
        store: InMemoryRelayStore,
        localUser: String = "bob",
        localDevice: String = "device-bob",
        currentUser: () -> String? = { localUser },
    ) = FirebaseSasVerificationTransport(
        localUserId = localUser,
        localDeviceId = localDevice,
        relayStore = store,
        currentAuthenticatedUserId = currentUser,
        clock = { NOW },
    )

    private fun relayRecord(message: SasVerificationMessage): SasVerificationRelayRecord =
        SasVerificationRelayRecord(
            deliveryId = message.deliveryId,
            fields = mapOf(
                "senderUid" to message.senderUserId,
                "senderDeviceId" to message.senderDeviceId,
                "transactionId" to message.transactionId,
                "type" to message.type.wireValue,
                "payload" to message.payload,
                "createdAtMillis" to message.createdAtMillis,
                "expiresAtMillis" to message.expiresAtMillis,
                "canonicalMessage" to SasVerificationMessageCodec.encode(message),
            ),
        )

    private fun message(
        transactionId: String,
        senderUserId: String = "alice",
        senderDeviceId: String = "device-alice",
        recipientUserId: String = "bob",
        recipientDeviceId: String = "device-bob",
        createdAt: Long,
        expiresAt: Long,
    ) = SasVerificationMessage(
        transactionId = transactionId,
        senderUserId = senderUserId,
        senderDeviceId = senderDeviceId,
        recipientUserId = recipientUserId,
        recipientDeviceId = recipientDeviceId,
        type = SasVerificationMessageType.COMMITMENT,
        payload = "a".repeat(64),
        createdAtMillis = createdAt,
        expiresAtMillis = expiresAt,
    )

    private class InMemoryRelayStore : SasVerificationRelayStore {
        data class Row(
            val recipientUserId: String,
            val recipientDeviceId: String,
            val record: SasVerificationRelayRecord,
        )

        val rows = mutableListOf<Row>()
        val removedDeliveryIds = mutableListOf<String>()
        var failNextRead = false
        var failNextRemove = false
        var readCount = 0
        var afterRead: (() -> Unit)? = null

        override suspend fun put(
            recipientUserId: String,
            recipientDeviceId: String,
            deliveryId: String,
            fields: Map<String, Any?>,
        ) {
            rows.removeAll {
                it.recipientUserId == recipientUserId &&
                    it.recipientDeviceId == recipientDeviceId &&
                    it.record.deliveryId == deliveryId
            }
            rows += Row(
                recipientUserId,
                recipientDeviceId,
                SasVerificationRelayRecord(deliveryId, fields.toMap()),
            )
        }

        override suspend fun readInbox(
            recipientUserId: String,
            recipientDeviceId: String,
        ): List<SasVerificationRelayRecord> {
            readCount++
            if (failNextRead) {
                failNextRead = false
                error("Simulated temporary SAS relay read failure")
            }
            val result = rows.filter {
                it.recipientUserId == recipientUserId &&
                    it.recipientDeviceId == recipientDeviceId
            }.map { it.record }
            afterRead?.invoke()
            return result
        }

        override suspend fun remove(
            recipientUserId: String,
            recipientDeviceId: String,
            deliveryId: String,
        ) {
            if (failNextRemove) {
                failNextRemove = false
                error("Simulated temporary SAS relay delete failure")
            }
            rows.removeAll {
                it.recipientUserId == recipientUserId &&
                    it.recipientDeviceId == recipientDeviceId &&
                    it.record.deliveryId == deliveryId
            }
            removedDeliveryIds += deliveryId
        }

        fun enqueue(
            recipientUserId: String,
            recipientDeviceId: String,
            record: SasVerificationRelayRecord,
        ) {
            rows += Row(recipientUserId, recipientDeviceId, record)
        }

        fun contains(recipientUserId: String, recipientDeviceId: String, deliveryId: String) =
            rows.any {
                it.recipientUserId == recipientUserId &&
                    it.recipientDeviceId == recipientDeviceId &&
                    it.record.deliveryId == deliveryId
            }
    }

    private companion object {
        const val NOW = 1_000L
    }
}
