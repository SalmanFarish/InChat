package com.example.inchat.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartitionedOlmEnvelopeTransportTest {
    @Test
    fun directUploadsUseCloudflareWhileGroupUploadsRemainOnFirebase() = runBlocking {
        val firebase = RecordingRelay()
        val cloudflare = RecordingRelay()
        val transport = PartitionedOlmEnvelopeTransport(firebase, cloudflare, cloudflareDirectEnabled = true)

        transport.upload(directDelivery(), "sender-device")
        transport.upload(groupDelivery(), "sender-device")

        assertEquals(listOf("group-message"), firebase.uploads.map { it.messageId })
        assertEquals(listOf("direct-message"), cloudflare.uploads.map { it.messageId })
    }

    @Test
    fun groupUploadsUseCloudflareOnlyWhenTheGroupRouteIsEnabled() = runBlocking {
        val firebase = RecordingRelay()
        val cloudflare = RecordingRelay()
        val transport = PartitionedOlmEnvelopeTransport(
            firebase = firebase,
            cloudflare = cloudflare,
            cloudflareDirectEnabled = false,
            cloudflareGroupEnabled = true,
        )

        transport.upload(directDelivery(), "sender-device")
        transport.upload(groupDelivery(), "sender-device")

        assertEquals(listOf("direct-message"), firebase.uploads.map { it.messageId })
        assertEquals(listOf("group-message"), cloudflare.uploads.map { it.messageId })
    }

    @Test
    fun groupInboxUsesCloudflareAndKeepsLegacyFirebaseItemsOnTheirOriginalRelay() = runBlocking {
        val firebase = RecordingRelay(inbox = listOf(groupInbox("legacy-group"), directInbox("legacy-direct")))
        val cloudflare = RecordingRelay(
            inbox = listOf(groupInbox("migrated-group")),
            acks = listOf(deliveryAck("group-ack")),
        )
        val transport = PartitionedOlmEnvelopeTransport(
            firebase = firebase,
            cloudflare = cloudflare,
            cloudflareDirectEnabled = false,
            cloudflareGroupEnabled = true,
        )

        val items = transport.readMyDeviceInbox("recipient-device")
        assertEquals(
            listOf("legacy-direct", "legacy-group", "migrated-group"),
            items.map { it.deliveryId },
        )
        val migrated = items.single { it.deliveryId == "migrated-group" }
        assertEquals(OlmRelayOrigin.CLOUDFLARE, migrated.relayOrigin)
        transport.acknowledgeStoredDelivery("recipient-device", migrated)
        transport.removeFromMyDeviceInbox("recipient-device", migrated)

        val acks = transport.readMyDeliveryAcknowledgements("sender-device")
        assertEquals(OlmRelayOrigin.CLOUDFLARE, acks.single().relayOrigin)
        transport.removeMyDeliveryAcknowledgement("sender-device", acks.single())

        assertEquals(listOf("migrated-group"), cloudflare.acknowledgementsCreated)
        assertEquals(listOf("migrated-group"), cloudflare.inboxDeletes)
        assertEquals(listOf("group-ack"), cloudflare.ackDeletes)
        assertTrue(firebase.acknowledgementsCreated.isEmpty())
        assertTrue(firebase.inboxDeletes.isEmpty())
    }

    @Test
    fun cloudflareGroupMailboxRejectsUnexpectedDirectDelivery() = runBlocking {
        val transport = PartitionedOlmEnvelopeTransport(
            firebase = RecordingRelay(),
            cloudflare = RecordingRelay(inbox = listOf(directInbox("unexpected-direct"))),
            cloudflareDirectEnabled = false,
            cloudflareGroupEnabled = true,
        )

        val failure = runCatching { transport.readMyDeviceInbox("recipient-device") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun disabledDirectRouteKeepsEveryDeliveryOnFirebase() = runBlocking {
        val firebase = RecordingRelay()
        val cloudflare = RecordingRelay()
        val transport = PartitionedOlmEnvelopeTransport(firebase, cloudflare, cloudflareDirectEnabled = false)

        transport.upload(directDelivery(), "sender-device")
        transport.upload(groupDelivery(), "sender-device")

        assertEquals(listOf("direct-message", "group-message"), firebase.uploads.map { it.messageId })
        assertTrue(cloudflare.uploads.isEmpty())
        assertEquals(0, cloudflare.inboxReads)
        assertEquals(0, cloudflare.ackReads)
    }

    @Test
    fun inboxPollMergesLegacyFirebaseAndCloudflareDirectItemsWithCorrectRelayOrigins() = runBlocking {
        val firebase = RecordingRelay(
            inbox = listOf(directInbox("legacy-direct"), groupInbox("legacy-group")),
        )
        val cloudflare = RecordingRelay(inbox = listOf(directInbox("new-direct")))
        val transport = PartitionedOlmEnvelopeTransport(firebase, cloudflare, cloudflareDirectEnabled = true)

        val items = transport.readMyDeviceInbox("recipient-device")

        assertEquals(listOf("legacy-direct", "legacy-group", "new-direct"), items.map { it.deliveryId })
        assertEquals(
            listOf(OlmRelayOrigin.FIREBASE, OlmRelayOrigin.FIREBASE, OlmRelayOrigin.CLOUDFLARE),
            items.map { it.relayOrigin },
        )

        val legacy = items.first { it.deliveryId == "legacy-direct" }
        val group = items.first { it.deliveryId == "legacy-group" }
        val migrated = items.first { it.deliveryId == "new-direct" }
        transport.acknowledgeStoredDelivery("recipient-device", legacy)
        transport.removeFromMyDeviceInbox("recipient-device", legacy)
        transport.acknowledgeStoredDelivery("recipient-device", group)
        transport.removeFromMyDeviceInbox("recipient-device", group)
        transport.acknowledgeStoredDelivery("recipient-device", migrated)
        transport.removeFromMyDeviceInbox("recipient-device", migrated)

        assertEquals(listOf("legacy-direct", "legacy-group"), firebase.acknowledgementsCreated)
        assertEquals(listOf("legacy-direct", "legacy-group"), firebase.inboxDeletes)
        assertEquals(listOf("new-direct"), cloudflare.acknowledgementsCreated)
        assertEquals(listOf("new-direct"), cloudflare.inboxDeletes)
    }

    @Test
    fun exactDuplicateAcrossRelaysIsAcknowledgedAndRemovedFromBoth() = runBlocking {
        val duplicate = directInbox("same-delivery")
        val firebase = RecordingRelay(inbox = listOf(duplicate))
        val cloudflare = RecordingRelay(inbox = listOf(duplicate))
        val transport = PartitionedOlmEnvelopeTransport(firebase, cloudflare, cloudflareDirectEnabled = true)

        val item = transport.readMyDeviceInbox("recipient-device").single()
        assertEquals(OlmRelayOrigin.BOTH, item.relayOrigin)

        transport.acknowledgeStoredDelivery("recipient-device", item)
        transport.removeFromMyDeviceInbox("recipient-device", item)

        assertEquals(listOf("same-delivery"), firebase.acknowledgementsCreated)
        assertEquals(listOf("same-delivery"), cloudflare.acknowledgementsCreated)
        assertEquals(listOf("same-delivery"), firebase.inboxDeletes)
        assertEquals(listOf("same-delivery"), cloudflare.inboxDeletes)
    }

    @Test
    fun sameDeliveryIdWithDifferentCiphertextIsKeptAsAConflict() = runBlocking {
        val firebaseItem = directInbox("same-delivery")
        val cloudflareItem = firebaseItem.copy(envelope = "different-ciphertext")
        val transport = PartitionedOlmEnvelopeTransport(
            firebase = RecordingRelay(inbox = listOf(firebaseItem)),
            cloudflare = RecordingRelay(inbox = listOf(cloudflareItem)),
            cloudflareDirectEnabled = true,
        )

        val items = transport.readMyDeviceInbox("recipient-device")

        assertEquals(2, items.size)
        assertTrue(items.any { it.relayOrigin == OlmRelayOrigin.FIREBASE && it.envelope == firebaseItem.envelope })
        assertTrue(items.any { it.relayOrigin == OlmRelayOrigin.CLOUDFLARE && it.envelope == cloudflareItem.envelope })
    }

    @Test
    fun duplicateAcknowledgementsAreRemovedFromBothRelays() = runBlocking {
        val acknowledgement = deliveryAck("same-ack")
        val firebase = RecordingRelay(acks = listOf(acknowledgement))
        val cloudflare = RecordingRelay(acks = listOf(acknowledgement))
        val transport = PartitionedOlmEnvelopeTransport(firebase, cloudflare, cloudflareDirectEnabled = true)

        val result = transport.readMyDeliveryAcknowledgements("sender-device")
        assertEquals(1, result.size)
        assertEquals(OlmRelayOrigin.BOTH, result.single().relayOrigin)

        transport.removeMyDeliveryAcknowledgement("sender-device", result.single())

        assertEquals(listOf("same-ack"), firebase.ackDeletes)
        assertEquals(listOf("same-ack"), cloudflare.ackDeletes)
    }

    @Test
    fun cloudflareDirectMailboxRejectsUnexpectedGroupDelivery() = runBlocking {
        val transport = PartitionedOlmEnvelopeTransport(
            firebase = RecordingRelay(),
            cloudflare = RecordingRelay(inbox = listOf(groupInbox("unexpected-group"))),
            cloudflareDirectEnabled = true,
        )

        val failure = runCatching { transport.readMyDeviceInbox("recipient-device") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun directDelivery() = QueuedOlmDeviceDelivery(
        messageId = "direct-message",
        deliveryId = "a".repeat(64),
        recipientUserId = "bob",
        recipientDeviceId = "recipient-device",
        ownerUserId = "alice",
        senderDeviceId = "sender-device",
        conversationId = "alice_bob",
        conversationPeerUserId = "bob",
        envelope = "ciphertext",
        status = OlmDeliveryStatus.IN_FLIGHT,
        attemptCount = 1,
        leaseUntilMillis = 10_000L,
    )

    private fun groupDelivery() = directDelivery().copy(
        messageId = "group-message",
        deliveryId = "b".repeat(64),
        groupId = "group-1",
        membershipEpoch = 3L,
        conversationId = "group-1",
        conversationPeerUserId = null,
    )

    private fun directInbox(id: String) = FirebaseOlmInboxItem(
        deliveryId = id,
        senderUid = "alice",
        senderDeviceId = "sender-device",
        messageId = "message-" + id,
        envelope = "ciphertext",
        conversationId = "alice_bob",
        conversationType = "direct",
        conversationPeerUserId = "bob",
    )

    private fun groupInbox(id: String) = FirebaseOlmInboxItem(
        deliveryId = id,
        senderUid = "alice",
        senderDeviceId = "sender-device",
        messageId = "message-" + id,
        envelope = "ciphertext",
        groupId = "group-1",
        membershipEpoch = 3L,
        conversationId = "group-1",
        conversationType = "group",
    )

    private fun deliveryAck(id: String) = OlmDeliveryAcknowledgement(
        deliveryId = id,
        recipientUserId = "bob",
        recipientDeviceId = "recipient-device",
        messageId = "message-" + id,
        senderUserId = "alice",
        senderDeviceId = "sender-device",
    )

    private class RecordingRelay(
        var inbox: List<FirebaseOlmInboxItem> = emptyList(),
        var acks: List<OlmDeliveryAcknowledgement> = emptyList(),
    ) : OlmEnvelopeRelayTransport {
        val uploads = mutableListOf<QueuedOlmDeviceDelivery>()
        val acknowledgementsCreated = mutableListOf<String>()
        val inboxDeletes = mutableListOf<String>()
        val ackDeletes = mutableListOf<String>()
        var inboxReads = 0
        var ackReads = 0

        override suspend fun upload(
            delivery: QueuedOlmDeviceDelivery,
            senderDeviceId: String,
        ): FirebaseOlmUploadResult {
            uploads += delivery
            return FirebaseOlmUploadResult.STORED
        }

        override suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem> {
            inboxReads++
            return inbox
        }

        override suspend fun acknowledgeStoredDelivery(deviceId: String, item: FirebaseOlmInboxItem) {
            acknowledgementsCreated += item.deliveryId
        }

        override suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String) {
            inboxDeletes += deliveryId
        }

        override suspend fun readMyDeliveryAcknowledgements(
            senderDeviceId: String,
        ): List<OlmDeliveryAcknowledgement> {
            ackReads++
            return acks
        }

        override suspend fun removeMyDeliveryAcknowledgement(senderDeviceId: String, deliveryId: String) {
            ackDeletes += deliveryId
        }
    }
}
