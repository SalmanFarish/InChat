package com.example.inchat.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceVerificationQrCodecTest {
    @Test
    fun roundTripsExactAccountDeviceAndIdentityFingerprint() {
        val key = "published-ed25519-identity-key"
        val encoded = DeviceVerificationQrCodec.encode(
            userId = "account_123-abc",
            deviceId = "phone_01-abc",
            ed25519IdentityKey = key,
        )

        val decoded = DeviceVerificationQrCodec.decode(encoded)

        assertEquals("account_123-abc", decoded.userId)
        assertEquals("phone_01-abc", decoded.deviceId)
        assertEquals(DeviceVerificationQrCodec.fingerprint(key), decoded.identityKeyFingerprint)
    }

    @Test
    fun fingerprintChangesWhenThePublishedIdentityKeyChanges() {
        assertNotEquals(
            DeviceVerificationQrCodec.fingerprint("first identity"),
            DeviceVerificationQrCodec.fingerprint("changed identity"),
        )
    }

    @Test
    fun rejectsNonInChatUrisAndWrongProtocolVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceVerificationQrCodec.decode("https://example.com/verify?v=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceVerificationQrCodec.decode("inchat://verify-device/v2?user=alice&device=phone&fingerprint=" + "a".repeat(64))
        }
    }

    @Test
    fun rejectsDuplicateOrMissingQueryFields() {
        val fingerprint = "a".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceVerificationQrCodec.decode(
                "inchat://verify-device/v1?user=alice&user=bob&device=phone&fingerprint=$fingerprint",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceVerificationQrCodec.decode(
                "inchat://verify-device/v1?user=alice&device=phone",
            )
        }
    }

    @Test
    fun rejectsMalformedIdentifiersAndFingerprint() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceVerificationQrCodec.decode(
                "inchat://verify-device/v1?user=../alice&device=phone&fingerprint=" + "a".repeat(64),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceVerificationQrCodec.decode(
                "inchat://verify-device/v1?user=alice&device=phone&fingerprint=not-a-fingerprint",
            )
        }
    }

    @Test
    fun rejectsOversizedOrEmptyQrContents() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceVerificationQrCodec.decode("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceVerificationQrCodec.decode("x".repeat(2049))
        }
        assertTrue(
            DeviceVerificationQrCodec.encode("alice", "phone", "identity").startsWith("inchat://verify-device/v1"),
        )
    }
}
