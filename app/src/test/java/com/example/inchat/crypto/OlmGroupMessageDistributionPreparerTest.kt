package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupMessageDistributionPreparerTest {
    @Test
    fun fansOutSameMegolmCiphertextToEverySasTrustedDeviceWithExactRouting() = runBlocking {
        val sender = bundle("sender-current")
        val senderOther = bundle("sender-tablet")
        val recipient = bundle("recipient-phone")
        val directory = FakeDirectory(
            "alice",
            mapOf(
                "alice" to listOf(sender, senderOther),
                "bob" to listOf(recipient),
            ),
        )
        val sessions = FakeSessions(sender.deviceId)
        val trusted = setOf(
            "alice::sender-tablet::ed25519-sender-tablet",
            "bob::recipient-phone::ed25519-recipient-phone",
        )
        val preparer = OlmGroupMessageDistributionPreparer(
            directory,
            sessions,
            verifier(trusted),
        )
        val ciphertext = ByteArray(80) { it.toByte() }

        val result = preparer.prepare(
            membership = membership("group-1", 5L, "alice", "bob"),
            messageId = "message-1",
            encryptedMessage = EncryptedMegolmMessage("1234567890abcdef", 9L, ciphertext),
            sentAtMillis = 1234L,
        )

        assertEquals("message-1", result.messageId)
        assertEquals(5L, result.membershipEpoch)
        assertEquals(2, result.deliveries.size)
        assertEquals(0, result.skippedUntrustedDeviceCount)
        result.deliveries.forEach { delivery ->
            val payload = OlmGroupMessagePayloadCodec.decodeAndValidate(
                plaintext = delivery.envelope,
                expectedMessageId = "message-1",
                expectedGroupId = "group-1",
                expectedMembershipEpoch = 5L,
                expectedSenderUserId = "alice",
                expectedSenderDeviceId = "sender-current",
                expectedRecipientUserId = delivery.recipientUserId,
                expectedRecipientDeviceId = delivery.recipientDeviceId,
            )
            assertEquals(9L, payload.messageIndex)
            assertEquals("1234567890abcdef", payload.megolmSessionId)
            assertEquals(
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext),
                payload.ciphertext,
            )
        }
        assertEquals(2, sessions.createdSessions.size)
        assertEquals(setOf("alice", "bob"), directory.readUsers.toSet())
    }

    @Test
    fun readOnlyTrustedTargetPreviewMatchesFanoutWithoutClaimingPrekeysOrCreatingSessions() = runBlocking {
        val sender = bundle("sender-current")
        val senderOther = bundle("sender-tablet")
        val recipient = bundle("recipient-phone")
        val directory = FakeDirectory(
            "alice",
            mapOf(
                "alice" to listOf(sender, senderOther),
                "bob" to listOf(recipient),
            ),
        )
        val sessions = FakeSessions(sender.deviceId)
        val trusted = setOf(
            "alice::sender-tablet::ed25519-sender-tablet",
            "bob::recipient-phone::ed25519-recipient-phone",
        )
        val preparer = OlmGroupMessageDistributionPreparer(directory, sessions, verifier(trusted))

        val targets = preparer.currentTrustedTargetDevices(
            membership("group-1", 5L, "alice", "bob"),
        )

        assertEquals(
            setOf("alice" to "sender-tablet", "bob" to "recipient-phone"),
            targets,
        )
        assertTrue(directory.claimedPreKeys.isEmpty())
        assertTrue(sessions.createdSessions.isEmpty())
    }

    @Test
    fun refusesFanoutWhenAnyMemberHasNoTrustedDeviceBeforeClaimingPrekeys() = runBlocking {
        val sender = bundle("sender-current")
        val bob = bundle("bob-phone")
        val carol = bundle("carol-phone")
        val directory = FakeDirectory(
            "alice",
            mapOf("alice" to listOf(sender), "bob" to listOf(bob), "carol" to listOf(carol)),
        )
        val sessions = FakeSessions(sender.deviceId)
        val preparer = OlmGroupMessageDistributionPreparer(
            directory,
            sessions,
            verifier(setOf("bob::bob-phone::ed25519-bob-phone")),
        )

        assertRejected {
            runBlocking {
                preparer.prepare(
                    membership("group-1", 5L, "alice", "bob", "carol"),
                    "message-1",
                    EncryptedMegolmMessage("1234567890abcdef", 0L, ByteArray(80)),
                    1234L,
                )
            }
        }
        assertTrue(directory.claimedPreKeys.isEmpty())
        assertTrue(sessions.createdSessions.isEmpty())
    }

    @Test
    fun usesSignedFallbackWhenRecipientOneTimePrekeyCannotBeClaimed() = runBlocking {
        val sender = bundle("sender-current")
        val recipient = bundle("recipient-phone")
        val directory = FakeDirectory(
            "alice",
            mapOf("alice" to listOf(sender), "bob" to listOf(recipient)),
            claimResult = false,
        )
        val sessions = FakeSessions(sender.deviceId)
        val preparer = OlmGroupMessageDistributionPreparer(
            directory,
            sessions,
            verifier(setOf("bob::recipient-phone::ed25519-recipient-phone")),
        )

        val result = preparer.prepare(
            membership("group-1", 5L, "alice", "bob"),
            "message-1",
            EncryptedMegolmMessage("1234567890abcdef", 0L, ByteArray(80)),
            1234L,
        )
        assertEquals(1, result.deliveries.size)
        assertEquals("fallback-recipient-phone", sessions.createdSessions.single().second.keyId)
    }

    private class FakeDirectory(
        override val authenticatedUserId: String,
        private val devices: Map<String, List<PublicDeviceKeyBundle>>,
        private val claimResult: Boolean = true,
    ) : OlmOutboundKeyDirectory {
        val readUsers = mutableListOf<String>()
        val claimedPreKeys = mutableListOf<Pair<String, String>>()

        override suspend fun getUserDevices(uid: String): List<PublicDeviceKeyBundle> {
            readUsers += uid
            return devices[uid].orEmpty()
        }

        override suspend fun claimOneTimePreKey(
            targetUid: String,
            deviceId: String,
            keyId: String,
        ): Boolean {
            claimedPreKeys += targetUid to deviceId
            return claimResult
        }
    }

    private class FakeSessions(
        override val localDeviceId: String,
    ) : OlmGroupMessageOutboundSessionRepository {
        val createdSessions = mutableListOf<Pair<String, SignedOlmPreKey>>()

        override fun createOutboundSession(
            remoteDevice: PublicDeviceKeyBundle,
            remotePreKey: SignedOlmPreKey,
        ): String {
            createdSessions += remoteDevice.deviceId to remotePreKey
            return "session-${remoteDevice.deviceId}"
        }

        override fun encryptAuthenticatedEnvelope(
            sessionId: String,
            payload: OlmAuthenticatedMessagePayload,
        ): String = error("Direct message envelope is not used here")

        override fun encryptGroupMessageEnvelope(
            sessionId: String,
            payload: OlmGroupMessagePayload,
        ): String {
            assertEquals("session-${payload.recipientDeviceId}", sessionId)
            return OlmGroupMessagePayloadCodec.encode(payload)
        }
    }

    private fun verifier(trusted: Set<String>) = OlmDeviceTrustVerifier {
            peerUserId, peerDeviceId, identityKey ->
        "$peerUserId::$peerDeviceId::$identityKey" in trusted
    }

    private fun bundle(deviceId: String) = PublicDeviceKeyBundle(
        deviceId = deviceId,
        ed25519IdentityKey = "ed25519-$deviceId",
        curve25519IdentityKey = "curve25519-$deviceId",
        curve25519IdentityKeySignature = "signature-$deviceId",
        fallbackKey = SignedOlmPreKey(
            "fallback", "fallback-$deviceId", "fallback-public-$deviceId",
            "fallback-signature-$deviceId",
        ),
        oneTimeKeys = listOf(
            SignedOlmPreKey(
                "one_time", "otk-$deviceId", "otk-public-$deviceId", "otk-signature-$deviceId",
            ),
        ),
    )

    private fun membership(groupId: String, epoch: Long, vararg userIds: String) =
        GroupCryptoMembershipSnapshot(
            groupId = groupId,
            epoch = epoch,
            members = userIds.associateWith { if (it == "alice") "admin" else "member" },
        )

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected unsafe group-message fan-out to be rejected", rejected)
    }
}
