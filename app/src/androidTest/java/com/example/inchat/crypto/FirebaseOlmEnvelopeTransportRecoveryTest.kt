package com.example.inchat.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirebaseOlmEnvelopeTransportRecoveryTest {

    @Test
    fun uploadRetriesSameImmutableDeliveryAfterRelayAcceptedButCompletionWasLost() = runBlocking {
        val store = FakeRelayStore()
        val delivery = queuedDelivery()
        val transport = transport(store, user = { "alice" })
        store.failNextInboxPutAfterCommit = true

        val firstAttempt = runCatching {
            transport.upload(delivery, "sender-device")
        }
        assertTrue(firstAttempt.isFailure)
        assertEquals(1, store.inboxPutAttempts)
        assertEquals(1, store.inbox.size)
        assertEquals(delivery.envelope, store.inbox.values.single().fields["envelope"])

        val recovered = transport.upload(delivery, "sender-device")

        assertEquals(FirebaseOlmUploadResult.STORED, recovered)
        assertEquals(2, store.inboxPutAttempts)
        assertEquals(1, store.inbox.size)
        assertEquals(delivery.deliveryId, store.inbox.values.single().deliveryId)
        assertEquals(delivery.envelope, store.inbox.values.single().fields["envelope"])

        val changedCiphertext = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = "sender-device",
                recipientDeviceId = "recipient-device",
                sessionId = "session-1",
                ciphertext = byteArrayOf(9, 8, 7),
            ),
        )
        val changedRetry = runCatching {
            transport.upload(delivery.copy(envelope = changedCiphertext), "sender-device")
        }
        assertTrue("A delivery ID cannot be reused for changed ciphertext", changedRetry.isFailure)
        assertEquals(delivery.envelope, store.inbox.values.single().fields["envelope"])
    }

    @Test
    fun directUploadCarriesTheAuthenticatedPeerAndConversationScope() = runBlocking {
        val store = FakeRelayStore()
        val delivery = queuedDelivery()
        val transport = transport(store, user = { "alice" })

        assertEquals(FirebaseOlmUploadResult.STORED, transport.upload(delivery, "sender-device"))

        val fields = store.inbox.values.single().fields
        assertEquals("direct", fields["conversationType"])
        assertEquals(OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "bob"), fields["conversationId"])
        assertEquals("bob", fields["conversationPeerUserId"])
        assertTrue(!fields.containsKey("groupId"))
        assertTrue(!fields.containsKey("membershipEpoch"))

        val received = transport(store, user = { "bob" }).readMyDeviceInbox("recipient-device").single()
        assertEquals("direct", received.conversationType)
        assertEquals(fields["conversationId"], received.conversationId)
        assertEquals("bob", received.conversationPeerUserId)
    }

    @Test
    fun senderOwnedDeviceCopyRetainsTheOtherDirectParticipant() = runBlocking {
        val store = FakeRelayStore()
        val senderDeviceCopy = queuedDelivery().copy(recipientUserId = "alice")
        val transport = transport(store, user = { "alice" })

        assertEquals(FirebaseOlmUploadResult.STORED, transport.upload(senderDeviceCopy, "sender-device"))

        val fields = store.inbox.values.single().fields
        assertEquals("direct", fields["conversationType"])
        assertEquals("alice_bob", fields["conversationId"])
        assertEquals("bob", fields["conversationPeerUserId"])
    }

    @Test
    fun directUploadFailsClosedWhenConversationPeerIsMissing() = runBlocking {
        val store = FakeRelayStore()
        val transport = transport(store, user = { "alice" })
        val unscoped = queuedDelivery().copy(conversationId = null, conversationPeerUserId = null)

        assertTrue(runCatching { transport.upload(unscoped, "sender-device") }.isFailure)
        assertEquals(0, store.inboxPutAttempts)
    }

    @Test
    fun groupUploadCarriesTheExactCommittedMembershipEpoch() = runBlocking {
        val store = FakeRelayStore()
        val delivery = queuedDelivery().copy(groupId = "group-1", membershipEpoch = 7L, conversationId = "group-1", conversationPeerUserId = null)
        val transport = transport(store, user = { "alice" })
        transport.upload(delivery, "sender-device")
        assertEquals("group", store.inbox.values.single().fields["conversationType"])
        assertEquals("group-1", store.inbox.values.single().fields["conversationId"])
        assertEquals("group-1", store.inbox.values.single().fields["groupId"])
        assertEquals(7L, store.inbox.values.single().fields["membershipEpoch"])
        val received = transport(store, user = { "bob" }).readMyDeviceInbox("recipient-device").single()
        assertEquals("group-1", received.groupId)
        assertEquals(7L, received.membershipEpoch)
    }

    @Test
    fun inboxReadFailureLeavesRelayItemAvailableForTheNextPoll() = runBlocking {
        val store = FakeRelayStore()
        val item = inboxItem()
        store.seedInbox(item)
        store.failNextInboxRead = true
        val transport = transport(store, user = { "bob" })

        assertTrue(runCatching { transport.readMyDeviceInbox("recipient-device") }.isFailure)
        assertEquals(1, store.inbox.size)

        val recovered = transport.readMyDeviceInbox("recipient-device")

        assertEquals(listOf(item), recovered)
        assertEquals(1, store.inbox.size)
        assertEquals(2, store.inboxReadAttempts)
    }

    @Test
    fun failedAcknowledgementWriteAndInboxDeleteCanBothRecover() = runBlocking {
        val store = FakeRelayStore()
        val item = inboxItem()
        store.seedInbox(item)
        val recipientTransport = transport(store, user = { "bob" })

        store.failNextAcknowledgementPutAfterCommit = true
        assertTrue(
            runCatching {
                recipientTransport.acknowledgeStoredDelivery("recipient-device", item)
            }.isFailure,
        )
        assertEquals(1, store.acknowledgements.size)
        assertEquals(1, store.inbox.size)

        recipientTransport.acknowledgeStoredDelivery("recipient-device", item)
        assertEquals(1, store.acknowledgements.size)
        assertEquals("bob", store.acknowledgements.values.single().fields["recipientUid"])
        assertEquals("recipient-device", store.acknowledgements.values.single().fields["recipientDeviceId"])

        store.failNextInboxRemoveAfterCommit = true
        assertTrue(
            runCatching {
                recipientTransport.removeFromMyDeviceInbox("recipient-device", item.deliveryId)
            }.isFailure,
        )
        assertTrue(store.inbox.isEmpty())
        assertEquals(1, store.acknowledgements.size)

        recipientTransport.removeFromMyDeviceInbox("recipient-device", item.deliveryId)
        assertTrue(store.inbox.isEmpty())
        assertEquals(1, store.acknowledgements.size)

        val senderTransport = transport(store, user = { "alice" })
        val senderAcknowledgements = senderTransport.readMyDeliveryAcknowledgements("sender-device")
        assertEquals(1, senderAcknowledgements.size)
        assertEquals(
            OlmDeliveryAcknowledgement(
                deliveryId = item.deliveryId,
                recipientUserId = "bob",
                recipientDeviceId = "recipient-device",
                messageId = item.messageId,
                senderUserId = "alice",
                senderDeviceId = "sender-device",
            ),
            senderAcknowledgements.single(),
        )
        senderTransport.removeMyDeliveryAcknowledgement("sender-device", item.deliveryId)
        assertTrue(store.acknowledgements.isEmpty())
    }

    @Test
    fun relayRecordsWithUnexpectedFieldsAreIgnoredAndRetained() = runBlocking {
        val store = FakeRelayStore()
        val item = inboxItem()
        store.seedInbox(item)
        val existing = store.inbox.getValue(item.deliveryId)
        store.inbox[item.deliveryId] = existing.copy(
            fields = existing.fields + ("unexpectedPlaintext" to "must-not-be-consumed"),
        )
        val transport = transport(store, user = { "bob" })

        val received = transport.readMyDeviceInbox("recipient-device")

        assertTrue(received.isEmpty())
        assertEquals(1, store.inbox.size)

        store.acknowledgements[item.deliveryId] = FirebaseOlmRelayRecord(
            item.deliveryId,
            mapOf(
                "recipientUid" to "bob",
                "recipientDeviceId" to "recipient-device",
                "messageId" to item.messageId,
                "acknowledgedAt" to 1234L,
                "unexpectedPlaintext" to "must-not-be-consumed",
            ),
        )
        val senderTransport = transport(store, user = { "alice" })

        assertTrue(senderTransport.readMyDeliveryAcknowledgements("sender-device").isEmpty())
        assertEquals(1, store.acknowledgements.size)
    }

    @Test
    fun accountSwitchDuringInboxReadFailsClosedWithoutDeletingRelayItem() = runBlocking {
        val store = FakeRelayStore()
        val item = inboxItem()
        store.seedInbox(item)
        var signedInUser = "bob"
        store.afterInboxRead = { signedInUser = "mallory" }
        val transport = transport(store, user = { signedInUser })

        val result = runCatching {
            transport.readMyDeviceInbox("recipient-device")
        }

        assertTrue(result.isFailure)
        assertEquals("mallory", signedInUser)
        assertEquals(1, store.inbox.size)
        assertEquals(0, store.inboxRemoveAttempts)
    }

    @Test
    fun accountSwitchDuringAckRemovalFailsClosedAfterDeletingOnlyCapturedAccountPath() = runBlocking {
        val store = FakeRelayStore()
        val item = inboxItem()
        store.seedAcknowledgement(item)
        var signedInUser = "alice"
        store.afterAcknowledgementRemove = { signedInUser = "mallory" }
        val transport = transport(store, user = { signedInUser })

        val result = runCatching {
            transport.removeMyDeliveryAcknowledgement("sender-device", item.deliveryId)
        }

        assertTrue("The transport must notice the account switch after the awaited removal", result.isFailure)
        assertEquals("mallory", signedInUser)
        assertEquals(listOf("alice/sender-device/" + item.deliveryId), store.removedAcknowledgementPaths)
        assertTrue(store.acknowledgements.isEmpty())
    }

    @Test
    fun accountSwitchDuringDirectoryReadPreventsAnEncryptedUpload() = runBlocking {
        val store = FakeRelayStore()
        var signedInUser = "alice"
        val transport = FirebaseOlmEnvelopeTransport(
            trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
            relayStore = store,
            currentAuthenticatedUserId = { signedInUser },
            readPublishedDevices = {
                signedInUser = "mallory"
                listOf(recipientBundle())
            },
            expectedAuthenticatedUserId = "alice",
        )

        val result = runCatching {
            transport.upload(queuedDelivery(), "sender-device")
        }

        assertTrue(result.isFailure)
        assertEquals("mallory", signedInUser)
        assertTrue("The stale account must not publish ciphertext", store.inbox.isEmpty())
        assertEquals(0, store.inboxPutAttempts)
    }

    @Test
    fun revokedRecipientTrustPreventsQueuedCiphertextFromBeingRelayed() = runBlocking {
        val store = FakeRelayStore()
        var trustChecks = 0
        val transport = FirebaseOlmEnvelopeTransport(
            trustVerifier = OlmDeviceTrustVerifier { userId, deviceId, identityKey ->
                trustChecks++
                assertEquals("bob", userId)
                assertEquals("recipient-device", deviceId)
                assertEquals("recipient-ed25519", identityKey)
                false
            },
            relayStore = store,
            currentAuthenticatedUserId = { "alice" },
            readPublishedDevices = { listOf(recipientBundle()) },
            expectedAuthenticatedUserId = "alice",
        )

        val result = runCatching {
            transport.upload(queuedDelivery(), "sender-device")
        }

        assertTrue("A locally revoked device must not receive a queued group/direct envelope", result.isFailure)
        assertEquals(1, trustChecks)
        assertEquals(0, store.inboxPutAttempts)
        assertTrue(store.inbox.isEmpty())
    }

    @Test
    fun replacedRecipientIdentityKeyCannotReuseTrustForOldDeviceIdentity() = runBlocking {
        val store = FakeRelayStore()
        val replacement = recipientBundle().copy(ed25519IdentityKey = "replacement-ed25519")
        var checkedIdentityKey: String? = null
        val transport = FirebaseOlmEnvelopeTransport(
            trustVerifier = OlmDeviceTrustVerifier { _, _, identityKey ->
                checkedIdentityKey = identityKey
                identityKey == "recipient-ed25519"
            },
            relayStore = store,
            currentAuthenticatedUserId = { "alice" },
            readPublishedDevices = { listOf(replacement) },
            expectedAuthenticatedUserId = "alice",
        )

        val result = runCatching {
            transport.upload(queuedDelivery(), "sender-device")
        }

        assertTrue("A replacement identity key must require a fresh SAS trust decision", result.isFailure)
        assertEquals("replacement-ed25519", checkedIdentityKey)
        assertEquals(0, store.inboxPutAttempts)
        assertTrue(store.inbox.isEmpty())
    }

    private fun transport(
        store: FakeRelayStore,
        user: () -> String?,
    ) = FirebaseOlmEnvelopeTransport(
        trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
        relayStore = store,
        currentAuthenticatedUserId = user,
        readPublishedDevices = { listOf(recipientBundle()) },
        expectedAuthenticatedUserId = user(),
    )

    private fun queuedDelivery(): QueuedOlmDeviceDelivery {
        val envelope = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = "sender-device",
                recipientDeviceId = "recipient-device",
                sessionId = "session-1",
                ciphertext = byteArrayOf(1, 2, 3),
            ),
        )
        return QueuedOlmDeviceDelivery(
            messageId = "message-1",
            deliveryId = "a".repeat(64),
            recipientUserId = "bob",
            recipientDeviceId = "recipient-device",
            ownerUserId = "alice",
            senderDeviceId = "sender-device",
            envelope = envelope,
            conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "bob"),
            conversationPeerUserId = "bob",
            status = OlmDeliveryStatus.IN_FLIGHT,
            attemptCount = 1,
            leaseUntilMillis = 10_000L,
        )
    }

    private fun inboxItem(): FirebaseOlmInboxItem {
        val delivery = queuedDelivery()
        return FirebaseOlmInboxItem(
            deliveryId = delivery.deliveryId,
            senderUid = "alice",
            senderDeviceId = "sender-device",
            messageId = delivery.messageId,
            envelope = delivery.envelope,
            conversationId = OlmAuthenticatedMessagePayloadCodec.directConversationId("alice", "bob"),
            conversationType = "direct",
            conversationPeerUserId = "bob",
        )
    }

    private fun recipientBundle() = PublicDeviceKeyBundle(
        deviceId = "recipient-device",
        ed25519IdentityKey = "recipient-ed25519",
        curve25519IdentityKey = "recipient-curve25519",
        curve25519IdentityKeySignature = "identity-signature",
        fallbackKey = null,
        oneTimeKeys = emptyList(),
    )

    private class FakeRelayStore : OlmRelayStore {
        val inbox = linkedMapOf<String, FirebaseOlmRelayRecord>()
        val acknowledgements = linkedMapOf<String, FirebaseOlmRelayRecord>()
        var inboxPutAttempts = 0
        var inboxReadAttempts = 0
        var inboxRemoveAttempts = 0
        var failNextInboxPutAfterCommit = false
        var failNextInboxRead = false
        var failNextAcknowledgementPutAfterCommit = false
        var failNextInboxRemoveAfterCommit = false
        var afterInboxRead: (() -> Unit)? = null
        var afterAcknowledgementRemove: (() -> Unit)? = null
        val removedAcknowledgementPaths = mutableListOf<String>()

        override suspend fun putInbox(
            recipientUid: String,
            recipientDeviceId: String,
            deliveryId: String,
            fields: Map<String, Any?>,
        ) {
            inboxPutAttempts++
            val existing = inbox[deliveryId]
            if (existing == null) {
                inbox[deliveryId] = FirebaseOlmRelayRecord(deliveryId, fields.toMap())
            } else {
                check(existing.fields == fields) {
                    "A delivery ID cannot be overwritten with different content"
                }
            }
            if (failNextInboxPutAfterCommit) {
                failNextInboxPutAfterCommit = false
                error("Simulated lost upload completion after relay commit")
            }
        }

        override suspend fun readInbox(
            recipientUid: String,
            recipientDeviceId: String,
        ): List<FirebaseOlmRelayRecord> {
            inboxReadAttempts++
            if (failNextInboxRead) {
                failNextInboxRead = false
                error("Simulated temporary inbox read failure")
            }
            val result = inbox.values.toList()
            afterInboxRead?.invoke()
            return result
        }

        override suspend fun removeInbox(
            recipientUid: String,
            recipientDeviceId: String,
            deliveryId: String,
        ) {
            inboxRemoveAttempts++
            inbox.remove(deliveryId)
            if (failNextInboxRemoveAfterCommit) {
                failNextInboxRemoveAfterCommit = false
                error("Simulated lost inbox-delete completion after relay removal")
            }
        }

        override suspend fun putAcknowledgement(
            senderUid: String,
            senderDeviceId: String,
            deliveryId: String,
            fields: Map<String, Any?>,
        ) {
            // Replace the Firebase ServerValue sentinel with the value returned
            // after server resolution so the sender parser can be tested locally.
            val resolvedFields = fields + ("acknowledgedAt" to 1234L)
            val existing = acknowledgements[deliveryId]
            if (existing != null) {
                check(existing.fields["recipientUid"] == resolvedFields["recipientUid"])
                check(existing.fields["recipientDeviceId"] == resolvedFields["recipientDeviceId"])
                check(existing.fields["messageId"] == resolvedFields["messageId"])
            } else {
                acknowledgements[deliveryId] = FirebaseOlmRelayRecord(deliveryId, resolvedFields)
            }
            if (failNextAcknowledgementPutAfterCommit) {
                failNextAcknowledgementPutAfterCommit = false
                error("Simulated lost acknowledgement-write completion after relay commit")
            }
        }

        override suspend fun readAcknowledgements(
            senderUid: String,
            senderDeviceId: String,
        ): List<FirebaseOlmRelayRecord> = acknowledgements.values.toList()

        override suspend fun removeAcknowledgement(
            senderUid: String,
            senderDeviceId: String,
            deliveryId: String,
        ) {
            removedAcknowledgementPaths += "$senderUid/$senderDeviceId/$deliveryId"
            acknowledgements.remove(deliveryId)
            afterAcknowledgementRemove?.invoke()
        }

        fun seedAcknowledgement(item: FirebaseOlmInboxItem) {
            acknowledgements[item.deliveryId] = FirebaseOlmRelayRecord(
                item.deliveryId,
                mapOf(
                    "recipientUid" to "bob",
                    "recipientDeviceId" to "recipient-device",
                    "messageId" to item.messageId,
                    "acknowledgedAt" to 1234L,
                ),
            )
        }

        fun seedInbox(item: FirebaseOlmInboxItem) {
            inbox[item.deliveryId] = FirebaseOlmRelayRecord(
                item.deliveryId,
                mapOf(
                    "senderUid" to item.senderUid,
                    "senderDeviceId" to item.senderDeviceId,
                    "messageId" to item.messageId,
                    "envelope" to item.envelope,
                    "conversationType" to item.conversationType,
                    "conversationId" to item.conversationId,
                ) + if (item.conversationType == "direct") mapOf(
                    "conversationPeerUserId" to item.conversationPeerUserId,
                ) else if (item.groupId != null && item.membershipEpoch != null) mapOf(
                    "groupId" to item.groupId,
                    "membershipEpoch" to item.membershipEpoch,
                ) else emptyMap(),
            )
        }
    }
}
