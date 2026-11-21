package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OlmGroupSessionKeyDistributionPreparerTest {
    @Test
    fun createsSeparateOlmPayloadForEveryTrustedMemberAndOtherSenderDevice() = runBlocking {
        val senderCurrent = bundle("sender-current")
        val senderTablet = bundle("sender-tablet")
        val recipientPhone = bundle("recipient-phone")
        val recipientLaptop = bundle("recipient-laptop")
        val thirdMemberPhone = bundle("third-member-phone")
        val directory = FakeDirectory(
            authenticatedUserId = "alice",
            devices = mapOf(
                "alice" to listOf(senderCurrent, senderTablet),
                "bob" to listOf(recipientPhone, recipientLaptop),
                "carol" to listOf(thirdMemberPhone),
            ),
        )
        val trusted = setOf(
            trustKey("alice", senderTablet),
            trustKey("bob", recipientPhone),
            trustKey("carol", thirdMemberPhone),
        )
        val sessions = FakeSessions(
            localDeviceId = senderCurrent.deviceId,
        )
        val preparer = OlmGroupSessionKeyDistributionPreparer(
            directory = directory,
            sessions = sessions,
            trustVerifier = verifier(trusted),
        )

        val result = preparer.prepare(
            distributionId = "distribution-1",
            membership = membership("group-1", 3L, "alice", "bob", "carol"),
            megolmSessionId = "1234567890abcdef",
            sessionKey = sessionKey(),
            issuedAtMillis = 123456L,
        )

        assertEquals("distribution-1", result.distributionId)
        assertEquals("group-1", result.groupId)
        assertEquals(3L, result.membershipEpoch)
        assertEquals(1, result.skippedUntrustedDeviceCount)
        assertEquals(
            setOf(
                "alice::sender-tablet",
                "bob::recipient-phone",
                "carol::third-member-phone",
            ),
            result.deliveries.map { it.recipientUserId + "::" + it.recipientDeviceId }.toSet(),
        )
        assertEquals(
            setOf("alice", "bob", "carol"),
            directory.readUsers.toSet(),
        )
        assertEquals(3, sessions.createdSessions.size)
        assertTrue(directory.claimedPreKeys.all { it.second != "recipient-laptop" })

        result.deliveries.forEach { delivery ->
            val payload = OlmGroupSessionKeyDistributionPayloadCodec.decodeAndValidate(
                plaintext = delivery.envelope,
                expectedDistributionId = "distribution-1",
                expectedGroupId = "group-1",
                expectedMembershipEpoch = 3L,
                expectedSenderUserId = "alice",
                expectedSenderDeviceId = "sender-current",
                expectedRecipientUserId = delivery.recipientUserId,
                expectedRecipientDeviceId = delivery.recipientDeviceId,
            )
            assertEquals("1234567890abcdef", payload.megolmSessionId)
        }
    }

    @Test
    fun preparesOnlySelectedRecoveryTargetsAfterValidatingTheFullTrustedPlan() = runBlocking {
        val sender = bundle("sender-current")
        val senderTablet = bundle("sender-tablet")
        val bob = bundle("bob-phone")
        val carol = bundle("carol-phone")
        val directory = FakeDirectory(
            authenticatedUserId = "alice",
            devices = mapOf(
                "alice" to listOf(sender, senderTablet),
                "bob" to listOf(bob),
                "carol" to listOf(carol),
            ),
        )
        val sessions = FakeSessions(sender.deviceId)
        val preparer = OlmGroupSessionKeyDistributionPreparer(
            directory = directory,
            sessions = sessions,
            trustVerifier = verifier(
                setOf(
                    trustKey("alice", senderTablet),
                    trustKey("bob", bob),
                    trustKey("carol", carol),
                ),
            ),
        )

        val selected = setOf("carol" to "carol-phone")
        val result = preparer.prepare(
            distributionId = "distribution-recovery",
            membership = membership("group-1", 3L, "alice", "bob", "carol"),
            megolmSessionId = "1234567890abcdef",
            sessionKey = sessionKey(),
            issuedAtMillis = 123456L,
            selectedTargetDevices = selected,
        )

        assertEquals(selected, result.deliveries.map {
            it.recipientUserId to it.recipientDeviceId
        }.toSet())
        assertEquals(
            setOf(
                "alice" to "sender-tablet",
                "bob" to "bob-phone",
                "carol" to "carol-phone",
            ),
            result.targetDevices.toSet(),
        )
        assertEquals(1, sessions.createdSessions.size)
        assertEquals(listOf("alice", "bob", "carol"), directory.readUsers.sorted())
        assertEquals(listOf("carol" to "carol-phone"), directory.claimedPreKeys)
    }

    @Test
    fun rechecksLiveDirectoryIdentityAndSasTrustBeforeRecoveryEnqueue() = runBlocking {
        val sender = bundle("sender-current")
        val originalBob = bundle("bob-phone")
        val changedBob = originalBob.copy(ed25519IdentityKey = "rotated-ed25519-bob-phone")
        val liveDevices = mutableMapOf(
            "alice" to listOf(sender),
            "bob" to listOf(changedBob),
        )
        val directory = FakeDirectory("alice", liveDevices)
        val preparer = OlmGroupSessionKeyDistributionPreparer(
            directory = directory,
            sessions = FakeSessions(sender.deviceId),
            trustVerifier = verifier(setOf(trustKey("bob", originalBob))),
        )
        val prepared = PreparedOlmGroupKeyDistribution(
            distributionId = "distribution-recovery",
            groupId = "group-1",
            membershipEpoch = 3L,
            megolmSessionId = "1234567890abcdef",
            deliveries = listOf(
                PreparedOlmGroupKeyDelivery(
                    recipientUserId = "bob",
                    recipientDeviceId = "bob-phone",
                    sessionId = "olm-session-bob",
                    envelope = "opaque-encrypted-envelope",
                ),
            ),
            skippedUntrustedDeviceCount = 0,
            targetDevices = listOf("bob" to "bob-phone"),
        )

        assertEquals(false, preparer.arePreparedTargetsStillTrusted(prepared))
        assertEquals(listOf("bob"), directory.readUsers)
    }

    @Test
    fun rejectsPreparedTargetRemovedFromLiveDirectoryBeforeRecoveryEnqueue() = runBlocking {
        val sender = bundle("sender-current")
        val bob = bundle("bob-phone")
        val directory = FakeDirectory(
            authenticatedUserId = "alice",
            devices = mapOf("alice" to listOf(sender), "bob" to emptyList()),
        )
        val preparer = OlmGroupSessionKeyDistributionPreparer(
            directory = directory,
            sessions = FakeSessions(sender.deviceId),
            trustVerifier = verifier(setOf(trustKey("bob", bob))),
        )
        val prepared = PreparedOlmGroupKeyDistribution(
            distributionId = "distribution-recovery",
            groupId = "group-1",
            membershipEpoch = 3L,
            megolmSessionId = "1234567890abcdef",
            deliveries = listOf(
                PreparedOlmGroupKeyDelivery(
                    recipientUserId = "bob",
                    recipientDeviceId = "bob-phone",
                    sessionId = "olm-session-bob",
                    envelope = "opaque-encrypted-envelope",
                ),
            ),
            skippedUntrustedDeviceCount = 0,
            targetDevices = listOf("bob" to "bob-phone"),
        )

        assertEquals(false, preparer.arePreparedTargetsStillTrusted(prepared))
    }

    @Test
    fun rejectsRecoveryTargetOutsideCurrentTrustedPlanBeforeClaimingAnyPrekey() = runBlocking {
        val sender = bundle("sender-current")
        val bob = bundle("bob-phone")
        val directory = FakeDirectory(
            authenticatedUserId = "alice",
            devices = mapOf("alice" to listOf(sender), "bob" to listOf(bob)),
        )
        val sessions = FakeSessions(sender.deviceId)
        val preparer = OlmGroupSessionKeyDistributionPreparer(
            directory,
            sessions,
            verifier(setOf(trustKey("bob", bob))),
        )

        assertRejected {
            runBlocking {
                preparer.prepare(
                    distributionId = "distribution-recovery",
                    membership = membership("group-1", 3L, "alice", "bob"),
                    megolmSessionId = "1234567890abcdef",
                    sessionKey = sessionKey(),
                    issuedAtMillis = 123456L,
                    selectedTargetDevices = setOf("mallory" to "mallory-phone"),
                )
            }
        }
        assertTrue(directory.claimedPreKeys.isEmpty())
        assertTrue(sessions.createdSessions.isEmpty())
    }

    @Test
    fun usesSignedFallbackWhenEveryRecipientOneTimeKeyIsAlreadyClaimed() = runBlocking {
        val sender = bundle("sender-current")
        val recipient = bundle("recipient-phone")
        val directory = FakeDirectory(
            authenticatedUserId = "alice",
            devices = mapOf(
                "alice" to listOf(sender),
                "bob" to listOf(recipient),
            ),
            claimResult = false,
        )
        val sessions = FakeSessions(sender.deviceId)
        val preparer = OlmGroupSessionKeyDistributionPreparer(
            directory,
            sessions,
            verifier(setOf(trustKey("bob", recipient))),
        )

        val result = preparer.prepare(
            distributionId = "distribution-fallback",
            membership = membership("group-2", 1L, "alice", "bob"),
            megolmSessionId = "abcdef0123456789",
            sessionKey = sessionKey(),
            issuedAtMillis = 10L,
        )

        assertEquals(1, result.deliveries.size)
        assertEquals("fallback-recipient-phone", sessions.createdSessions.single().second.keyId)
    }

    @Test
    fun failsBeforeClaimingKeysWhenAnyMemberHasNoSasTrustedDevice() = runBlocking {
        val sender = bundle("sender-current")
        val bob = bundle("bob-phone")
        val carol = bundle("carol-phone")
        val directory = FakeDirectory(
            authenticatedUserId = "alice",
            devices = mapOf(
                "alice" to listOf(sender),
                "bob" to listOf(bob),
                "carol" to listOf(carol),
            ),
        )
        val sessions = FakeSessions(sender.deviceId)
        val preparer = OlmGroupSessionKeyDistributionPreparer(
            directory,
            sessions,
            verifier(setOf(trustKey("bob", bob))),
        )

        assertRejected {
            runBlocking {
                preparer.prepare(
                    distributionId = "distribution-1",
                    membership = membership("group-1", 1L, "alice", "bob", "carol"),
                    megolmSessionId = "1234567890abcdef",
                    sessionKey = sessionKey(),
                    issuedAtMillis = 1L,
                )
            }
        }
        assertTrue(directory.claimedPreKeys.isEmpty())
        assertTrue(sessions.createdSessions.isEmpty())
    }

    @Test
    fun refusesToPrepareForAnotherAuthenticatedAccount() = runBlocking {
        val sender = bundle("sender-current")
        val directory = FakeDirectory(
            authenticatedUserId = "mallory",
            devices = mapOf("alice" to listOf(sender)),
        )
        val sessions = FakeSessions(sender.deviceId)
        val preparer = OlmGroupSessionKeyDistributionPreparer(
            directory,
            sessions,
            verifier(emptySet()),
        )

        assertRejected {
            runBlocking {
                preparer.prepare(
                    distributionId = "distribution-1",
                    membership = membership("group-1", 1L, "alice", "bob"),
                    megolmSessionId = "1234567890abcdef",
                    sessionKey = sessionKey(),
                    issuedAtMillis = 1L,
                )
            }
        }
        assertTrue(directory.readUsers.isEmpty())
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
    ) : OlmGroupKeyOutboundSessionRepository {
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
        ): String = error("Direct-text payload is not used for group-key delivery tests")

        override fun encryptGroupSessionKeyDistributionEnvelope(
            sessionId: String,
            payload: OlmGroupSessionKeyDistributionPayload,
        ): String {
            assertEquals("session-${payload.recipientDeviceId}", sessionId)
            return OlmGroupSessionKeyDistributionPayloadCodec.encode(payload)
        }
    }

    private fun verifier(trusted: Set<String>) = OlmDeviceTrustVerifier {
            peerUserId, peerDeviceId, identityKey ->
        "$peerUserId::$peerDeviceId::$identityKey" in trusted
    }

    private fun trustKey(userId: String, bundle: PublicDeviceKeyBundle): String =
        "$userId::${bundle.deviceId}::${bundle.ed25519IdentityKey}"

    private fun bundle(deviceId: String) = PublicDeviceKeyBundle(
        deviceId = deviceId,
        ed25519IdentityKey = "ed25519-$deviceId",
        curve25519IdentityKey = "curve25519-$deviceId",
        curve25519IdentityKeySignature = "signature-$deviceId",
        fallbackKey = SignedOlmPreKey(
            "fallback",
            "fallback-$deviceId",
            "fallback-public-$deviceId",
            "fallback-signature-$deviceId",
        ),
        oneTimeKeys = listOf(
            SignedOlmPreKey(
                "one_time",
                "otk-$deviceId",
                "otk-public-$deviceId",
                "otk-signature-$deviceId",
            ),
        ),
    )

    private fun membership(
        groupId: String,
        epoch: Long,
        vararg userIds: String,
    ) = GroupCryptoMembershipSnapshot(
        groupId = groupId,
        epoch = epoch,
        members = userIds.associateWith { userId ->
            if (userId == "alice") "admin" else "member"
        },
    )

    private fun sessionKey(): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(229) { index -> index.toByte() })

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Expected unsafe group-key preparation to be rejected", rejected)
    }
}
