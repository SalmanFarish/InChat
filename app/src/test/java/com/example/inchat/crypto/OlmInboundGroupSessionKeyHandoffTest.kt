package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OlmInboundGroupSessionKeyHandoffTest {
    @Test
    fun trustedCurrentMemberImportsAuthenticatedKeyAndZeroizesDecodedBytes() = runBlocking {
        val expectedKey = expectedSessionKey()
        var importedReference: ByteArray? = null
        var importedCopy: ByteArray? = null
        var importCalls = 0
        var membershipReads = 0
        val handoff = handoff(
            snapshots = mutableListOf(membership(), membership()),
            importer = MegolmInboundSessionKeyImporter { current, sender, senderDevice, sessionId, key ->
                importCalls++
                assertEquals(membership(), current)
                assertEquals("alice", sender)
                assertEquals("alice-phone", senderDevice)
                assertEquals("MEGOLM-session-123456", sessionId)
                importedReference = key
                importedCopy = key.copyOf()
                true
            },
            onMembershipRead = { membershipReads++ },
        )

        val accepted = handoff.process(
            receipt = receipt(),
            remoteDevice = remoteDevice(),
            remoteDeviceUserId = "alice",
            localUserId = "bob",
            authenticatedOlmPlaintext = payload(),
        )

        assertTrue(accepted)
        assertEquals(1, importCalls)
        assertEquals(2, membershipReads)
        assertArrayEquals(expectedKey, importedCopy)
        assertTrue(requireNotNull(importedReference).all { it == 0.toByte() })
    }

    @Test
    fun malformedGroupRoutesAreRejectedBeforeMembershipLookupOrImport() = runBlocking {
        var membershipReads = 0
        var importCalls = 0
        val handoff = handoff(
            snapshots = mutableListOf(membership(), membership()),
            importer = MegolmInboundSessionKeyImporter { _, _, _, _, _ ->
                importCalls++
                true
            },
            onMembershipRead = { membershipReads++ },
        )
        val malformedPayloads = listOf(
            JSONObject(payload()).put("groupId", 123).toString(),
            JSONObject(payload()).put("groupId", JSONObject.NULL).toString(),
            JSONObject(payload()).put("groupId", JSONObject().put("value", "group-1")).toString(),
            JSONObject(payload()).put("groupId", "../other").toString(),
            JSONObject(payload()).apply { remove("groupId") }.toString(),
        )
        malformedPayloads.forEach { malformed ->
            var rejected = false
            try {
                handoff.process(receipt(), remoteDevice(), "alice", "bob", malformed)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Malformed authenticated group-key route must be rejected", rejected)
        }
        assertEquals(0, membershipReads)
        assertEquals(0, importCalls)
    }

    @Test
    fun decodedSessionKeyIsZeroizedWhenNativeImportFails() = runBlocking {
        var importedReference: ByteArray? = null
        val handoff = handoff(
            snapshots = mutableListOf(membership(), membership()),
            importer = MegolmInboundSessionKeyImporter { _, _, _, _, key ->
                importedReference = key
                throw IllegalStateException("simulated native import failure")
            },
        )
        var failed = false
        try {
            handoff.process(receipt(), remoteDevice(), "alice", "bob", payload())
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(requireNotNull(importedReference).all { it == 0.toByte() })
    }

    @Test
    fun exactDuplicateNativeImportIsAcceptedAsAnIdempotentReceipt() = runBlocking {
        var importCalls = 0
        val handoff = handoff(
            snapshots = mutableListOf(membership(), membership()),
            importer = MegolmInboundSessionKeyImporter { _, _, _, _, _ ->
                importCalls++
                false
            },
        )

        val accepted = handoff.process(
            receipt = receipt(),
            remoteDevice = remoteDevice(),
            remoteDeviceUserId = "alice",
            localUserId = "bob",
            authenticatedOlmPlaintext = payload(),
        )

        // false means the native repository already held this exact session;
        // it is not a protocol failure and the outer Olm receipt can finalize.
        assertFalse(accepted)
        assertEquals(1, importCalls)
    }

    @Test
    fun membershipEpochChangeBeforeImportPreventsNativeMutation() = runBlocking {
        var importCalls = 0
        val changed = membership().copy(epoch = 8L)
        val handoff = handoff(
            snapshots = mutableListOf(membership(), changed),
            importer = MegolmInboundSessionKeyImporter { _, _, _, _, _ ->
                importCalls++
                true
            },
        )

        assertRejected {
            runBlocking {
                handoff.process(
                    receipt(), remoteDevice(), "alice", "bob", payload(),
                )
            }
        }
        assertEquals(0, importCalls)
    }

    @Test
    fun memberOrRoleChangeWithSameEpochPreventsNativeMutation() = runBlocking {
        var importCalls = 0
        val changed = membership().copy(
            members = mapOf("alice" to "admin", "carol" to "member"),
        )
        val handoff = handoff(
            snapshots = mutableListOf(membership(), changed),
            importer = MegolmInboundSessionKeyImporter { _, _, _, _, _ ->
                importCalls++
                true
            },
        )

        assertRejected {
            runBlocking {
                handoff.process(
                    receipt(), remoteDevice(), "alice", "bob", payload(),
                )
            }
        }
        assertEquals(0, importCalls)
    }

    @Test
    fun senderTrustRevokedDuringMembershipLookupPreventsNativeImport() = runBlocking {
        var trustChecks = 0
        var importCalls = 0
        val handoff = DefaultOlmInboundGroupSessionKeyHandoff(
            membershipForGroup = { membership() },
            keyImporter = MegolmInboundSessionKeyImporter { _, _, _, _, _ ->
                importCalls++
                true
            },
            authenticatedLocalUserId = { "bob" },
            trustVerifier = OlmDeviceTrustVerifier { userId, deviceId, identityKey ->
                trustChecks++
                userId == "alice" &&
                    deviceId == "alice-phone" &&
                    identityKey == "alice-ed25519" &&
                    trustChecks == 1
            },
        )

        var rejected = false
        try {
            handoff.process(receipt(), remoteDevice(), "alice", "bob", payload())
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue("Revoked sender trust must fail closed before native key import", rejected)
        assertEquals(2, trustChecks)
        assertEquals(0, importCalls)
    }

    @Test
    fun authenticatedAccountChangeDuringMembershipLookupPreventsNativeImport() = runBlocking {
        var accountChecks = 0
        var importCalls = 0
        val handoff = DefaultOlmInboundGroupSessionKeyHandoff(
            membershipForGroup = { membership() },
            keyImporter = MegolmInboundSessionKeyImporter { _, _, _, _, _ ->
                importCalls++
                true
            },
            authenticatedLocalUserId = {
                accountChecks++
                if (accountChecks == 1) "bob" else "mallory"
            },
            trustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
        )

        var rejected = false
        try {
            handoff.process(receipt(), remoteDevice(), "alice", "bob", payload())
        } catch (expected: IllegalStateException) {
            rejected = expected.message.orEmpty().contains("Authenticated account changed")
        }
        assertTrue("Account replacement must fail closed before native key import", rejected)
        assertEquals(2, accountChecks)
        assertEquals(0, importCalls)
    }

    @Test
    fun untrustedSenderCannotReachTheNativeImporter() = runBlocking {
        var importCalls = 0
        val handoff = handoff(
            snapshots = mutableListOf(membership(), membership()),
            importer = MegolmInboundSessionKeyImporter { _, _, _, _, _ ->
                importCalls++
                true
            },
            trusted = false,
        )

        var rejected = false
        try {
            handoff.process(receipt(), remoteDevice(), "alice", "bob", payload())
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(0, importCalls)
    }

    private fun handoff(
        snapshots: MutableList<GroupCryptoMembershipSnapshot?>,
        importer: MegolmInboundSessionKeyImporter,
        trusted: Boolean = true,
        onMembershipRead: () -> Unit = {},
    ) = DefaultOlmInboundGroupSessionKeyHandoff(
        membershipForGroup = { groupId ->
            assertEquals("group-1", groupId)
            onMembershipRead()
            if (snapshots.isEmpty()) null else snapshots.removeAt(0)
        },
        keyImporter = importer,
        authenticatedLocalUserId = { "bob" },
        trustVerifier = OlmDeviceTrustVerifier { userId, deviceId, identityKey ->
            trusted && userId == "alice" &&
                deviceId == "alice-phone" &&
                identityKey == "alice-ed25519"
        },
    )

    private fun receipt() = OlmInboundReceiptEntity(
        receiptId = "a".repeat(64),
        senderUserId = "alice",
        senderDeviceId = "alice-phone",
        messageId = "group-key-7",
        recipientDeviceId = "bob-phone",
        envelope = "opaque-outer-envelope",
        envelopeSha256 = "b".repeat(64),
        status = OlmInboundReceiptStatus.PENDING.name,
        receivedAtMillis = 1234L,
        processedAtMillis = null,
    )

    private fun remoteDevice() = PublicDeviceKeyBundle(
        deviceId = "alice-phone",
        ed25519IdentityKey = "alice-ed25519",
        curve25519IdentityKey = "alice-curve",
        curve25519IdentityKeySignature = "alice-curve-signature",
        fallbackKey = SignedOlmPreKey(
            keyKind = "fallback",
            keyId = "fallback-1",
            publicKey = "fallback-public",
            signature = "fallback-signature",
        ),
        oneTimeKeys = emptyList(),
    )

    private fun payload(): String = OlmGroupSessionKeyDistributionPayloadCodec.encode(
        OlmGroupSessionKeyDistributionPayload(
            distributionId = "group-key-7",
            groupId = "group-1",
            membershipEpoch = 7L,
            senderUserId = "alice",
            senderDeviceId = "alice-phone",
            recipientUserId = "bob",
            recipientDeviceId = "bob-phone",
            megolmSessionId = "MEGOLM-session-123456",
            sessionKey = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(expectedSessionKey()),
            issuedAtMillis = 1234L,
        ),
    )

    private fun expectedSessionKey(): ByteArray = ByteArray(32) { (it + 1).toByte() }

    private fun membership() = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = 7L,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Invalid or stale membership must prevent a key import", rejected)
    }
}
