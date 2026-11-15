package com.example.inchat.crypto

import android.util.Base64
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets

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
                Base64.encodeToString(envelope.ciphertext, Base64.NO_WRAP or Base64.URL_SAFE),
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
        require(json.getInt("encryptionVersion") == ENVELOPE_VERSION) {
            "Unsupported encrypted-message version"
        }
        require(json.getString("algorithm") == ALGORITHM) {
            "Unsupported encrypted-message algorithm"
        }
        val ciphertext = try {
            Base64.decode(json.getString("ciphertext"), Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Encrypted Olm ciphertext is not valid Base64", error)
        }
        return EncryptedOlmEnvelope(
            senderDeviceId = json.getString("senderDeviceId"),
            recipientDeviceId = json.getString("recipientDeviceId"),
            sessionId = json.getString("sessionId"),
            ciphertext = ciphertext,
        ).also(::validate)
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
