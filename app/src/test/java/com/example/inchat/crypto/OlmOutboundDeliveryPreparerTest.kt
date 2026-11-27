package com.example.inchat.crypto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OlmOutboundDeliveryPreparerTest {
    @Test
    fun establishedSessionIsReusedWithoutClaimingAnotherOneTimePrekey() = runBlocking {
        val recipient = bundle("bob-phone")
        val directory = FakeDirectory()
        val sessions = FakeSessions("alice-phone", "confirmed-olm-session")
        val preparer = OlmOutboundDeliveryPreparer(
            directory = directory,
            sessions = sessions,
            trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
        )

        val prepared = preparer.prepareFirstMessage(payload(recipient.deviceId), recipient)

        assertEquals("confirmed-olm-session", prepared.sessionId)
        assertEquals(0, directory.claims)
        assertEquals(0, sessions.createdSessions)
        assertEquals(1, sessions.encryptedMessages)
    }

    @Test
    fun unestablishedSessionCreatesFreshPrekeyHandshakeForEachMessage() = runBlocking {
        val recipient = bundle("bob-phone")
        val directory = FakeDirectory()
        val sessions = FakeSessions("alice-phone", null)
        val preparer = OlmOutboundDeliveryPreparer(
            directory = directory,
            sessions = sessions,
            trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
        )

        val prepared = preparer.prepareFirstMessage(payload(recipient.deviceId), recipient)

        assertEquals("new-olm-session", prepared.sessionId)
        assertEquals(1, directory.claims)
        assertEquals(1, sessions.createdSessions)
        assertEquals(1, sessions.encryptedMessages)
    }

    private class FakeDirectory : OlmOutboundKeyDirectory {
        override val authenticatedUserId: String = "alice"
        var claims = 0

        override suspend fun getUserDevices(uid: String): List<PublicDeviceKeyBundle> = emptyList()

        override suspend fun claimOneTimePreKey(
            targetUid: String,
            deviceId: String,
            keyId: String,
            scope: OlmPreKeyClaimScope,
        ): Boolean {
            claims++
            assertEquals("bob", targetUid)
            assertEquals("bob-phone", deviceId)
            // The preparer supplies the direct-message scope to the durable claim.
            @Suppress("UNUSED_VARIABLE")
            val claimScope = scope
            return true
        }
    }

    private class FakeSessions(
        override val localDeviceId: String,
        private val establishedSessionId: String?,
    ) : OlmOutboundSessionRepository {
        var createdSessions = 0
        var encryptedMessages = 0

        override fun findEstablishedOutboundSession(remoteDevice: PublicDeviceKeyBundle): String? =
            establishedSessionId

        override fun createOutboundSession(
            remoteDevice: PublicDeviceKeyBundle,
            remotePreKey: SignedOlmPreKey,
        ): String {
            createdSessions++
            assertEquals("one_time", remotePreKey.keyKind)
            return "new-olm-session"
        }

        override fun encryptAuthenticatedEnvelope(
            sessionId: String,
            payload: OlmAuthenticatedMessagePayload,
        ): String {
            encryptedMessages++
            return "opaque-ciphertext:" + sessionId + ":" + payload.messageId
        }
    }

    private fun payload(recipientDeviceId: String) = OlmAuthenticatedMessagePayload(
        messageId = "message-1",
        conversationId = "alice_bob",
        conversationPeerUserId = "bob",
        senderUserId = "alice",
        senderDeviceId = "alice-phone",
        recipientUserId = "bob",
        recipientDeviceId = recipientDeviceId,
        sentAtMillis = 1_000L,
        text = "hello",
    )

    private fun bundle(deviceId: String) = PublicDeviceKeyBundle(
        deviceId = deviceId,
        ed25519IdentityKey = "ed25519-$deviceId",
        curve25519IdentityKey = "curve25519-$deviceId",
        curve25519IdentityKeySignature = "identity-signature-$deviceId",
        fallbackKey = SignedOlmPreKey(
            keyKind = "fallback",
            keyId = "fallback-$deviceId",
            publicKey = "fallback-public-$deviceId",
            signature = "fallback-signature-$deviceId",
        ),
        oneTimeKeys = listOf(
            SignedOlmPreKey(
                keyKind = "one_time",
                keyId = "one-time-$deviceId",
                publicKey = "one-time-public-$deviceId",
                signature = "one-time-signature-$deviceId",
            ),
        ),
    )
}
