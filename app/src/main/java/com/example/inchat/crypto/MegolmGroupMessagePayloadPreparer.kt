package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot

/**
 * Creates the authenticated inner routing envelope for one trusted recipient device.
 *
 * The same Megolm ciphertext/message ID can be fanned out to every current
 * member device, but each Olm plaintext binds one exact recipient device.
 */
internal class MegolmGroupMessagePayloadPreparer(
    private val authenticatedUserId: () -> String,
    private val localDeviceId: () -> String,
) {
    fun prepareForRecipient(
        membership: GroupCryptoMembershipSnapshot,
        recipientUserId: String,
        recipientDeviceId: String,
        messageId: String,
        encryptedMessage: EncryptedMegolmMessage,
        sentAtMillis: Long,
    ): String {
        require(membership.isValid()) { "Authoritative group membership snapshot is invalid" }
        val senderUserId = authenticatedUserId()
        val senderDeviceId = localDeviceId()
        require(senderUserId in membership.memberUserIds) {
            "Authenticated sender is not a current group member"
        }
        require(recipientUserId in membership.memberUserIds) {
            "Group message recipient is not a current member"
        }
        require(recipientDeviceId.matches(DEVICE_ID)) { "Recipient device ID is invalid" }
        require(senderDeviceId.matches(DEVICE_ID)) { "Sender device ID is invalid" }
        require(
            senderUserId != recipientUserId || senderDeviceId != recipientDeviceId
        ) { "A device cannot send a group message to itself" }

        val payload = OlmGroupMessagePayload(
            messageId = messageId,
            groupId = membership.groupId,
            membershipEpoch = membership.epoch,
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            recipientUserId = recipientUserId,
            recipientDeviceId = recipientDeviceId,
            megolmSessionId = encryptedMessage.sessionId,
            messageIndex = encryptedMessage.messageIndex,
            ciphertext = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(encryptedMessage.ciphertext),
            sentAtMillis = sentAtMillis,
        )
        return OlmGroupMessagePayloadCodec.encode(payload)
    }

    private companion object {
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
