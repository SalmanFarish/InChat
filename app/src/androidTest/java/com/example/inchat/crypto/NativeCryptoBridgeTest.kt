package com.example.inchat.crypto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeCryptoBridgeTest {

    @Test
    fun nativeLibraryLoadsAndReportsPinnedVersion() {
        assertEquals(
            "0.11.2",
            NativeCryptoBridge.nativeVodozemacVersion()
        )
    }

    @Test
    fun nativeOlmSelfTestExchangesEncryptedMessagesBetweenTwoDevices() {
        assertTrue(
            "The native two-device Olm self-test failed",
            NativeCryptoBridge.nativeOlmSelfTest()
        )
    }

    @Test
    fun deviceIdentityPersistsAndReplenishesAfterPublication() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DeviceIdentityRepository(context)

        val first = repository.ensurePublicKeyBundle()
        val repeated = repository.ensurePublicKeyBundle()

        assertEquals(first.deviceId, repeated.deviceId)
        assertEquals(first.ed25519IdentityKey, repeated.ed25519IdentityKey)
        assertEquals(first.curve25519IdentityKey, repeated.curve25519IdentityKey)
        assertEquals(20, first.oneTimeKeys.size)
        assertEquals(first.oneTimeKeys.map { it.keyId }.toSet(), repeated.oneTimeKeys.map { it.keyId }.toSet())
        assertNotEquals(null, first.fallbackKey)

        repository.markPublicKeyBundlePublished()

        val replenished = repository.ensurePublicKeyBundle()
        assertEquals(first.deviceId, replenished.deviceId)
        assertEquals(first.ed25519IdentityKey, replenished.ed25519IdentityKey)
        assertEquals(first.curve25519IdentityKey, replenished.curve25519IdentityKey)
        assertEquals(20, replenished.oneTimeKeys.size)
        assertTrue(
            "Replenished one-time prekeys must have new IDs",
            first.oneTimeKeys.map { it.keyId }.toSet()
                .intersect(replenished.oneTimeKeys.map { it.keyId }.toSet()).isEmpty()
        )
        assertNotEquals(
            first.fallbackKey?.keyId,
            replenished.fallbackKey?.keyId
        )
    }
}
