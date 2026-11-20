package com.example.inchat.crypto

import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

/**
 * One group ciphertext addressed to one device inside an authenticated Olm envelope.
 *
 * Megolm ciphertext is not published to a group-wide plaintext relay: callers
 * wrap this payload in a separate authenticated Olm message for every trusted
 * recipient device and bind the outer receipt to messageId and sender identity.
 */
internal data class OlmGroupMessagePayload(
    val messageId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val megolmSessionId: String,
    val messageIndex: Long,
    /** Canonical unpadded Base64URL Megolm wire ciphertext. */
    val ciphertext: String,
    val sentAtMillis: Long,
)

internal object OlmGroupMessagePayloadCodec {
    private const val SCHEMA_VERSION = 1
    private const val MESSAGE_TYPE = "group_message"
    private const val MAX_CIPHERTEXT_BYTES = 1024 * 1024
    private const val MAX_PAYLOAD_BYTES = 1400 * 1024

    private val ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
    private val FIELDS = setOf(
        "schemaVersion", "messageType", "messageId", "groupId", "membershipEpoch",
        "senderUserId", "senderDeviceId", "recipientUserId", "recipientDeviceId",
        "megolmSessionId", "messageIndex", "ciphertext", "sentAtMillis",
    )

    fun encode(payload: OlmGroupMessagePayload): String {
        validate(payload)
        val encoded = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("messageType", MESSAGE_TYPE)
            .put("messageId", payload.messageId)
            .put("groupId", payload.groupId)
            .put("membershipEpoch", payload.membershipEpoch)
            .put("senderUserId", payload.senderUserId)
            .put("senderDeviceId", payload.senderDeviceId)
            .put("recipientUserId", payload.recipientUserId)
            .put("recipientDeviceId", payload.recipientDeviceId)
            .put("megolmSessionId", payload.megolmSessionId)
            .put("messageIndex", payload.messageIndex)
            .put("ciphertext", payload.ciphertext)
            .put("sentAtMillis", payload.sentAtMillis)
            .toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Encrypted group-message payload exceeds the size limit"
        }
        return encoded
    }

    /**
     * Bind the inner payload to the authenticated Olm receipt and the latest
     * verified membership snapshot before any Megolm ratchet is touched.
     */
    fun decodeAndValidate(
        plaintext: String,
        expectedMessageId: String,
        expectedGroupId: String,
        expectedMembershipEpoch: Long,
        expectedSenderUserId: String,
        expectedSenderDeviceId: String,
        expectedRecipientUserId: String,
        expectedRecipientDeviceId: String,
    ): OlmGroupMessagePayload {
        require(plaintext.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Encrypted group-message payload exceeds the size limit"
        }
        val json = try {
            JSONObject(plaintext)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Encrypted group-message payload is not valid JSON", error)
        }
        require(json.keys().asSequence().toSet() == FIELDS) {
            "Encrypted group-message payload has missing or unknown fields"
        }
        val version = json.opt("schemaVersion")
        require(version is Number && version.toDouble() == SCHEMA_VERSION.toDouble()) {
            "Unsupported encrypted group-message payload version"
        }
        require(json.opt("messageType") == MESSAGE_TYPE) {
            "Unsupported encrypted group payload type"
        }

        fun stringField(name: String): String {
            val value = json.opt(name)
            require(value is String) { "Encrypted group-message field '$name' must be text" }
            return value
        }

        fun longField(name: String): Long {
            val value = json.opt(name)
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Encrypted group-message field '$name' must be an integer"
            }
            return (value as Number).toLong()
        }

        val payload = OlmGroupMessagePayload(
            messageId = stringField("messageId"),
            groupId = stringField("groupId"),
            membershipEpoch = longField("membershipEpoch"),
            senderUserId = stringField("senderUserId"),
            senderDeviceId = stringField("senderDeviceId"),
            recipientUserId = stringField("recipientUserId"),
            recipientDeviceId = stringField("recipientDeviceId"),
            megolmSessionId = stringField("megolmSessionId"),
            messageIndex = longField("messageIndex"),
            ciphertext = stringField("ciphertext"),
            sentAtMillis = longField("sentAtMillis"),
        )
        validate(payload)
        require(encode(payload) == plaintext) {
            "Encrypted group-message payload is not in canonical form"
        }
        require(payload.messageId == expectedMessageId) {
            "Group message ID does not match its authenticated Olm receipt"
        }
        require(payload.groupId == expectedGroupId) {
            "Group message belongs to a different group"
        }
        require(payload.membershipEpoch == expectedMembershipEpoch) {
            "Group message membership epoch is stale or unexpected"
        }
        require(payload.senderUserId == expectedSenderUserId) {
            "Group message sender account does not match its Olm receipt"
        }
        require(payload.senderDeviceId == expectedSenderDeviceId) {
            "Group message sender device does not match its Olm receipt"
        }
        require(payload.recipientUserId == expectedRecipientUserId) {
            "Group message recipient account does not match the local account"
        }
        require(payload.recipientDeviceId == expectedRecipientDeviceId) {
            "Group message recipient device does not match the local device"
        }
        return payload
    }

    fun requireCurrentMembers(
        payload: OlmGroupMessagePayload,
        currentMemberUserIds: Set<String>,
    ) {
        require(currentMemberUserIds.size in 2..50) {
            "Current group membership snapshot has an invalid size"
        }
        require(payload.senderUserId in currentMemberUserIds) {
            "Group message sender is not a current member"
        }
        require(payload.recipientUserId in currentMemberUserIds) {
            "Group message recipient is not a current member"
        }
    }

    private fun validate(payload: OlmGroupMessagePayload) {
        require(ID.matches(payload.messageId)) { "Group message ID has an invalid format" }
        require(ID.matches(payload.groupId)) { "Group ID has an invalid format" }
        require(payload.membershipEpoch > 0L) { "Group membership epoch must be positive" }
        require(ID.matches(payload.senderUserId)) { "Group message sender account is invalid" }
        require(DEVICE_ID.matches(payload.senderDeviceId)) { "Group message sender device is invalid" }
        require(ID.matches(payload.recipientUserId)) { "Group message recipient account is invalid" }
        require(DEVICE_ID.matches(payload.recipientDeviceId)) { "Group message recipient device is invalid" }
        require(
            payload.senderUserId != payload.recipientUserId ||
                payload.senderDeviceId != payload.recipientDeviceId
        ) { "A device cannot send an encrypted group message to itself" }
        require(SESSION_ID.matches(payload.megolmSessionId)) { "Megolm session ID is invalid" }
        require(payload.messageIndex in 0L..0xffff_ffffL) { "Megolm message index is invalid" }
        require(payload.sentAtMillis >= 0L) { "Group message timestamp cannot be negative" }

        val decoded = try {
            Base64.getUrlDecoder().decode(payload.ciphertext)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Megolm ciphertext is not valid Base64URL", error)
        }
        require(decoded.isNotEmpty() && decoded.size <= MAX_CIPHERTEXT_BYTES) {
            "Megolm ciphertext has an invalid size"
        }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == payload.ciphertext) {
            "Megolm ciphertext must use canonical unpadded Base64URL"
        }
    }
}
