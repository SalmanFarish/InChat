package com.example.inchat.crypto

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeyFingerprintTest {
    private fun encodedKey(seed: Int): String =
        Base64.encodeToString(
            ByteArray(32) { (seed + it).toByte() },
            Base64.NO_WRAP or Base64.NO_PADDING,
        )

    @Test
    fun fingerprintIsStableAndHumanComparable() {
        val key = encodedKey(1)
        val first = DeviceKeyFingerprint.create("device-one", key)
        val repeated = DeviceKeyFingerprint.create("device-one", key)

        assertEquals(first, repeated)
        assertEquals(64, first.replace(" ", "").length)
        assertTrue(first.split(" ").all { it.length == 4 })
    }

    @Test
    fun fingerprintChangesWhenDeviceOrIdentityKeyChanges() {
        val key = encodedKey(1)
        val original = DeviceKeyFingerprint.create("device-one", key)

        assertNotEquals(original, DeviceKeyFingerprint.create("device-two", key))
        assertNotEquals(original, DeviceKeyFingerprint.create("device-one", encodedKey(2)))
    }

    @Test
    fun fingerprintRejectsMalformedIdentityMaterial() {
        listOf(
            "device/one" to encodedKey(1),
            "device-one" to "not-base64!",
            "device-one" to Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP),
            "device-one" to Base64.encodeToString(ByteArray(32), Base64.NO_WRAP),
        ).forEach { (deviceId, key) ->
            var rejected = false
            try {
                DeviceKeyFingerprint.create(deviceId, key)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Malformed device identity material must be rejected", rejected)
        }
    }
}
