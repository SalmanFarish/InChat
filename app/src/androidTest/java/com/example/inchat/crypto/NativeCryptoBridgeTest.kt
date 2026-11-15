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
    fun encryptedOlmEnvelopeRoundTripsAndRejectsUnsupportedVersions() {
        val original = EncryptedOlmEnvelope(
            senderDeviceId = "sender-device",
            recipientDeviceId = "recipient-device",
            sessionId = "olm-session-123",
            ciphertext = byteArrayOf(1, 2, 3, 4, 5, 6),
        )

        val encoded = EncryptedOlmEnvelopeCodec.encode(original)
        val decoded = EncryptedOlmEnvelopeCodec.decode(encoded)

        assertEquals(original.senderDeviceId, decoded.senderDeviceId)
        assertEquals(original.recipientDeviceId, decoded.recipientDeviceId)
        assertEquals(original.sessionId, decoded.sessionId)
        assertTrue(original.ciphertext.contentEquals(decoded.ciphertext))

        val unsupportedVersion = encoded.replace(
            "\"encryptionVersion\":1",
            "\"encryptionVersion\":2",
        )
        var rejected = false
        try {
            EncryptedOlmEnvelopeCodec.decode(unsupportedVersion)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Unknown envelope versions must be rejected", rejected)
    }

    @Test
    fun encryptedOlmEnvelopeRejectsInvalidRoutingAndCiphertext() {
        var invalidDeviceRejected = false
        try {
            EncryptedOlmEnvelopeCodec.encode(
                EncryptedOlmEnvelope(
                    senderDeviceId = "sender",
                    recipientDeviceId = "bad/device",
                    sessionId = "session",
                    ciphertext = byteArrayOf(1, 2),
                )
            )
        } catch (_: IllegalArgumentException) {
            invalidDeviceRejected = true
        }
        assertTrue("Invalid device IDs must be rejected", invalidDeviceRejected)

        var shortCiphertextRejected = false
        try {
            EncryptedOlmEnvelopeCodec.encode(
                EncryptedOlmEnvelope(
                    senderDeviceId = "sender",
                    recipientDeviceId = "recipient",
                    sessionId = "session",
                    ciphertext = byteArrayOf(1),
                )
            )
        } catch (_: IllegalArgumentException) {
            shortCiphertextRejected = true
        }
        assertTrue("Truncated ciphertext must be rejected", shortCiphertextRejected)
    }

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
    fun nativePendingDecryptionRecoversExactlyAndFinalizesAfterCommit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val aliceDeviceId = "recover-alice-$suffix"
        val bobDeviceId = "recover-bob-$suffix"
        val aliceStoreId = "device-recover-a-$suffix"
        val bobStoreId = "device-recover-b-$suffix"
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
        assertTrue(NativeCryptoBridge.nativeMarkDeviceKeysPublished(bobStore, bobDeviceId))

        fun receiptId(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val aliceSessionId = requireNotNull(
            NativeCryptoBridge.nativeCreateOutboundOlmSession(
                aliceStore,
                aliceDeviceId,
                bobBundle.getJSONObject("identityKeys").getString("curve25519"),
                bobBundle.getJSONArray("oneTimeKeys").getJSONObject(0).getString("key"),
            )
        )
        val firstWire = requireNotNull(
            NativeCryptoBridge.nativeEncryptOlmMessage(
                aliceStore, aliceDeviceId, aliceSessionId, "initial crash-safe message"
            )
        )
        val firstWireRetry = firstWire.clone()
        val firstReceipt = receiptId("initial-$suffix")
        val firstResult = JSONObject(
            NativeCryptoBridge.nativePrepareInboundOlmSession(
                bobStore,
                bobDeviceId,
                firstReceipt,
                aliceBundle.getJSONObject("identityKeys").getString("curve25519"),
                firstWire,
            ) ?: error("Initial message should be staged")
        )
        assertEquals("initial crash-safe message", firstResult.getString("plaintext"))
        assertEquals(firstReceipt, firstResult.getString("receiptId"))

        val recoveredFirst = JSONObject(
            NativeCryptoBridge.nativePrepareInboundOlmSession(
                CryptoStateStore(context, bobStoreId),
                bobDeviceId,
                firstReceipt,
                aliceBundle.getJSONObject("identityKeys").getString("curve25519"),
                firstWireRetry,
            ) ?: error("Retry should recover staged plaintext without consuming the OTK again")
        )
        assertEquals(firstResult.getString("sessionId"), recoveredFirst.getString("sessionId"))
        assertEquals(firstResult.getString("plaintext"), recoveredFirst.getString("plaintext"))

        val conflictingWire = firstWireRetry.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(
            null,
            NativeCryptoBridge.nativePrepareInboundOlmSession(
                CryptoStateStore(context, bobStoreId),
                bobDeviceId,
                firstReceipt,
                aliceBundle.getJSONObject("identityKeys").getString("curve25519"),
                conflictingWire,
            ),
        )

        assertTrue(
            NativeCryptoBridge.nativeFinalizeInboundDecryption(
                CryptoStateStore(context, bobStoreId), bobDeviceId, firstReceipt
            )
        )
        assertTrue(
            "Finalize must be safe to repeat after a crash",
            NativeCryptoBridge.nativeFinalizeInboundDecryption(
                CryptoStateStore(context, bobStoreId), bobDeviceId, firstReceipt
            )
        )

        val replyWire = requireNotNull(
            NativeCryptoBridge.nativeEncryptOlmMessage(
                CryptoStateStore(context, bobStoreId),
                bobDeviceId,
                firstResult.getString("sessionId"),
                "normal-message crash recovery",
            )
        )
        val replyRetry = replyWire.clone()
        val normalReceipt = receiptId("normal-$suffix")
        val normalResult = JSONObject(
            NativeCryptoBridge.nativePrepareOlmMessage(
                CryptoStateStore(context, aliceStoreId),
                aliceDeviceId,
                normalReceipt,
                aliceSessionId,
                replyWire,
            ) ?: error("Normal message should be staged")
        )
        assertEquals("normal-message crash recovery", normalResult.getString("plaintext"))

        val recoveredNormal = JSONObject(
            NativeCryptoBridge.nativePrepareOlmMessage(
                CryptoStateStore(context, aliceStoreId),
                aliceDeviceId,
                normalReceipt,
                aliceSessionId,
                replyRetry,
            ) ?: error("Normal message retry should recover exact staged plaintext")
        )
        assertEquals(normalResult.getString("plaintext"), recoveredNormal.getString("plaintext"))
        assertTrue(
            NativeCryptoBridge.nativeFinalizeInboundDecryption(
                CryptoStateStore(context, aliceStoreId), aliceDeviceId, normalReceipt
            )
        )
    }

    @Test
    fun olmSessionRepositoryUsesVerifiedDeviceBundleAndPersistsRatchets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val remoteDeviceId = "olm-remote-$suffix"
        val remoteStore = CryptoStateStore(context, "device-remote-$suffix")
        val remoteJson = JSONObject(
            NativeCryptoBridge.nativeEnsureDeviceIdentity(remoteStore, remoteDeviceId)
                ?: error("Remote identity should be created")
        )
        assertTrue(NativeCryptoBridge.nativeMarkDeviceKeysPublished(remoteStore, remoteDeviceId))

        val identity = remoteJson.getJSONObject("identityKeys")
        val fallbackJson = remoteJson.getJSONObject("fallbackKey")
        val oneTimeJson = remoteJson.getJSONArray("oneTimeKeys")
        val remoteBundle = PublicDeviceKeyBundle(
            deviceId = remoteDeviceId,
            ed25519IdentityKey = identity.getString("ed25519"),
            curve25519IdentityKey = identity.getString("curve25519"),
            curve25519IdentityKeySignature = identity.getString("curve25519Signature"),
            fallbackKey = SignedOlmPreKey(
                keyKind = fallbackJson.getString("keyKind"),
                keyId = fallbackJson.getString("keyId"),
                publicKey = fallbackJson.getString("key"),
                signature = fallbackJson.getString("signature"),
            ),
            oneTimeKeys = (0 until oneTimeJson.length()).map { index ->
                val item = oneTimeJson.getJSONObject(index)
                SignedOlmPreKey(
                    keyKind = item.getString("keyKind"),
                    keyId = item.getString("keyId"),
                    publicKey = item.getString("key"),
                    signature = item.getString("signature"),
                )
            },
        )

        val repository = OlmSessionRepository(context)
        val sessionId = repository.createOutboundSession(
            remoteBundle,
            requireNotNull(remoteBundle.oneTimeKeys.firstOrNull()),
        )
        val localBundle = DeviceIdentityRepository(context).ensurePublicKeyBundle()
        val firstMessage = repository.encryptEnvelope(
            sessionId,
            remoteDeviceId,
            "repository handshake",
        )
        val outboundEnvelope = EncryptedOlmEnvelopeCodec.decode(firstMessage)
        assertEquals(localBundle.deviceId, outboundEnvelope.senderDeviceId)
        assertEquals(remoteDeviceId, outboundEnvelope.recipientDeviceId)
        val inbound = JSONObject(
            NativeCryptoBridge.nativeCreateInboundOlmSession(
                remoteStore,
                remoteDeviceId,
                localBundle.curve25519IdentityKey,
                outboundEnvelope.ciphertext,
            ) ?: error("Remote device should accept the repository pre-key message")
        )
        assertEquals("repository handshake", inbound.getString("plaintext"))
        assertEquals(sessionId, inbound.getString("sessionId"))

        val replyWire = NativeCryptoBridge.nativeEncryptOlmMessage(
            remoteStore,
            remoteDeviceId,
            inbound.getString("sessionId"),
            "repository reply",
        ) ?: error("Remote device should encrypt its reply")
        val replyEnvelope = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = remoteDeviceId,
                recipientDeviceId = localBundle.deviceId,
                sessionId = inbound.getString("sessionId"),
                ciphertext = replyWire,
            )
        )
        assertEquals(
            "repository reply",
            repository.decryptEnvelope(remoteDeviceId, replyEnvelope),
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
