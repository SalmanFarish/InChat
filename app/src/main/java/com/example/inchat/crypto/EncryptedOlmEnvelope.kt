package com.example.inchat.crypto

import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Versioned transport wrapper for Olm ciphertext.
 *
 * Device IDs and session IDs are routing metadata, not authenticated claims.
 * The Olm ciphertext remains responsible for cryptographic authentication.
 */
internal data class EncryptedOlmEnvelope(
    val senderDeviceId: String,
    val recipientDeviceId: String,
    val sessionId: String,
    val ciphertext: ByteArray,
)

internal object EncryptedOlmEnvelopeCodec {
    private const val ENVELOPE_VERSION = 1
    private const val ALGORITHM = "olm"
    private const val MAX_CIPHERTEXT_BYTES = 1024 * 1024
    private const val MAX_ENCODED_BYTES = 2 * 1024 * 1024
    private val FIELDS = setOf(
        "encryptionVersion", "algorithm", "senderDeviceId",
        "recipientDeviceId", "sessionId", "ciphertext",
    )
    private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    fun encode(envelope: EncryptedOlmEnvelope): String {
        validate(envelope)
        val json = JSONObject()
            .put("encryptionVersion", ENVELOPE_VERSION)
            .put("algorithm", ALGORITHM)
            .put("senderDeviceId", envelope.senderDeviceId)
            .put("recipientDeviceId", envelope.recipientDeviceId)
            .put("sessionId", envelope.sessionId)
            .put(
                "ciphertext",
                Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.ciphertext),
            )
            .toString()
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Encrypted Olm envelope exceeds the size limit"
        }
        return json
    }

    fun decode(encoded: String): EncryptedOlmEnvelope {
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Encrypted Olm envelope exceeds the size limit"
        }
        val json = try {
            JSONObject(encoded)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Encrypted Olm envelope is not valid JSON", error)
        }
        val fields = json.keys().asSequence().toSet()
        require(fields == FIELDS) {
            "Encrypted Olm envelope has missing or unknown fields"
        }
        require(json.opt("encryptionVersion") == ENVELOPE_VERSION) {
            "Unsupported encrypted-message version"
        }
        require(json.opt("algorithm") == ALGORITHM) {
            "Unsupported encrypted-message algorithm"
        }
        val ciphertext = try {
            Base64.getUrlDecoder().decode(json.getString("ciphertext"))
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Encrypted Olm ciphertext is not valid Base64", error)
        }
        val envelope = EncryptedOlmEnvelope(
            senderDeviceId = requireString(json, "senderDeviceId"),
            recipientDeviceId = requireString(json, "recipientDeviceId"),
            sessionId = requireString(json, "sessionId"),
            ciphertext = ciphertext,
        ).also(::validate)
        require(encode(envelope) == encoded) {
            "Encrypted Olm envelope is not in canonical form"
        }
        return envelope
    }

    private fun requireString(json: JSONObject, name: String): String {
        val value = json.opt(name)
        require(value is String) { "Encrypted Olm envelope field '$name' must be text" }
        return value
    }

    private fun validate(envelope: EncryptedOlmEnvelope) {
        require(DEVICE_ID_PATTERN.matches(envelope.senderDeviceId)) {
            "Sender device ID has an invalid format"
        }
        require(DEVICE_ID_PATTERN.matches(envelope.recipientDeviceId)) {
            "Recipient device ID has an invalid format"
        }
        require(envelope.senderDeviceId != envelope.recipientDeviceId) {
            "An encrypted Olm envelope cannot target the sending device"
        }
        require(envelope.sessionId.isNotBlank() && envelope.sessionId.length <= 128) {
            "Olm session ID has an invalid format"
        }
        require(envelope.ciphertext.size in 2..MAX_CIPHERTEXT_BYTES) {
            "Encrypted Olm ciphertext has an invalid size"
        }
    }
}
