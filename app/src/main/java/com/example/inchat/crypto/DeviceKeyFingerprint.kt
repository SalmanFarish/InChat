package com.example.inchat.crypto

import android.util.Base64
import java.security.MessageDigest

/**
 * Human-comparable fingerprint for a device's Ed25519 identity key.
 *
 * This is a verification aid, not proof by itself: users must compare the
 * complete fingerprint through an independent trusted channel before marking
 * a device as verified. The Firebase directory is not a trust anchor.
 */
internal object DeviceKeyFingerprint {
    private const val DOMAIN = "INCHAT_DEVICE_FINGERPRINT_V1\n"
    private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

    /**
     * Returns the full SHA-256 fingerprint, grouped for visual comparison.
     * The digest binds both the installation device ID and Ed25519 identity key.
     */
    fun create(deviceId: String, ed25519IdentityKey: String): String {
        require(DEVICE_ID_PATTERN.matches(deviceId)) {
            "Device ID has an invalid format"
        }

        val identityKey = try {
            Base64.decode(ed25519IdentityKey, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Ed25519 identity key is not valid Base64")
        }
        require(identityKey.size == 32) {
            "Ed25519 identity key must decode to exactly 32 bytes"
        }
        val canonicalKey = Base64.encodeToString(
            identityKey,
            Base64.NO_WRAP or Base64.NO_PADDING,
        )
        require(canonicalKey == ed25519IdentityKey) {
            "Ed25519 identity key must use canonical unpadded Base64"
        }

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(deviceId.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(identityKey)
        val hex = digest.digest().joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString(" ")
    }
}
