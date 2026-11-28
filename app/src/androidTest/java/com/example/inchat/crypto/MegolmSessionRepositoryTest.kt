package com.example.inchat.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-side two-device Megolm adapter coverage.
 *
 * Each endpoint has an isolated native Keystore-backed state store and local
 * identity. The test exports the exact signed sender session key, imports it
 * on the recipient, transfers ordered Megolm ciphertexts, and recreates both
 * repositories between messages to verify native ratchets survive restart.
 */
class MegolmSessionRepositoryTest {
    @Test
    fun groupMegolmRatchetRoundTripsAcrossTwoDeviceStoresAndRepositoryRestarts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val senderDeviceId = "megolm-sender-" + suffix
        val recipientDeviceId = "megolm-recipient-" + suffix
        val senderStore = CryptoStateStore(context, "device-$senderDeviceId")
        val recipientStore = CryptoStateStore(context, "device-$recipientDeviceId")
        try {
            senderStore.delete()
            recipientStore.delete()
            val sender = createIdentity(senderDeviceId, senderStore)
            val recipient = createIdentity(recipientDeviceId, recipientStore)
            val membership = GroupCryptoMembershipSnapshot(
                groupId = "megolm-e2e-group",
                epoch = 7L,
                members = mapOf(
                    "alice" to "admin",
                    "bob" to "member",
                ),
            )
            val trustedTargets = setOf("bob" to recipientDeviceId)

            val senderRepository = MegolmSessionRepository(
                context = context,
                identityRepository = sender.identity,
                authenticatedUserId = { "alice" },
            )
            var recipientRepository = MegolmSessionRepository(
                context = context,
                identityRepository = recipient.identity,
                authenticatedUserId = { "bob" },
            )

            val outboundSession = senderRepository.ensureOutboundSession(
                membership = membership,
                trustedTargetDevices = trustedTargets,
            )
            assertTrue("A new membership/roster should create a fresh Megolm session", outboundSession.created)
            assertEquals(membership.groupId, outboundSession.groupId)
            assertEquals(membership.epoch, outboundSession.membershipEpoch)

            val exportedKey = senderRepository.exportOutboundSessionKey(
                membership,
                outboundSession.sessionId,
            )
            assertTrue(
                "The recipient must import the sender's exact signed session key",
                recipientRepository.importInboundSessionKey(
                    membership = membership,
                    senderUserId = "alice",
                    senderDeviceId = senderDeviceId,
                    expectedSessionId = outboundSession.sessionId,
                    sessionKey = exportedKey,
                ),
            )
            assertTrue(
                "The exported session-key buffer must be wiped after import",
                exportedKey.all { it == 0.toByte() },
            )

            deliverAndFinalize(
                membership = membership,
                senderRepository = senderRepository,
                recipientRepository = recipientRepository,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                expectedSessionId = outboundSession.sessionId,
                messageId = "group-message-one",
                plaintext = "first encrypted group message",
                sentAtMillis = 1_700_000_000_001L,
                receivedAtMillis = 1_700_000_000_101L,
                expectedMessageIndex = 0L,
            )

            // Repository recreation models process death/restart. Both native
            // stores must retain the sender and recipient Megolm ratchet positions.
            val restartedSenderRepository = MegolmSessionRepository(
                context = context,
                identityRepository = sender.identity,
                authenticatedUserId = { "alice" },
            )
            recipientRepository = MegolmSessionRepository(
                context = context,
                identityRepository = recipient.identity,
                authenticatedUserId = { "bob" },
            )
            deliverAndFinalize(
                membership = membership,
                senderRepository = restartedSenderRepository,
                recipientRepository = recipientRepository,
                senderDeviceId = senderDeviceId,
                recipientDeviceId = recipientDeviceId,
                expectedSessionId = outboundSession.sessionId,
                messageId = "group-message-two",
                plaintext = "second encrypted group message after restart",
                sentAtMillis = 1_700_000_000_002L,
                receivedAtMillis = 1_700_000_000_102L,
                expectedMessageIndex = 1L,
            )

            assertTrue(restartedSenderRepository.pendingOutboundMessageIds().isEmpty())
            assertTrue(recipientRepository.pendingInboundDecryptionReceiptIds().isEmpty())
        } finally {
            senderStore.delete()
            recipientStore.delete()
        }
    }

    private fun deliverAndFinalize(
        membership: GroupCryptoMembershipSnapshot,
        senderRepository: MegolmSessionRepository,
        recipientRepository: MegolmSessionRepository,
        senderDeviceId: String,
        recipientDeviceId: String,
        expectedSessionId: String,
        messageId: String,
        plaintext: String,
        sentAtMillis: Long,
        receivedAtMillis: Long,
        expectedMessageIndex: Long,
    ) {
        val stagedOutbound = senderRepository.prepareOutboundMessage(
            membership = membership,
            messageId = messageId,
            plaintext = plaintext,
            sentAtMillis = sentAtMillis,
        )
        val outboundCiphertext = stagedOutbound.encryptedMessage.ciphertext
        val receiptId = sha256(
            "bob\u0000" + membership.groupId + "\u0000alice\u0000" +
                senderDeviceId + "\u0000" + expectedSessionId + "\u0000" + messageId,
        )
        try {
            assertEquals(expectedSessionId, stagedOutbound.encryptedMessage.sessionId)
            assertEquals(expectedMessageIndex, stagedOutbound.encryptedMessage.messageIndex)

            val payload = OlmGroupMessagePayload(
                messageId = messageId,
                groupId = membership.groupId,
                membershipEpoch = membership.epoch,
                senderUserId = "alice",
                senderDeviceId = senderDeviceId,
                recipientUserId = "bob",
                recipientDeviceId = recipientDeviceId,
                megolmSessionId = stagedOutbound.encryptedMessage.sessionId,
                messageIndex = stagedOutbound.encryptedMessage.messageIndex,
                ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(outboundCiphertext),
                sentAtMillis = sentAtMillis,
            )
            val encodedPayload = OlmGroupMessagePayloadCodec.encode(payload)
            val authenticatedPayload = OlmGroupMessagePayloadCodec.decodeAndValidate(
                plaintext = encodedPayload,
                expectedMessageId = messageId,
                expectedGroupId = membership.groupId,
                expectedMembershipEpoch = membership.epoch,
                expectedSenderUserId = "alice",
                expectedSenderDeviceId = senderDeviceId,
                expectedRecipientUserId = "bob",
                expectedRecipientDeviceId = recipientDeviceId,
            )
            OlmGroupMessagePayloadCodec.requireCurrentMembers(
                authenticatedPayload,
                membership.memberUserIds,
            )

            val stagedInbound = recipientRepository.prepareInboundMessage(
                membership = membership,
                payload = authenticatedPayload,
                receiptId = receiptId,
                receivedAtMillis = receivedAtMillis,
            )
            try {
                assertEquals(messageId, stagedInbound.message.messageId)
                assertEquals(expectedSessionId, stagedInbound.message.megolmSessionId)
                assertEquals(expectedMessageIndex, stagedInbound.message.messageIndex)
                assertEquals(plaintext, stagedInbound.message.text)

                // The test acknowledges the sender's outbox commit after proving
                // the recipient can decrypt the exact staged ciphertext. Production
                // finalizes only after the complete per-device Room fan-out commits.
                assertTrue(senderRepository.finalizeOutboundMessage(messageId))

                // Production commits encrypted Room content and its replay receipt
                // before finalizing this native staged plaintext.
                assertTrue(recipientRepository.finalizeInboundDecryption(receiptId))
            } finally {
                stagedInbound.ciphertext.fill(0)
            }
        } finally {
            outboundCiphertext.fill(0)
        }
    }

    private fun createIdentity(deviceId: String, store: CryptoStateStore): TestIdentity {
        val encoded = store.withExclusiveAccess {
            NativeCryptoBridge.nativeEnsureDeviceIdentity(store, deviceId)
        } ?: error("Native identity setup failed for $deviceId")
        val bundle = parseBundle(encoded, deviceId)
        val localIdentity = object : SasVerificationLocalIdentity {
            override fun ensurePublicKeyBundle(): PublicDeviceKeyBundle = bundle
            override fun requireIdentityUsable() = Unit
        }
        return TestIdentity(localIdentity)
    }

    private fun parseBundle(encoded: String, expectedDeviceId: String): PublicDeviceKeyBundle {
        val root = JSONObject(encoded)
        require(root.getInt("schemaVersion") == 1)
        val deviceId = root.getString("deviceId")
        require(deviceId == expectedDeviceId)
        val keys = root.getJSONObject("identityKeys")
        val fallback = root.optJSONObject("fallbackKey")?.let(::parsePreKey)
        val oneTimeArray = root.getJSONArray("oneTimeKeys")
        val oneTimeKeys = buildList {
            for (index in 0 until oneTimeArray.length()) {
                add(parsePreKey(oneTimeArray.getJSONObject(index)))
            }
        }
        return PublicDeviceKeyBundle(
            deviceId = deviceId,
            ed25519IdentityKey = keys.getString("ed25519"),
            curve25519IdentityKey = keys.getString("curve25519"),
            curve25519IdentityKeySignature = keys.getString("curve25519Signature"),
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

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class TestIdentity(
        val identity: SasVerificationLocalIdentity,
    )
}
