package com.example.inchat.crypto

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Public locator for one published InChat device. It contains no secret key material. */
internal data class DeviceVerificationQrPayload(
    val userId: String,
    val deviceId: String,
    val identityKeyFingerprint: String,
)

/**
 * Encodes an exact published device identity for QR-assisted SAS setup.
 *
 * Scanning this code only locates and binds the peer's published public key.
 * It never marks that key trusted; the existing two-sided SAS comparison
 * remains the only operation that establishes independently verified trust.
 */
internal object DeviceVerificationQrCodec {
    private const val SCHEME = "inchat"
    private const val HOST = "verify-device"
    private const val PATH = "/v1"
    private val USER_ID = Regex("[A-Za-z0-9_-]{1,128}")
    private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val FINGERPRINT = Regex("[a-f0-9]{64}")

    fun encode(userId: String, deviceId: String, ed25519IdentityKey: String): String {
        require(USER_ID.matches(userId)) { "QR account ID has an invalid format" }
        require(DEVICE_ID.matches(deviceId)) { "QR device ID has an invalid format" }
        require(ed25519IdentityKey.isNotBlank()) { "QR device identity key is missing" }
        val fingerprint = fingerprint(ed25519IdentityKey)
        return "$SCHEME://$HOST$PATH" +
            "?user=" + encodeQueryValue(userId) +
            "&device=" + encodeQueryValue(deviceId) +
            "&fingerprint=" + fingerprint
    }

    fun decode(contents: String): DeviceVerificationQrPayload {
        require(contents.length in 1..2048) { "This QR code is not a valid InChat device code" }
        val uri = runCatching { URI(contents) }
            .getOrElse { throw IllegalArgumentException("This QR code is malformed", it) }
        require(
            uri.scheme == SCHEME &&
                uri.host == HOST &&
                uri.path == PATH &&
                uri.port == -1 &&
                uri.rawUserInfo == null &&
                uri.fragment == null
        ) { "This QR code is not an InChat device verification code" }

        val parameters = parseQuery(uri.rawQuery.orEmpty())
        require(parameters.keys == setOf("user", "device", "fingerprint")) {
            "This InChat device code is missing required fields"
        }
        val userId = parameters.getValue("user")
        val deviceId = parameters.getValue("device")
        val fingerprint = parameters.getValue("fingerprint")
        require(USER_ID.matches(userId)) { "QR account ID has an invalid format" }
        require(DEVICE_ID.matches(deviceId)) { "QR device ID has an invalid format" }
        require(FINGERPRINT.matches(fingerprint)) { "QR identity fingerprint has an invalid format" }

        return DeviceVerificationQrPayload(
            userId = userId,
            deviceId = deviceId,
            identityKeyFingerprint = fingerprint,
        )
    }

    fun fingerprint(ed25519IdentityKey: String): String {
        require(ed25519IdentityKey.isNotBlank()) { "Device identity key is missing" }
        return MessageDigest.getInstance("SHA-256")
            .digest(ed25519IdentityKey.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        require(rawQuery.isNotBlank()) { "This InChat device code has no identity fields" }
        val values = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            require(separator > 0 && separator < pair.lastIndex) {
                "This InChat device code contains a malformed field"
            }
            val key = decodeQueryValue(pair.substring(0, separator))
            val value = decodeQueryValue(pair.substring(separator + 1))
            require(values.put(key, value) == null) {
                "This InChat device code contains a duplicate field"
            }
        }
        return values
    }

    private fun encodeQueryValue(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun decodeQueryValue(value: String): String =
        try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("This InChat device code contains invalid encoding", failure)
        }
}
