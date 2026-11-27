package com.example.inchat.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OlmSessionRepositoryTest {
    @Test
    fun onlyReusesAConfirmedSessionBoundToTheRemoteDeviceAndPersistsItAcrossRepositoryRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localDeviceId = "olm-reuse-local-device"
        val remoteDeviceId = "olm-reuse-remote-device"
        val localStore = CryptoStateStore(context, "device-$localDeviceId")
        val remoteStore = CryptoStateStore(context, "device-$remoteDeviceId")
        localStore.delete()
        remoteStore.delete()

        try {
            val localEncodedBundle = localStore.withExclusiveAccess {
                NativeCryptoBridge.nativeEnsureDeviceIdentity(localStore, localDeviceId)
            } ?: error("Local native identity could not be initialized")
            val remoteEncodedBundle = remoteStore.withExclusiveAccess {
                NativeCryptoBridge.nativeEnsureDeviceIdentity(remoteStore, remoteDeviceId)
            } ?: error("Remote native identity could not be initialized")
            val localBundle = parseBundle(localEncodedBundle, localDeviceId)
            val remoteBundle = parseBundle(remoteEncodedBundle, remoteDeviceId)

            assertEquals(
                true,
                remoteStore.withExclusiveAccess {
                    NativeCryptoBridge.nativeMarkDeviceKeysPublished(remoteStore, remoteDeviceId)
                },
            )

            val localIdentity = object : SasVerificationLocalIdentity {
                override fun ensurePublicKeyBundle(): PublicDeviceKeyBundle = localBundle
                override fun requireIdentityUsable() = Unit
            }
            val senderRepository = OlmSessionRepository(context, localIdentity)
            val remotePreKey = requireNotNull(remoteBundle.oneTimeKeys.firstOrNull()) {
                "Remote device must publish an initial one-time key"
            }

            val sessionId = senderRepository.createOutboundSession(remoteBundle, remotePreKey)
            assertNull(
                "An initial outbound session is not reusable until a peer reply is decrypted",
                senderRepository.findEstablishedOutboundSession(remoteBundle),
            )

            val firstWireMessage = senderRepository.encrypt(sessionId, "initial hello")
            val inboundJson = remoteStore.withExclusiveAccess {
                NativeCryptoBridge.nativeCreateInboundOlmSession(
                    remoteStore,
                    remoteDeviceId,
                    localBundle.curve25519IdentityKey,
                    firstWireMessage,
                )
            } ?: error("Remote device could not establish the inbound Olm session")
            val inbound = JSONObject(inboundJson)
            assertEquals("initial hello", inbound.getString("plaintext"))
            val inboundSessionId = inbound.getString("sessionId")

            val replyWireMessage = remoteStore.withExclusiveAccess {
                NativeCryptoBridge.nativeEncryptOlmMessage(
                    remoteStore,
                    remoteDeviceId,
                    inboundSessionId,
                    "peer reply",
                )
            } ?: error("Remote device could not encrypt its reply")
            assertEquals("peer reply", senderRepository.decrypt(sessionId, replyWireMessage))

            assertEquals(
                "The confirmed session must be discoverable using the remote device identity",
                sessionId,
                senderRepository.findEstablishedOutboundSession(remoteBundle),
            )

            // A new repository instance simulates process recreation. The native
            // session and its verified peer-identity binding must both survive.
            val restartedRepository = OlmSessionRepository(context, localIdentity)
            assertEquals(
                sessionId,
                restartedRepository.findEstablishedOutboundSession(remoteBundle),
            )
        } finally {
            localStore.delete()
            remoteStore.delete()
        }
    }

    private fun parseBundle(encoded: String, expectedDeviceId: String): PublicDeviceKeyBundle {
        val root = JSONObject(encoded)
        require(root.getInt("schemaVersion") == 1)
        val deviceId = root.getString("deviceId")
        require(deviceId == expectedDeviceId)
        val identityKeys = root.getJSONObject("identityKeys")
        val fallback = root.optJSONObject("fallbackKey")?.let(::parsePreKey)
        val oneTimeArray: JSONArray = root.getJSONArray("oneTimeKeys")
        val oneTimeKeys = buildList {
            for (index in 0 until oneTimeArray.length()) {
                add(parsePreKey(oneTimeArray.getJSONObject(index)))
            }
        }
        return PublicDeviceKeyBundle(
            deviceId = deviceId,
            ed25519IdentityKey = identityKeys.getString("ed25519"),
            curve25519IdentityKey = identityKeys.getString("curve25519"),
            curve25519IdentityKeySignature = identityKeys.getString("curve25519Signature"),
            fallbackKey = fallback,
            oneTimeKeys = oneTimeKeys,
        )
    }

    private fun parsePreKey(value: JSONObject) = SignedOlmPreKey(
        keyKind = value.getString("keyKind"),
        keyId = value.getString("keyId"),
        publicKey = value.getString("key"),
        signature = value.getString("signature"),
    )
}
