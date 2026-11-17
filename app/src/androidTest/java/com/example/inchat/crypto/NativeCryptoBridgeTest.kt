package com.example.inchat.crypto

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking

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
    fun encryptedOlmEnvelopeRejectsUnknownFieldsAndNonCanonicalEncoding() {
        val encoded = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = "sender-device",
                recipientDeviceId = "recipient-device",
                sessionId = "olm-session-123",
                ciphertext = byteArrayOf(1, 2, 3, 4),
            )
        )
        val withUnknownField = encoded.dropLast(1) + ",\"extra\":true}"
        val reordered = "{\"algorithm\":\"olm\"," + encoded.removePrefix("{")
        listOf(withUnknownField, reordered).forEach { invalid ->
            var rejected = false
            try {
                EncryptedOlmEnvelopeCodec.decode(invalid)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Unknown or non-canonical envelope JSON must be rejected", rejected)
        }
    }

    @Test
    fun encryptedOlmEnvelopeRejectsFractionalVersionAndWrongFieldTypes() {
        val encoded = EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = "sender-device",
                recipientDeviceId = "recipient-device",
                sessionId = "olm-session-123",
                ciphertext = byteArrayOf(1, 2, 3, 4),
            )
        )
        val fractionalVersion = encoded.replace("\"encryptionVersion\":1", "\"encryptionVersion\":1.0")
        val numericSender = encoded.replace("\"senderDeviceId\":\"sender-device\"", "\"senderDeviceId\":123")
        for (invalid in listOf(fractionalVersion, numericSender)) {
            var rejected = false
            try {
                EncryptedOlmEnvelopeCodec.decode(invalid)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Malformed envelope fields must be rejected", rejected)
        }
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
        val conflictingWire = firstWire.clone().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
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

    @Test
    fun nativeSasVerificationAuthenticatesBothDeviceIdentities() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val aliceDeviceId = "sas-alice-$suffix"
        val bobDeviceId = "sas-bob-$suffix"
        val aliceStore = CryptoStateStore(context, "sas-alice-store-$suffix")
        val bobStore = CryptoStateStore(context, "sas-bob-store-$suffix")

        val aliceBundle = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(aliceStore, aliceDeviceId)
            )
        )
        val bobBundle = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(bobStore, bobDeviceId)
            )
        )
        val aliceEd25519 = aliceBundle.getJSONObject("identityKeys").getString("ed25519")
        val bobEd25519 = bobBundle.getJSONObject("identityKeys").getString("ed25519")
        val transactionId = "sas-transaction-$suffix"

        val alice = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeSasCreate(
                    "alice-user", aliceDeviceId, aliceEd25519,
                    "bob-user", bobDeviceId, bobEd25519,
                    transactionId, true,
                )
            )
        )
        val bob = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeSasCreate(
                    "alice-user", aliceDeviceId, aliceEd25519,
                    "bob-user", bobDeviceId, bobEd25519,
                    transactionId, false,
                )
            )
        )
        val aliceHandle = alice.getString("handle")
        val bobHandle = bob.getString("handle")

        assertTrue(
            NativeCryptoBridge.nativeSasAcceptPeerCommitment(
                aliceHandle, bob.getString("commitment")
            )
        )
        assertTrue(
            NativeCryptoBridge.nativeSasAcceptPeerCommitment(
                bobHandle, alice.getString("commitment")
            )
        )
        val alicePublicKey = requireNotNull(
            NativeCryptoBridge.nativeSasRevealPublicKey(aliceHandle)
        )
        val bobPublicKey = requireNotNull(
            NativeCryptoBridge.nativeSasRevealPublicKey(bobHandle)
        )
        assertTrue(
            NativeCryptoBridge.nativeSasAcceptPeerPublicKey(aliceHandle, bobPublicKey)
        )
        assertTrue(
            NativeCryptoBridge.nativeSasAcceptPeerPublicKey(bobHandle, alicePublicKey)
        )
        assertEquals(
            NativeCryptoBridge.nativeSasDisplayCodes(aliceHandle),
            NativeCryptoBridge.nativeSasDisplayCodes(bobHandle),
        )

        assertTrue(NativeCryptoBridge.nativeSasConfirmMatch(aliceHandle, true))
        assertTrue(NativeCryptoBridge.nativeSasConfirmMatch(bobHandle, true))
        val aliceMac = requireNotNull(NativeCryptoBridge.nativeSasCreateLocalMac(aliceHandle))
        val bobMac = requireNotNull(NativeCryptoBridge.nativeSasCreateLocalMac(bobHandle))
        assertTrue(NativeCryptoBridge.nativeSasVerifyPeerMac(aliceHandle, bobMac))
        assertTrue(NativeCryptoBridge.nativeSasVerifyPeerMac(bobHandle, aliceMac))
        assertTrue(NativeCryptoBridge.nativeSasIsComplete(aliceHandle))
        assertTrue(NativeCryptoBridge.nativeSasIsComplete(bobHandle))

        assertTrue(NativeCryptoBridge.nativeSasCancel(aliceHandle))
        assertTrue(NativeCryptoBridge.nativeSasCancel(bobHandle))
        assertTrue(!NativeCryptoBridge.nativeSasIsComplete(aliceHandle))
    }


    @Test
    fun deviceTransferJournalLocksAcrossInstancesAndCanCancel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val sourceDeviceId = "journal-source-$suffix"
        val targetDeviceId = "journal-target-$suffix"
        val transactionId = UUID.randomUUID().toString().replace("-", "")
        val journalDirectory = File(context.cacheDir, "device-transfer-journal-$suffix")

        try {
            val firstJournal = DeviceTransferJournal(context, journalDirectory)
            firstJournal.begin(
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                "journal-user-$suffix",
                "b".repeat(64),
            )
            firstJournal.requirePendingTarget(sourceDeviceId, targetDeviceId, transactionId)

            var locked = false
            try {
                DeviceTransferJournal(context, journalDirectory).requireUnlocked(sourceDeviceId)
            } catch (_: IllegalStateException) {
                locked = true
            }
            assertTrue("A recreated journal must retain the source lock", locked)

            var wrongTargetRejected = false
            try {
                firstJournal.requirePendingTarget(
                    sourceDeviceId,
                    "different-target",
                    transactionId,
                )
            } catch (_: IllegalStateException) {
                wrongTargetRejected = true
            }
            assertTrue("Export must be bound to the journal's target", wrongTargetRejected)

            firstJournal.cancel(sourceDeviceId)
            DeviceTransferJournal(context, journalDirectory).requireUnlocked(sourceDeviceId)

            val acknowledgedTransaction = UUID.randomUUID().toString().replace("-", "")
            firstJournal.begin(
                sourceDeviceId,
                targetDeviceId,
                acknowledgedTransaction,
                "journal-user-$suffix",
                "b".repeat(64),
            )
            firstJournal.markExported(sourceDeviceId, targetDeviceId, acknowledgedTransaction)
            val exported = DeviceTransferJournal(context, journalDirectory).pending()
            assertTrue("Export status must persist across journal recreation", exported?.exported == true)

            var exportedCancelRejected = false
            try {
                firstJournal.cancel(sourceDeviceId)
            } catch (_: IllegalStateException) {
                exportedCancelRejected = true
            }
            assertTrue("A possibly delivered export must not be cancelled", exportedCancelRejected)

            firstJournal.markAcknowledged(
                sourceDeviceId,
                targetDeviceId,
                acknowledgedTransaction,
                "a".repeat(64),
            )
            val restored = DeviceTransferJournal(context, journalDirectory).pending()
            assertTrue(
                "Verified acknowledgement must persist across journal recreation",
                restored?.acknowledged == true,
            )

            firstJournal.markRetiring(
                sourceDeviceId,
                targetDeviceId,
                acknowledgedTransaction,
            )
            firstJournal.markRetired(
                sourceDeviceId,
                targetDeviceId,
                acknowledgedTransaction,
            )
            val retired = DeviceTransferJournal(context, journalDirectory).pending()
            assertTrue("Retirement status must survive journal recreation", retired?.retired == true)

            var retiredStillLocked = false
            try {
                firstJournal.requireUnlocked(sourceDeviceId)
            } catch (_: IllegalStateException) {
                retiredStillLocked = true
            }
            assertTrue("A retired source must never recreate/use its old identity", retiredStillLocked)
        } finally {
            journalDirectory.deleteRecursively()
        }
    }

    @Test
    fun cachedOlmRepositoryCannotBypassAStartedIdentityTransfer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val testRoot = File(context.cacheDir, "cached-transfer-lock-$suffix")
        val noBackupDirectory = File(testRoot, "no-backup").also { it.mkdirs() }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = noBackupDirectory
        }
        val olmRepository = OlmSessionRepository(isolatedContext)
        val identityRepository = DeviceIdentityRepository(isolatedContext)
        val originalDeviceId = olmRepository.localDeviceId
        identityRepository.beginIdentityTransfer(
            targetDeviceId = "transfer-lock-target-$suffix",
            ownerUserId = "transfer-lock-user-$suffix",
        )

        try {
            assertEquals(originalDeviceId, identityRepository.currentDeviceId())
            var blocked = false
            try {
                // localBundle was already cached before beginIdentityTransfer.
                olmRepository.localDeviceId
            } catch (_: IllegalStateException) {
                blocked = true
            }
            assertTrue("A cached Olm repository must re-check the durable transfer lock", blocked)
        } finally {
            runCatching { identityRepository.cancelIdentityTransfer() }
            runCatching {
                CryptoStateStore(isolatedContext, "device-$originalDeviceId").delete()
            }
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun transferCoordinatorPublishesDestinationBeforeRevokingAndRetiringSource() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val ownerUserId = "transfer-user-$suffix"
        val sourceRoot = File(context.cacheDir, "identity-transfer-source-$suffix")
        val targetRoot = File(context.cacheDir, "identity-transfer-target-$suffix")
        val sourceNoBackup = File(sourceRoot, "no-backup")
        val targetNoBackup = File(targetRoot, "no-backup")
        sourceNoBackup.mkdirs()
        targetNoBackup.mkdirs()

        fun isolatedContext(noBackupDirectory: File): Context =
            object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir(): File = noBackupDirectory
            }

        val sourceContext = isolatedContext(sourceNoBackup)
        val targetContext = isolatedContext(targetNoBackup)
        val sourceRepository = DeviceIdentityRepository(sourceContext)
        val targetRepository = DeviceIdentityRepository(targetContext)
        val sourceBundle = sourceRepository.ensurePublicKeyBundle()
        val targetDeviceId = targetRepository.currentDeviceId()
        val publishedDevices = linkedMapOf<String, PublicDeviceKeyBundle>()

        val directory = object : DeviceIdentityTransferDirectory {
            override val authenticatedUserId: String = ownerUserId

            override suspend fun publishMyDevice(bundle: PublicDeviceKeyBundle) {
                publishedDevices[bundle.deviceId] = bundle
            }

            override suspend fun getUserDevices(uid: String): List<PublicDeviceKeyBundle> {
                check(uid == ownerUserId)
                return publishedDevices.values.toList()
            }

            override suspend fun removeMyDevice(deviceId: String) {
                publishedDevices.remove(deviceId)
            }
        }

        var encryptedPayload: ByteArray? = null
        var acknowledgement: ByteArray? = null
        val transferKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        try {
            directory.publishMyDevice(sourceBundle)
            val sourceCoordinator = DeviceIdentityTransferCoordinator(sourceRepository, directory)
            val targetCoordinator = DeviceIdentityTransferCoordinator(targetRepository, directory)
            val transactionId = sourceCoordinator.beginTransfer(targetDeviceId)

            encryptedPayload = sourceRepository.exportEncryptedIdentity(
                targetDeviceId = targetDeviceId,
                transactionId = transactionId,
                transferKey = transferKey.clone(),
                nonce = ByteArray(12).also { SecureRandom().nextBytes(it) },
            )
            targetRepository.importEncryptedIdentity(
                sourceDeviceId = sourceBundle.deviceId,
                transactionId = transactionId,
                transferKey = transferKey.clone(),
                encryptedPayload = requireNotNull(encryptedPayload).clone(),
            )

            acknowledgement = targetCoordinator.publishImportedDestination(
                sourceDeviceId = sourceBundle.deviceId,
                ownerUserId = ownerUserId,
                transactionId = transactionId,
            )

            // A valid receipt alone must not retire the source when the target
            // cannot yet be confirmed in the account directory.
            val destinationEntry = publishedDevices.remove(targetDeviceId)
            var missingDestinationRejected = false
            try {
                sourceCoordinator.acknowledgeAndRetireSource(
                    targetDeviceId = targetDeviceId,
                    transactionId = transactionId,
                    acknowledgement = requireNotNull(acknowledgement).clone(),
                )
            } catch (_: IllegalStateException) {
                missingDestinationRejected = true
            }
            assertTrue("An unavailable destination directory entry must block retirement", missingDestinationRejected)
            assertTrue("Source state must remain while destination confirmation is pending", CryptoStateStore(sourceContext, "device-${sourceBundle.deviceId}").read() != null)
            assertTrue("The source transfer lock must remain after the failed retirement attempt", sourceRepository.hasPendingIdentityTransfer())
            publishedDevices[targetDeviceId] = requireNotNull(destinationEntry)

            assertTrue(
                "Source retirement should succeed only after destination publication",
                sourceCoordinator.acknowledgeAndRetireSource(
                    targetDeviceId = targetDeviceId,
                    transactionId = transactionId,
                    acknowledgement = requireNotNull(acknowledgement).clone(),
                ),
            )

            assertTrue("Destination must remain in the account directory", publishedDevices.containsKey(targetDeviceId))
            assertTrue("Old source directory entry must be revoked", !publishedDevices.containsKey(sourceBundle.deviceId))
            assertEquals(
                "Destination keeps the transferred Ed25519 identity",
                sourceBundle.ed25519IdentityKey,
                publishedDevices.getValue(targetDeviceId).ed25519IdentityKey,
            )
            assertTrue(
                "Source journal must retain a terminal retirement tombstone",
                sourceRepository.pendingIdentityTransfer()?.retired == true,
            )
            assertTrue(
                "Retired source must not recreate its old crypto identity",
                runCatching { sourceRepository.ensurePublicKeyBundle() }.isFailure,
            )
            assertTrue(
                "Retired source crypto state must be deleted",
                CryptoStateStore(sourceContext, "device-${sourceBundle.deviceId}").read() == null,
            )
        } finally {
            transferKey.fill(0)
            encryptedPayload?.fill(0)
            acknowledgement?.fill(0)
            runCatching {
                CryptoStateStore(targetContext, "device-$targetDeviceId").delete()
            }
            runCatching {
                CryptoStateStore(sourceContext, "device-${sourceRepository.currentDeviceId()}").delete()
            }
            sourceRoot.deleteRecursively()
            targetRoot.deleteRecursively()
        }
    }

    @Test
    fun encryptedDeviceIdentityTransferPreservesIdentityAndOlmSessions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val sourceDeviceId = "transfer-source-$suffix"
        val targetDeviceId = "transfer-target-$suffix"
        val peerDeviceId = "transfer-peer-$suffix"
        val sourceStore = CryptoStateStore(context, "device-$sourceDeviceId")
        val targetStore = CryptoStateStore(context, "device-$targetDeviceId")
        val peerStore = CryptoStateStore(context, "device-$peerDeviceId")

        val sourceBundle = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(sourceStore, sourceDeviceId)
            )
        )
        val peerBundle = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(peerStore, peerDeviceId)
            )
        )
        assertTrue(NativeCryptoBridge.nativeMarkDeviceKeysPublished(peerStore, peerDeviceId))

        val sourceIdentity = sourceBundle.getJSONObject("identityKeys")
        val peerIdentity = peerBundle.getJSONObject("identityKeys")
        val peerOneTimeKey = peerBundle.getJSONArray("oneTimeKeys")
            .getJSONObject(0).getString("key")
        val sessionId = requireNotNull(
            NativeCryptoBridge.nativeCreateOutboundOlmSession(
                sourceStore,
                sourceDeviceId,
                peerIdentity.getString("curve25519"),
                peerOneTimeKey,
            )
        )

        val transactionId = UUID.randomUUID().toString().replace("-", "")
        val transferKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val exportKey = transferKey.clone()
        val exportNonce = nonce.clone()
        val encryptedTransfer = requireNotNull(
            NativeCryptoBridge.nativeExportDeviceIdentity(
                sourceStore,
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                exportKey,
                exportNonce,
            )
        )
        assertTrue("Native export must clear its temporary key buffer", exportKey.all { it == 0.toByte() })
        assertTrue("Native export must clear its temporary nonce buffer", exportNonce.all { it == 0.toByte() })

        val wrongKey = transferKey.clone().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertTrue(
            "The transfer envelope must not be reusable in another transaction",
            !NativeCryptoBridge.nativeImportDeviceIdentity(
                targetStore,
                sourceDeviceId,
                targetDeviceId,
                UUID.randomUUID().toString().replace("-", ""),
                transferKey.clone(),
                encryptedTransfer.clone(),
            ),
        )

        assertTrue(
            "A transfer encrypted with another key must not import",
            !NativeCryptoBridge.nativeImportDeviceIdentity(
                targetStore,
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                wrongKey,
                encryptedTransfer.clone(),
            ),
        )

        assertTrue(
            "The authenticated transfer should import into the empty target store",
            NativeCryptoBridge.nativeImportDeviceIdentity(
                targetStore,
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                transferKey.clone(),
                encryptedTransfer.clone(),
            ),
        )

        assertTrue(
            "A destination identity must never be overwritten by a second import",
            !NativeCryptoBridge.nativeImportDeviceIdentity(
                targetStore,
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                transferKey.clone(),
                encryptedTransfer.clone(),
            ),
        )

        val acknowledgement = requireNotNull(
            NativeCryptoBridge.nativeCreateDeviceTransferAcknowledgement(
                targetStore,
                sourceDeviceId,
                targetDeviceId,
                transactionId,
            )
        )
        val encodedAcknowledgement = String(acknowledgement, Charsets.UTF_8)
        assertTrue(
            "A destination receipt must be an encoded Ed25519 signature",
            encodedAcknowledgement.matches(Regex("[A-Za-z0-9_+/=-]{80,128}")),
        )

        assertTrue(
            "A receipt must not verify for a different transaction",
            !NativeCryptoBridge.nativeVerifyDeviceTransferAcknowledgement(
                sourceStore,
                sourceDeviceId,
                targetDeviceId,
                UUID.randomUUID().toString().replace("-", ""),
                acknowledgement.clone(),
            ),
        )

        assertTrue(
            "A receipt must not verify for a different target device",
            !NativeCryptoBridge.nativeVerifyDeviceTransferAcknowledgement(
                sourceStore,
                sourceDeviceId,
                "different-target",
                transactionId,
                acknowledgement.clone(),
            ),
        )

        assertTrue(
            "A receipt must not verify for a different source device",
            !NativeCryptoBridge.nativeVerifyDeviceTransferAcknowledgement(
                sourceStore,
                "different-source",
                targetDeviceId,
                transactionId,
                acknowledgement.clone(),
            ),
        )

        val tamperedReceipt = acknowledgement.clone().also {
            it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte()
        }
        assertTrue(
            "A modified Ed25519 receipt must be rejected",
            !NativeCryptoBridge.nativeVerifyDeviceTransferAcknowledgement(
                sourceStore,
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                tamperedReceipt,
            ),
        )

        val verifyReceipt = acknowledgement.clone()
        assertTrue(
            "Source must verify the transaction-bound identity signature",
            NativeCryptoBridge.nativeVerifyDeviceTransferAcknowledgement(
                sourceStore,
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                verifyReceipt,
            ),
        )
        assertTrue("Native receipt verification must clear its input buffer", verifyReceipt.all { it == 0.toByte() })

        val transferredBundle = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeEnsureDeviceIdentity(targetStore, targetDeviceId)
            )
        )
        assertEquals(targetDeviceId, transferredBundle.getString("deviceId"))
        assertEquals(
            sourceIdentity.getString("ed25519"),
            transferredBundle.getJSONObject("identityKeys").getString("ed25519"),
        )
        assertEquals(
            sourceIdentity.getString("curve25519"),
            transferredBundle.getJSONObject("identityKeys").getString("curve25519"),
        )
        assertTrue(
            "Transferred public keys must be correctly signed for the new device ID",
            NativeCryptoBridge.nativeVerifyDeviceKeyBundle(transferredBundle.toString()),
        )

        val encryptedMessage = requireNotNull(
            NativeCryptoBridge.nativeEncryptOlmMessage(
                targetStore,
                targetDeviceId,
                sessionId,
                "session survived identity transfer",
            )
        )
        val received = JSONObject(
            requireNotNull(
                NativeCryptoBridge.nativeCreateInboundOlmSession(
                    peerStore,
                    peerDeviceId,
                    sourceIdentity.getString("curve25519"),
                    encryptedMessage,
                )
            )
        )
        assertEquals("session survived identity transfer", received.getString("plaintext"))
        assertEquals(sessionId, received.getString("sessionId"))

        // These stores use unique test-only IDs; remove only those generated stores.
        sourceStore.delete()
        targetStore.delete()
        peerStore.delete()
    }

}
