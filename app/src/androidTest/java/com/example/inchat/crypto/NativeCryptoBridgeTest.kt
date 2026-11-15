package com.example.inchat.crypto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.util.UUID

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
    fun nativeOlmSessionsPersistAndRatchetAcrossStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val aliceDeviceId = "olm-alice-$suffix"
        val bobDeviceId = "olm-bob-$suffix"
        val aliceStoreId = "device-olm-a-$suffix"
        val bobStoreId = "device-olm-b-$suffix"
        val aliceStore = CryptoStateStore(context, aliceStoreId)
        val bobStore = CryptoStateStore(context, bobStoreId)

        val aliceBundle = JSONObject(
            NativeCryptoBridge.nativeEnsureDeviceIdentity(aliceStore, aliceDeviceId)
                ?: error("Alice identity should be created")
        )
        val bobBundle = JSONObject(
            NativeCryptoBridge.nativeEnsureDeviceIdentity(bobStore, bobDeviceId)
                ?: error("Bob identity should be created")
        )
        val bobIdentity = bobBundle.getJSONObject("identityKeys").getString("curve25519")
        val bobOneTimeKey = bobBundle.getJSONArray("oneTimeKeys")
            .getJSONObject(0).getString("key")
        val aliceIdentity = aliceBundle.getJSONObject("identityKeys").getString("curve25519")

        assertTrue(NativeCryptoBridge.nativeMarkDeviceKeysPublished(bobStore, bobDeviceId))

        val aliceSessionId = NativeCryptoBridge.nativeCreateOutboundOlmSession(
            aliceStore, aliceDeviceId, bobIdentity, bobOneTimeKey
        ) ?: error("Alice outbound session should be saved")
        val firstWireMessage = NativeCryptoBridge.nativeEncryptOlmMessage(
            aliceStore, aliceDeviceId, aliceSessionId, "hello from Alice"
        ) ?: error("Alice should encrypt the initial pre-key message")

        val inboundJson = JSONObject(
            NativeCryptoBridge.nativeCreateInboundOlmSession(
                bobStore, bobDeviceId, aliceIdentity, firstWireMessage
            ) ?: error("Bob should establish and persist the inbound session")
        )
        val bobSessionId = inboundJson.getString("sessionId")
        assertEquals("hello from Alice", inboundJson.getString("plaintext"))

        // Recreate both store wrappers to verify that the encrypted unified state,
        // including the consumed prekey and both ratchets, survives process-style reopen.
        val aliceRestoredStore = CryptoStateStore(context, aliceStoreId)
        val bobRestoredStore = CryptoStateStore(context, bobStoreId)

        val reply = NativeCryptoBridge.nativeEncryptOlmMessage(
            bobRestoredStore, bobDeviceId, bobSessionId, "hello from Bob"
        ) ?: error("Bob should encrypt after restoring his inbound session")
        assertEquals(
            "hello from Bob",
            NativeCryptoBridge.nativeDecryptOlmMessage(
                aliceRestoredStore, aliceDeviceId, aliceSessionId, reply
            )
        )

        val followUp = NativeCryptoBridge.nativeEncryptOlmMessage(
            aliceRestoredStore, aliceDeviceId, aliceSessionId, "ratchet survived"
        ) ?: error("Alice should continue the restored session")
        assertEquals(
            "ratchet survived",
            NativeCryptoBridge.nativeDecryptOlmMessage(
                bobRestoredStore, bobDeviceId, bobSessionId, followUp
            )
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
        assertEquals(
            first.oneTimeKeys.map { it.keyId }.toSet(),
            repeated.oneTimeKeys.map { it.keyId }.toSet()
        )
        assertTrue(first.fallbackKey != null)

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
        assertTrue(
            "The fallback key should rotate after publication",
            first.fallbackKey?.keyId != replenished.fallbackKey?.keyId
        )
    }
}
