package com.example.inchat.crypto

/**
 * Phased encrypted relay migration: direct messages may use Cloudflare while
 * group deliveries can be migrated independently. Both inboxes are polled during
 * the transition so old Firebase deliveries are drained from their original relay.
 */
internal class PartitionedOlmEnvelopeTransport(
    private val firebase: OlmEnvelopeRelayTransport,
    private val cloudflare: OlmEnvelopeRelayTransport,
    private val cloudflareDirectEnabled: Boolean,
    private val cloudflareGroupEnabled: Boolean = false,
) : OlmEnvelopeRelayTransport {

    override suspend fun upload(
        delivery: QueuedOlmDeviceDelivery,
        senderDeviceId: String,
    ): FirebaseOlmUploadResult {
        val isDirect = delivery.groupId == null &&
            delivery.membershipEpoch == null &&
            delivery.conversationPeerUserId != null &&
            delivery.conversationId != null
        val isGroup = delivery.groupId != null &&
            delivery.membershipEpoch != null &&
            delivery.conversationId == delivery.groupId &&
            delivery.conversationPeerUserId == null
        val useCloudflare = (cloudflareDirectEnabled && isDirect) ||
            (cloudflareGroupEnabled && isGroup)
        return if (useCloudflare) {
            cloudflare.upload(delivery, senderDeviceId)
        } else {
            firebase.upload(delivery, senderDeviceId)
        }
    }

    override suspend fun readMyDeviceInbox(deviceId: String): List<FirebaseOlmInboxItem> {
        val firebaseItems = firebase.readMyDeviceInbox(deviceId)
            .map { it.copy(relayOrigin = OlmRelayOrigin.FIREBASE) }
        if (!cloudflareDirectEnabled) return firebaseItems

        val cloudflareItems = cloudflare.readMyDeviceInbox(deviceId).map { item ->
            require(
                item.conversationType == "direct" &&
                    item.groupId == null &&
                    item.membershipEpoch == null &&
                    item.conversationPeerUserId != null
            ) { "Cloudflare direct mailbox returned a non-direct delivery." }
            item.copy(relayOrigin = OlmRelayOrigin.CLOUDFLARE)
        }
        return mergeInboxItems(firebaseItems, cloudflareItems)
    }

    override suspend fun acknowledgeStoredDelivery(deviceId: String, item: FirebaseOlmInboxItem) {
        when (item.relayOrigin) {
            OlmRelayOrigin.FIREBASE -> firebase.acknowledgeStoredDelivery(deviceId, item)
            OlmRelayOrigin.CLOUDFLARE -> cloudflare.acknowledgeStoredDelivery(deviceId, item)
            OlmRelayOrigin.BOTH -> {
                firebase.acknowledgeStoredDelivery(deviceId, item)
                cloudflare.acknowledgeStoredDelivery(deviceId, item)
            }
        }
    }

    override suspend fun removeFromMyDeviceInbox(deviceId: String, deliveryId: String) {
        firebase.removeFromMyDeviceInbox(deviceId, deliveryId)
        if (cloudflareDirectEnabled) cloudflare.removeFromMyDeviceInbox(deviceId, deliveryId)
    }

    override suspend fun removeFromMyDeviceInbox(deviceId: String, item: FirebaseOlmInboxItem) {
        when (item.relayOrigin) {
            OlmRelayOrigin.FIREBASE -> firebase.removeFromMyDeviceInbox(deviceId, item)
            OlmRelayOrigin.CLOUDFLARE -> cloudflare.removeFromMyDeviceInbox(deviceId, item)
            OlmRelayOrigin.BOTH -> {
                firebase.removeFromMyDeviceInbox(deviceId, item)
                cloudflare.removeFromMyDeviceInbox(deviceId, item)
            }
        }
    }

    override suspend fun readMyDeliveryAcknowledgements(
        senderDeviceId: String,
    ): List<OlmDeliveryAcknowledgement> {
        val firebaseAcks = firebase.readMyDeliveryAcknowledgements(senderDeviceId)
            .map { it.copy(relayOrigin = OlmRelayOrigin.FIREBASE) }
        if (!cloudflareDirectEnabled) return firebaseAcks
        val cloudflareAcks = cloudflare.readMyDeliveryAcknowledgements(senderDeviceId)
            .map { it.copy(relayOrigin = OlmRelayOrigin.CLOUDFLARE) }
        return mergeAcknowledgements(firebaseAcks, cloudflareAcks)
    }

    override suspend fun removeMyDeliveryAcknowledgement(senderDeviceId: String, deliveryId: String) {
        firebase.removeMyDeliveryAcknowledgement(senderDeviceId, deliveryId)
        if (cloudflareDirectEnabled) {
            cloudflare.removeMyDeliveryAcknowledgement(senderDeviceId, deliveryId)
        }
    }

    override suspend fun removeMyDeliveryAcknowledgement(
        senderDeviceId: String,
        acknowledgement: OlmDeliveryAcknowledgement,
    ) {
        when (acknowledgement.relayOrigin) {
            OlmRelayOrigin.FIREBASE ->
                firebase.removeMyDeliveryAcknowledgement(senderDeviceId, acknowledgement.deliveryId)
            OlmRelayOrigin.CLOUDFLARE ->
                cloudflare.removeMyDeliveryAcknowledgement(senderDeviceId, acknowledgement.deliveryId)
            OlmRelayOrigin.BOTH -> {
                firebase.removeMyDeliveryAcknowledgement(senderDeviceId, acknowledgement.deliveryId)
                cloudflare.removeMyDeliveryAcknowledgement(senderDeviceId, acknowledgement.deliveryId)
            }
        }
    }

    private fun mergeInboxItems(
        firebaseItems: List<FirebaseOlmInboxItem>,
        cloudflareItems: List<FirebaseOlmInboxItem>,
    ): List<FirebaseOlmInboxItem> {
        val firebaseById = firebaseItems.associateBy(FirebaseOlmInboxItem::deliveryId)
        val cloudflareById = cloudflareItems.associateBy(FirebaseOlmInboxItem::deliveryId)
        return buildList {
            (firebaseById.keys + cloudflareById.keys).sorted().forEach { id ->
                val firebaseItem = firebaseById[id]
                val cloudflareItem = cloudflareById[id]
                when {
                    firebaseItem == null -> add(requireNotNull(cloudflareItem))
                    cloudflareItem == null -> add(firebaseItem)
                    firebaseItem.copy(relayOrigin = OlmRelayOrigin.FIREBASE) ==
                        cloudflareItem.copy(relayOrigin = OlmRelayOrigin.FIREBASE) ->
                        add(firebaseItem.copy(relayOrigin = OlmRelayOrigin.BOTH))
                    else -> {
                        // Never select one ciphertext when a deterministic ID collides.
                        add(firebaseItem)
                        add(cloudflareItem)
                    }
                }
            }
        }
    }

    private fun mergeAcknowledgements(
        firebaseAcks: List<OlmDeliveryAcknowledgement>,
        cloudflareAcks: List<OlmDeliveryAcknowledgement>,
    ): List<OlmDeliveryAcknowledgement> {
        val firebaseById = firebaseAcks.associateBy(OlmDeliveryAcknowledgement::deliveryId)
        val cloudflareById = cloudflareAcks.associateBy(OlmDeliveryAcknowledgement::deliveryId)
        return buildList {
            (firebaseById.keys + cloudflareById.keys).sorted().forEach { id ->
                val firebaseAck = firebaseById[id]
                val cloudflareAck = cloudflareById[id]
                when {
                    firebaseAck == null -> add(requireNotNull(cloudflareAck))
                    cloudflareAck == null -> add(firebaseAck)
                    firebaseAck.copy(relayOrigin = OlmRelayOrigin.FIREBASE) ==
                        cloudflareAck.copy(relayOrigin = OlmRelayOrigin.FIREBASE) ->
                        add(firebaseAck.copy(relayOrigin = OlmRelayOrigin.BOTH))
                    else -> {
                        add(firebaseAck)
                        add(cloudflareAck)
                    }
                }
            }
        }
    }
}
