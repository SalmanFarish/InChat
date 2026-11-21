package com.example.inchat.crypto

import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class OlmInboundGroupMessageHandoffTest {
    @Test
    fun extractsOnlyAValidGroupMessageRoute() {
        assertEquals(
            "group-123",
            extractOlmGroupMessageRouteGroupId(
                """{"messageType":"group_message","groupId":"group-123"}""",
            ),
        )
    }

    @Test
    fun rejectsAuthenticatedAccountChangeBeforeGroupMessageCommit() {
        try {
            requireUnchangedGroupRecipient("local-user", "different-account")
            org.junit.Assert.fail("An account switch during group decryption must be rejected")
        } catch (expected: IllegalStateException) {
            assertEquals(
                "Authenticated account changed during group-message decryption",
                expected.message,
            )
        }
    }

    @Test
    fun acceptsUnchangedAuthenticatedAccount() {
        requireUnchangedGroupRecipient("local-user", "local-user")
    }

    @Test
    fun rejectsWrongPayloadTypeBeforeMembershipLookup() {
        assertInvalidRoute("""{"messageType":"direct_message","groupId":"group-123"}""")
    }

    @Test
    fun rejectsMissingOrNonStringGroupId() {
        assertInvalidRoute("""{"messageType":"group_message"}""")
        assertInvalidRoute("""{"messageType":"group_message","groupId":123}""")
    }

    @Test
    fun rejectsMalformedJsonAndInvalidGroupIdentifiers() {
        assertInvalidRoute("{\"messageType\":\"group_message\"")
        assertInvalidRoute("""{"messageType":"group_message","groupId":"../other-group"}""")
        assertInvalidRoute("""{"messageType":"group_message","groupId":""}""")
        assertInvalidRoute("""{"messageType":"group_message","groupId":null}""")
        assertInvalidRoute("""{"messageType":"group_message","groupId":{"value":"group-123"}}""")
        assertInvalidRoute("""{"messageType":"group_message","groupId":"group/123"}""")
        val oversizedGroupId = "x".repeat(129)
        assertInvalidRoute("""{"messageType":"group_message","groupId":"$oversizedGroupId"}""")
    }

    @Test
    fun validHandoffRevalidatesAndCommitsStagedMessage() = runBlocking {
        val stagedCiphertext = byteArrayOf(4, 5, 6, 7)
        var membershipReads = 0
        var trustChecks = 0
        var stagingCalls = 0
        var commitCalls = 0
        var committedMessage: DecryptedGroupMessage? = null
        val snapshots = mutableListOf(membership(), membership(), membership())
        val handoff = handoff(
            snapshots = snapshots,
            stagedCiphertext = stagedCiphertext,
            authenticatedUser = { "bob" },
            trustVerifier = OlmDeviceTrustVerifier { user, device, key ->
                trustChecks++
                user == "alice" && device == "alice-phone" && key == "alice-ed25519"
            },
            onMembershipRead = { membershipReads++ },
            stager = MegolmInboundGroupMessageStager { current, payload, receiptId, receivedAt ->
                stagingCalls++
                assertEquals(membership(), current)
                assertEquals("group-message-7", payload.messageId)
                assertEquals("a".repeat(64), receiptId)
                assertEquals(1234L, receivedAt)
                staged(stagedCiphertext)
            },
            committer = EncryptedGroupMessageCommitter { message, _, ciphertext ->
                commitCalls++
                assertEquals(listOf(4, 5, 6, 7), ciphertext.map { it.toInt() })
                committedMessage = message
                true
            },
        )

        assertTrue(handoff.process(receipt(), remoteDevice(), "alice", "bob", payload()))
        assertEquals(3, membershipReads)
        assertEquals(3, trustChecks)
        assertEquals(1, stagingCalls)
        assertEquals(1, commitCalls)
        assertEquals("group-message-7", committedMessage?.messageId)
        assertEquals("authenticated group plaintext", committedMessage?.text)
        assertTrue("Staged ciphertext must be cleared after commit", stagedCiphertext.all { it == 0.toByte() })
    }

    @Test
    fun membershipChangeAfterNativeStagingPreventsRoomCommitAndClearsCiphertext() = runBlocking {
        val stagedCiphertext = byteArrayOf(8, 9, 10)
        var commitCalls = 0
        val changedMembership = membership().copy(epoch = 8L)
        val handoff = handoff(
            snapshots = mutableListOf(membership(), membership(), changedMembership),
            stagedCiphertext = stagedCiphertext,
            committer = EncryptedGroupMessageCommitter { _, _, _ ->
                commitCalls++
                true
            },
        )

        assertRejected { runBlocking { handoff.process(receipt(), remoteDevice(), "alice", "bob", payload()) } }
        assertEquals(0, commitCalls)
        assertTrue(stagedCiphertext.all { it == 0.toByte() })
    }

    @Test
    fun memberOrRoleChangeAfterNativeStagingPreventsRoomCommit() = runBlocking {
        val stagedCiphertext = byteArrayOf(11, 12, 13)
        var commitCalls = 0
        val changedMembership = membership().copy(
            members = mapOf("alice" to "member", "bob" to "admin"),
        )
        val handoff = handoff(
            snapshots = mutableListOf(membership(), membership(), changedMembership),
            stagedCiphertext = stagedCiphertext,
            committer = EncryptedGroupMessageCommitter { _, _, _ ->
                commitCalls++
                true
            },
        )

        assertRejected { runBlocking { handoff.process(receipt(), remoteDevice(), "alice", "bob", payload()) } }
        assertEquals(0, commitCalls)
        assertTrue(stagedCiphertext.all { it == 0.toByte() })
    }

    @Test
    fun authenticatedAccountSwitchAfterNativeStagingPreventsRoomCommit() = runBlocking {
        val stagedCiphertext = byteArrayOf(14, 15, 16)
        var accountChecks = 0
        var commitCalls = 0
        val handoff = handoff(
            snapshots = mutableListOf(membership(), membership(), membership()),
            stagedCiphertext = stagedCiphertext,
            authenticatedUser = {
                accountChecks++
                if (accountChecks == 1) "bob" else "mallory"
            },
            committer = EncryptedGroupMessageCommitter { _, _, _ ->
                commitCalls++
                true
            },
        )

        var rejected = false
        try {
            handoff.process(receipt(), remoteDevice(), "alice", "bob", payload())
        } catch (expected: IllegalStateException) {
            rejected = expected.message.orEmpty().contains("Authenticated account changed")
        }
        assertTrue("Account change during native staging must fail closed", rejected)
        assertEquals(2, accountChecks)
        assertEquals(0, commitCalls)
        assertTrue(stagedCiphertext.all { it == 0.toByte() })
    }

    @Test
    fun senderTrustRevocationAfterNativeStagingPreventsRoomCommit() = runBlocking {
        val stagedCiphertext = byteArrayOf(17, 18, 19)
        var trustChecks = 0
        var commitCalls = 0
        val handoff = handoff(
            snapshots = mutableListOf(membership(), membership(), membership()),
            stagedCiphertext = stagedCiphertext,
            trustVerifier = OlmDeviceTrustVerifier { _, _, _ ->
                trustChecks++
                trustChecks < 3
            },
            committer = EncryptedGroupMessageCommitter { _, _, _ ->
                commitCalls++
                true
            },
        )

        var rejected = false
        try {
            handoff.process(receipt(), remoteDevice(), "alice", "bob", payload())
        } catch (expected: IllegalStateException) {
            rejected = expected.message.orEmpty().contains("trust was revoked")
        }
        assertTrue("Sender trust revocation after staging must fail closed", rejected)
        assertEquals(3, trustChecks)
        assertEquals(0, commitCalls)
        assertTrue(stagedCiphertext.all { it == 0.toByte() })
    }

    private fun handoff(
        snapshots: MutableList<GroupCryptoMembershipSnapshot?>,
        stagedCiphertext: ByteArray,
        authenticatedUser: () -> String = { "bob" },
        trustVerifier: OlmDeviceTrustVerifier = OlmDeviceTrustVerifier { _, _, _ -> true },
        onMembershipRead: () -> Unit = {},
        stager: MegolmInboundGroupMessageStager = MegolmInboundGroupMessageStager { _, _, _, _ ->
            staged(stagedCiphertext)
        },
        committer: EncryptedGroupMessageCommitter = EncryptedGroupMessageCommitter { _, _, _ -> true },
    ) = DefaultOlmInboundGroupMessageHandoff(
        membershipForGroup = { groupId ->
            assertEquals("group-1", groupId)
            onMembershipRead()
            if (snapshots.isEmpty()) null else snapshots.removeAt(0)
        },
        sessionRepository = stager,
        messageRepository = committer,
        authenticatedLocalUserId = authenticatedUser,
        trustVerifier = trustVerifier,
    )

    private fun staged(ciphertext: ByteArray) = StagedMegolmDecryption(
        receiptId = "a".repeat(64),
        message = DecryptedGroupMessage(
            ownerUserId = "bob",
            groupId = "group-1",
            messageId = "group-message-7",
            membershipEpoch = 7L,
            senderUserId = "alice",
            senderDeviceId = "alice-phone",
            megolmSessionId = "MEGOLM-session-123456",
            messageIndex = 15L,
            sentAtMillis = 1200L,
            text = "authenticated group plaintext",
        ),
        ciphertext = ciphertext,
        receivedAtMillis = 1234L,
    )

    private fun payload() = OlmGroupMessagePayloadCodec.encode(
        OlmGroupMessagePayload(
            messageId = "group-message-7",
            groupId = "group-1",
            membershipEpoch = 7L,
            senderUserId = "alice",
            senderDeviceId = "alice-phone",
            recipientUserId = "bob",
            recipientDeviceId = "bob-phone",
            megolmSessionId = "MEGOLM-session-123456",
            messageIndex = 15L,
            ciphertext = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(byteArrayOf(1, 2, 3)),
            sentAtMillis = 1200L,
        ),
    )

    private fun membership() = GroupCryptoMembershipSnapshot(
        groupId = "group-1",
        epoch = 7L,
        members = mapOf("alice" to "admin", "bob" to "member"),
    )

    private fun receipt() = OlmInboundReceiptEntity(
        receiptId = "a".repeat(64),
        senderUserId = "alice",
        senderDeviceId = "alice-phone",
        messageId = "group-message-7",
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

    private fun assertInvalidRoute(plaintext: String) {
        try {
            extractOlmGroupMessageRouteGroupId(plaintext)
            org.junit.Assert.fail("Expected malformed authenticated group route to be rejected")
        } catch (_: IllegalArgumentException) {
            // Invalid routes must fail before membership/session access.
        }
    }

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("Changed membership must prevent the group-message commit", rejected)
    }
}
