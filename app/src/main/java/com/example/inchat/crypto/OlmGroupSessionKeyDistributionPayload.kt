package com.example.inchat.crypto

import java.util.Base64
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * A Megolm sender-session key addressed to exactly one device.
 *
 * The session key is a decryption capability. This object must only be encoded
 * inside an authenticated Olm message; it is never a Firebase directory value.
 */
internal data class OlmGroupSessionKeyDistributionPayload(
    val distributionId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val megolmSessionId: String,
    /** Canonical unpadded Base64URL encoding of vodozemac SessionKey::to_bytes(). */
    val sessionKey: String,
    val issuedAtMillis: Long,
)

internal object OlmGroupSessionKeyDistributionPayloadCodec {
    private const val SCHEMA_VERSION = 1
    private const val MESSAGE_TYPE = "group_session_key"
    private const val MAX_PAYLOAD_BYTES = 8 * 1024
    private const val MIN_SESSION_KEY_BYTES = 32
    private const val MAX_SESSION_KEY_BYTES = 1024

    private val ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val MEGOLM_SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
    private val FIELDS = setOf(
        "schemaVersion",
        "messageType",
        "distributionId",
        "groupId",
        "membershipEpoch",
        "senderUserId",
        "senderDeviceId",
        "recipientUserId",
        "recipientDeviceId",
        "megolmSessionId",
        "sessionKey",
        "issuedAtMillis",
    )

    /** Encode a single-device control payload for authenticated Olm transport. */
    fun encode(payload: OlmGroupSessionKeyDistributionPayload): String {
        validate(payload)
        val encoded = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("messageType", MESSAGE_TYPE)
            .put("distributionId", payload.distributionId)
            .put("groupId", payload.groupId)
            .put("membershipEpoch", payload.membershipEpoch)
            .put("senderUserId", payload.senderUserId)
            .put("senderDeviceId", payload.senderDeviceId)
            .put("recipientUserId", payload.recipientUserId)
            .put("recipientDeviceId", payload.recipientDeviceId)
            .put("megolmSessionId", payload.megolmSessionId)
            .put("sessionKey", payload.sessionKey)
            .put("issuedAtMillis", payload.issuedAtMillis)
            .toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Group session-key distribution payload exceeds the size limit"
        }
        return encoded
    }

    /**
     * Decode and bind every field to the already authenticated Olm receipt and
     * the caller's current trusted group/membership snapshot.
     */
    fun decodeAndValidate(
        plaintext: String,
        expectedDistributionId: String,
        expectedGroupId: String,
        expectedMembershipEpoch: Long,
        expectedSenderUserId: String,
        expectedSenderDeviceId: String,
        expectedRecipientUserId: String,
        expectedRecipientDeviceId: String,
    ): OlmGroupSessionKeyDistributionPayload {
        require(plaintext.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Group session-key distribution payload exceeds the size limit"
        }
        val json = try {
            JSONObject(plaintext)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Group session-key payload is not valid JSON", error)
        }
        require(json.keys().asSequence().toSet() == FIELDS) {
            "Group session-key payload has missing or unknown fields"
        }
        val version = json.opt("schemaVersion")
        require(version is Number && version.toDouble() == SCHEMA_VERSION.toDouble()) {
            "Unsupported group session-key payload version"
        }
        require(json.opt("messageType") == MESSAGE_TYPE) {
            "Unsupported encrypted control-message type"
        }

        fun stringField(name: String): String {
            val value = json.opt(name)
            require(value is String) { "Group session-key field '$name' must be text" }
            return value
        }

        fun longField(name: String): Long {
            val value = json.opt(name)
            require(value is Byte || value is Short || value is Int || value is Long) {
                "Group session-key field '$name' must be an integer"
            }
            return (value as Number).toLong()
        }

        val payload = OlmGroupSessionKeyDistributionPayload(
            distributionId = stringField("distributionId"),
            groupId = stringField("groupId"),
            membershipEpoch = longField("membershipEpoch"),
            senderUserId = stringField("senderUserId"),
            senderDeviceId = stringField("senderDeviceId"),
            recipientUserId = stringField("recipientUserId"),
            recipientDeviceId = stringField("recipientDeviceId"),
            megolmSessionId = stringField("megolmSessionId"),
            sessionKey = stringField("sessionKey"),
            issuedAtMillis = longField("issuedAtMillis"),
        )
        validate(payload)
        require(encode(payload) == plaintext) {
            "Group session-key payload is not in canonical form"
        }
        require(payload.distributionId == expectedDistributionId) {
            "Group session-key distribution ID does not match its Olm receipt"
        }
        require(payload.groupId == expectedGroupId) {
            "Group session-key group ID does not match the current group"
        }
        require(payload.membershipEpoch == expectedMembershipEpoch) {
            "Group session-key membership epoch is stale or unexpected"
        }
        require(payload.senderUserId == expectedSenderUserId) {
            "Group session-key sender account does not match its Olm receipt"
        }
        require(payload.senderDeviceId == expectedSenderDeviceId) {
            "Group session-key sender device does not match its Olm receipt"
        }
        require(payload.recipientUserId == expectedRecipientUserId) {
            "Group session-key recipient account does not match the local account"
        }
        require(payload.recipientDeviceId == expectedRecipientDeviceId) {
            "Group session-key recipient device does not match the local device"
        }
        return payload
    }

    /**
     * Confirm that both accounts belong to the same membership snapshot before
     * accepting or creating a key distribution. This does not replace checking
     * a separately authenticated, monotonically increasing membership epoch.
     */
    fun requireCurrentMembers(
        payload: OlmGroupSessionKeyDistributionPayload,
        currentMemberUserIds: Set<String>,
    ) {
        require(currentMemberUserIds.size in 2..50) {
            "Current group membership snapshot has an invalid size"
        }
        require(payload.senderUserId in currentMemberUserIds) {
            "Group session-key sender is not a current member"
        }
        require(payload.recipientUserId in currentMemberUserIds) {
            "Group session-key recipient is not a current member"
        }
    }

    private fun validate(payload: OlmGroupSessionKeyDistributionPayload) {
        require(ID.matches(payload.distributionId)) {
            "Group session-key distribution ID has an invalid format"
        }
        require(ID.matches(payload.groupId)) {
            "Group ID has an invalid format"
        }
        require(payload.membershipEpoch > 0L) {
            "Group membership epoch must be positive"
        }
        require(ID.matches(payload.senderUserId)) {
            "Group session-key sender account has an invalid format"
        }
        require(DEVICE_ID.matches(payload.senderDeviceId)) {
            "Group session-key sender device has an invalid format"
        }
        require(ID.matches(payload.recipientUserId)) {
            "Group session-key recipient account has an invalid format"
        }
        require(DEVICE_ID.matches(payload.recipientDeviceId)) {
            "Group session-key recipient device has an invalid format"
        }
        require(
            payload.senderUserId != payload.recipientUserId ||
                payload.senderDeviceId != payload.recipientDeviceId
        ) {
            "A device cannot distribute a group key to itself"
        }
        require(MEGOLM_SESSION_ID.matches(payload.megolmSessionId)) {
            "Megolm session ID has an invalid format"
        }
        require(payload.issuedAtMillis >= 0L) {
            "Group session-key timestamp cannot be negative"
        }

        val decodedKey = try {
            Base64.getUrlDecoder().decode(payload.sessionKey)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Megolm session key is not valid Base64URL", error)
        }
        require(decodedKey.size in MIN_SESSION_KEY_BYTES..MAX_SESSION_KEY_BYTES) {
            "Megolm session key has an invalid size"
        }
        val canonicalKey = Base64.getUrlEncoder().withoutPadding().encodeToString(decodedKey)
        require(canonicalKey == payload.sessionKey) {
            "Megolm session key must use canonical unpadded Base64URL"
        }
    }
}
