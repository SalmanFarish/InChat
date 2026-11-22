package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/**
 * Validates that one queued device delivery still belongs to the exact group
 * membership generation that authorized its Megolm fan-out.
 *
 * This deliberately checks both durable commit identity and the claimed row:
 * callers must not accidentally validate a different sender/message against a
 * valid snapshot.
 */
internal fun isCommittedGroupDeliveryCurrent(
    commit: EncryptedGroupOutgoingCommitEntity,
    delivery: QueuedOlmDeviceDelivery,
    authenticatedOwnerUserId: String,
    authenticatedSenderDeviceId: String,
    membership: GroupCryptoMembershipSnapshot,
): Boolean =
    commit.recipientDeviceCount in 1..MAX_OLM_DEVICE_DELIVERY_TARGETS &&
        commit.membershipEpoch > 0L &&
        commit.megolmSessionId.isNotBlank() && commit.megolmSessionId.length <= 128 &&
        commit.messageIndex >= 0L && commit.sentAtMillis >= 0L &&
        commit.ownerUserId == authenticatedOwnerUserId &&
        commit.senderDeviceId == authenticatedSenderDeviceId &&
        commit.messageId == delivery.messageId &&
        delivery.ownerUserId == authenticatedOwnerUserId &&
        delivery.senderDeviceId == authenticatedSenderDeviceId &&
        membership.isValid() &&
        membership.groupId == commit.groupId &&
        membership.epoch == commit.membershipEpoch &&
        authenticatedOwnerUserId in membership.memberUserIds &&
        delivery.recipientUserId in membership.memberUserIds
