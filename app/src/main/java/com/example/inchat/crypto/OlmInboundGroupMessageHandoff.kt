package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.security.MessageDigest
import org.json.JSONObject

/** Strictly extracts the authenticated route before membership/session access. */
internal fun extractOlmGroupMessageRouteGroupId(authenticatedOlmPlaintext: String): String {
    val groupId = try {
        val json = JSONObject(authenticatedOlmPlaintext)
        require(json.opt("messageType") == "group_message") {
            "Authenticated Olm payload is not a Megolm group message"
        }
        json.getString("groupId")
    } catch (error: Exception) {
        throw IllegalArgumentException("Authenticated group-message routing is malformed", error)
    }
    require(Regex("[A-Za-z0-9_-]{1,128}").matches(groupId)) {
        "Authenticated group ID has an invalid format"
    }
    return groupId
}

/**
 * Handles an authenticated Olm plaintext that carries a typed Megolm group
 * message. The outer Olm session, sender device signature and SAS trust must
 * already have been verified before this callback is invoked.
 */
internal fun interface OlmInboundGroupMessageHandoff {
    suspend fun process(
        receipt: OlmInboundReceiptEntity,
        remoteDevice: PublicDeviceKeyBundle,
        remoteDeviceUserId: String,
        localUserId: String,
        authenticatedOlmPlaintext: String,
    ): Boolean
}

/**
 * Validates the inner group route against one atomic Worker-authored membership
 * snapshot, stages native Megolm decryption, then commits ciphertext + replay
 * receipt into the isolated group Room tables.
 */
internal class DefaultOlmInboundGroupMessageHandoff(
    private val membershipForGroup: suspend (groupId: String) -> GroupCryptoMembershipSnapshot?,
    private val sessionRepository: MegolmSessionRepository,
    private val messageRepository: EncryptedGroupMessageRepository,
    private val authenticatedLocalUserId: () -> String,
    private val trustVerifier: OlmDeviceTrustVerifier,
) : OlmInboundGroupMessageHandoff {

    override suspend fun process(
        receipt: OlmInboundReceiptEntity,
        remoteDevice: PublicDeviceKeyBundle,
        remoteDeviceUserId: String,
        localUserId: String,
        authenticatedOlmPlaintext: String,
    ): Boolean {
        require(localUserId == authenticatedLocalUserId()) {
            "Group message recipient does not match the authenticated account"
        }
        require(remoteDeviceUserId == receipt.senderUserId &&
            remoteDevice.deviceId == receipt.senderDeviceId
        ) { "Verified sender device does not match the durable Olm receipt" }
        check(
            trustVerifier.isVerified(
                peerUserId = remoteDeviceUserId,
                peerDeviceId = remoteDevice.deviceId,
                peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
            )
        ) { "Group sender device is not SAS-trusted on this installation" }

        val groupId = extractOlmGroupMessageRouteGroupId(authenticatedOlmPlaintext)

        // The worker-authored epoch and full membership map are retrieved as one
        // value; never combine unrelated legacy group-member listeners.
        val membership = requireNotNull(membershipForGroup(groupId)) {
            "Group has no valid E2EE membership snapshot"
        }
        require(membership.isValid() && membership.groupId == groupId) {
            "Current E2EE group membership snapshot is invalid"
        }
        val payload = OlmGroupMessagePayloadCodec.decodeAndValidate(
            plaintext = authenticatedOlmPlaintext,
            expectedMessageId = receipt.messageId,
            expectedGroupId = membership.groupId,
            expectedMembershipEpoch = membership.epoch,
            expectedSenderUserId = receipt.senderUserId,
            expectedSenderDeviceId = receipt.senderDeviceId,
            expectedRecipientUserId = localUserId,
            expectedRecipientDeviceId = receipt.recipientDeviceId,
        )
        OlmGroupMessagePayloadCodec.requireCurrentMembers(payload, membership.memberUserIds)
        // Re-read immediately before advancing the local Megolm receive ratchet.
        // Matching the whole map as well as the epoch prevents accepting a stale
        // snapshot if a backend membership writer ever fails to advance its epoch.
        val currentMembership = requireUnchangedGroupCryptoMembershipSnapshot(
            expected = membership,
            latest = membershipForGroup(groupId),
        )

        val staged = sessionRepository.prepareInboundMessage(
            membership = currentMembership,
            payload = payload,
            receiptId = receipt.receiptId,
            receivedAtMillis = receipt.receivedAtMillis,
        )
        try {
            val ciphertextDigest = MessageDigest.getInstance("SHA-256")
                .digest(staged.ciphertext)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val replayReceipt = EncryptedGroupMessageReceiptEntity(
                ownerUserId = staged.message.ownerUserId,
                groupId = staged.message.groupId,
                senderUserId = staged.message.senderUserId,
                senderDeviceId = staged.message.senderDeviceId,
                megolmSessionId = staged.message.megolmSessionId,
                messageIndex = staged.message.messageIndex,
                membershipEpoch = staged.message.membershipEpoch,
                messageId = staged.message.messageId,
                sentAtMillis = staged.message.sentAtMillis,
                receiptId = staged.receiptId,
                ciphertextSha256 = ciphertextDigest,
                receivedAtMillis = staged.receivedAtMillis,
            )
            // The repository encrypts the text with Keystore and transactionally
            // writes both the message and replay key. The caller then finalizes
            // native Megolm and Olm stages only after this returns.
            return messageRepository.commitStagedMessage(
                message = staged.message,
                receipt = replayReceipt,
                originalCiphertext = staged.ciphertext,
            )
        } finally {
            staged.ciphertext.fill(0)
        }
    }

    private companion object {
    }
}
