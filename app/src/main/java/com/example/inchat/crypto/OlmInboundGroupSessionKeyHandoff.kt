package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64
import org.json.JSONObject

/** Receives a signed Megolm session key from an authenticated Olm control message. */
internal fun interface OlmInboundGroupSessionKeyHandoff {
    suspend fun process(
        receipt: OlmInboundReceiptEntity,
        remoteDevice: PublicDeviceKeyBundle,
        remoteDeviceUserId: String,
        localUserId: String,
        authenticatedOlmPlaintext: String,
    ): Boolean
}

/** Narrow native boundary for importing a previously authenticated Megolm session key. */
internal fun interface MegolmInboundSessionKeyImporter {
    fun importInboundSessionKey(
        membership: GroupCryptoMembershipSnapshot,
        senderUserId: String,
        senderDeviceId: String,
        expectedSessionId: String,
        sessionKey: ByteArray,
    ): Boolean
}

/**
 * Verifies the exact current group epoch and sender device before importing a
 * Megolm key. The native import preserves an existing receive ratchet on exact
 * duplicate distribution, so a crash after import can safely retry its Olm receipt.
 */
internal class DefaultOlmInboundGroupSessionKeyHandoff(
    private val membershipForGroup: suspend (groupId: String) -> GroupCryptoMembershipSnapshot?,
    private val keyImporter: MegolmInboundSessionKeyImporter,
    private val authenticatedLocalUserId: () -> String,
    private val trustVerifier: OlmDeviceTrustVerifier,
) : OlmInboundGroupSessionKeyHandoff {

    override suspend fun process(
        receipt: OlmInboundReceiptEntity,
        remoteDevice: PublicDeviceKeyBundle,
        remoteDeviceUserId: String,
        localUserId: String,
        authenticatedOlmPlaintext: String,
    ): Boolean {
        require(localUserId == authenticatedLocalUserId()) {
            "Group key recipient does not match the authenticated account"
        }
        require(remoteDeviceUserId == receipt.senderUserId &&
            remoteDevice.deviceId == receipt.senderDeviceId
        ) { "Verified sender device does not match the durable Olm receipt" }
        check(
            trustVerifier.isTrustedForMessaging(
                peerUserId = remoteDeviceUserId,
                peerDeviceId = remoteDevice.deviceId,
                peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
            )
        ) { "Group key sender identity has changed. Compare safety numbers before accepting this key" }

        val groupId = try {
            val json = JSONObject(authenticatedOlmPlaintext)
            require(json.opt("messageType") == "group_session_key") {
                "Authenticated Olm payload is not a group session-key distribution"
            }
            require(json.opt("groupId") is String) {
                "Authenticated group ID must be a string"
            }
            json.getString("groupId")
        } catch (error: Exception) {
            throw IllegalArgumentException("Authenticated group-key routing is malformed", error)
        }
        require(GROUP_ID.matches(groupId)) { "Authenticated group ID has an invalid format" }

        val membership = requireNotNull(membershipForGroup(groupId)) {
            "Group has no valid E2EE membership snapshot"
        }
        require(membership.isValid() && membership.groupId == groupId) {
            "Current E2EE group membership snapshot is invalid"
        }
        val payload = OlmGroupSessionKeyDistributionPayloadCodec.decodeAndValidate(
            plaintext = authenticatedOlmPlaintext,
            expectedDistributionId = receipt.messageId,
            expectedGroupId = membership.groupId,
            expectedMembershipEpoch = membership.epoch,
            expectedSenderUserId = receipt.senderUserId,
            expectedSenderDeviceId = receipt.senderDeviceId,
            expectedRecipientUserId = localUserId,
            expectedRecipientDeviceId = receipt.recipientDeviceId,
        )
        OlmGroupSessionKeyDistributionPayloadCodec.requireCurrentMembers(
            payload,
            membership.memberUserIds,
        )

        val sessionKey = try {
            Base64.getUrlDecoder().decode(payload.sessionKey)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Megolm session key is not valid Base64URL", error)
        }
        return try {
            require(sessionKey.isNotEmpty() && sessionKey.size <= MAX_SESSION_KEY_BYTES) {
                "Megolm session key has an invalid size"
            }
            // Membership is server-authored and can change while the encrypted
            // payload is being decoded. Re-read it immediately before the native
            // import, requiring the full member/role map and epoch to be identical.
            val currentMembership = requireUnchangedGroupCryptoMembershipSnapshot(
                expected = membership,
                latest = membershipForGroup(groupId),
            )
            // Membership lookup and key decoding can suspend. Fence the import
            // against sign-out/account replacement just like the message commit.
            requireUnchangedGroupRecipient(localUserId, authenticatedLocalUserId())
            // Membership lookup suspends and can take long enough for local SAS
            // trust to be revoked. Recheck the pinned sender identity immediately before importing the key.
            check(
                trustVerifier.isTrustedForMessaging(
                    peerUserId = remoteDeviceUserId,
                    peerDeviceId = remoteDevice.deviceId,
                    peerEd25519IdentityKey = remoteDevice.ed25519IdentityKey,
                )
            ) { "Group key sender identity changed before native import" }
            // This import persists the signed session and its sender/epoch binding
            // in the same Keystore-protected native device-state envelope.
            keyImporter.importInboundSessionKey(
                membership = currentMembership,
                senderUserId = payload.senderUserId,
                senderDeviceId = payload.senderDeviceId,
                expectedSessionId = payload.megolmSessionId,
                sessionKey = sessionKey,
            )
        } finally {
            sessionKey.fill(0)
        }
    }

    private companion object {
        const val MAX_SESSION_KEY_BYTES = 1024
        val GROUP_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
