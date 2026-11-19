package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SasVerificationCoordinatorTest {

    @Test
    fun twoAccountsCompleteSasOnlyAfterBothUsersConfirmAndTrustTheExpectedKeys() = runBlocking {
        val fixture = Fixture()
        var transactionId: String? = null
        try {
            val started = fixture.alice.coordinator.startVerification(
                peerUserId = fixture.bob.userId,
                peerDeviceId = fixture.bob.bundle.deviceId,
            )
            transactionId = started.transactionId
            assertEquals(SasVerificationPhase.WAITING_FOR_PEER, started.phase)

            assertEquals(1, fixture.bob.coordinator.receivePending().received)
            assertEquals(2, fixture.alice.coordinator.receivePending().received)
            assertEquals(
                SasVerificationPhase.CONFIRMING,
                fixture.alice.coordinator.states.value[started.transactionId]?.phase,
            )
            assertEquals(1, fixture.bob.coordinator.receivePending().received)

            val aliceState = fixture.alice.coordinator.states.value[started.transactionId]
            val bobState = fixture.bob.coordinator.states.value[started.transactionId]
            assertNotNull(aliceState)
            assertNotNull(bobState)
            assertEquals(SasVerificationPhase.CONFIRMING, bobState?.phase)
            assertEquals(aliceState?.decimalCodes, bobState?.decimalCodes)
            assertEquals(aliceState?.emojiIndices, bobState?.emojiIndices)

            // Both installations must explicitly confirm the same comparison.
            fixture.alice.coordinator.confirmSasMatch(started.transactionId)
            fixture.bob.coordinator.confirmSasMatch(started.transactionId)
            assertEquals(
                SasVerificationPhase.CONFIRMING,
                fixture.alice.coordinator.states.value[started.transactionId]?.phase,
            )
            assertEquals(
                SasVerificationPhase.CONFIRMING,
                fixture.bob.coordinator.states.value[started.transactionId]?.phase,
            )

            assertEquals(1, fixture.alice.coordinator.receivePending().received)
            assertEquals(1, fixture.bob.coordinator.receivePending().received)
            assertEquals(
                SasVerificationPhase.COMPLETE,
                fixture.alice.coordinator.states.value[started.transactionId]?.phase,
            )
            assertEquals(
                SasVerificationPhase.COMPLETE,
                fixture.bob.coordinator.states.value[started.transactionId]?.phase,
            )
            assertTrue(
                fixture.alice.trust.contains(
                    Triple(
                        fixture.bob.userId,
                        fixture.bob.bundle.deviceId,
                        fixture.bob.bundle.ed25519IdentityKey,
                    )
                )
            )
            assertTrue(
                fixture.bob.trust.contains(
                    Triple(
                        fixture.alice.userId,
                        fixture.alice.bundle.deviceId,
                        fixture.alice.bundle.ed25519IdentityKey,
                    )
                )
            )
        } finally {
            transactionId?.let { fixture.cancelBoth(it) }
            fixture.close()
        }
    }

    @Test
    fun cancelFromAnotherAccountAndDeviceCannotTerminateTheActiveAttempt() = runBlocking {
        val fixture = Fixture()
        var transactionId: String? = null
        try {
            val started = fixture.alice.coordinator.startVerification(
                peerUserId = fixture.bob.userId,
                peerDeviceId = fixture.bob.bundle.deviceId,
            )
            transactionId = started.transactionId
            val forgedCancel = SasVerificationMessage(
                transactionId = started.transactionId,
                senderUserId = "mallory-user",
                senderDeviceId = "mallory-device",
                recipientUserId = fixture.alice.userId,
                recipientDeviceId = fixture.alice.bundle.deviceId,
                type = SasVerificationMessageType.CANCEL,
                payload = "",
                createdAtMillis = FIXED_NOW,
                expiresAtMillis = FIXED_NOW + 60_000L,
            )
            fixture.relay.inject(forgedCancel)

            val rejected = runCatching { fixture.alice.coordinator.receivePending() }
            assertTrue("A cancellation from a different account/device must be rejected", rejected.isFailure)
            assertEquals(
                SasVerificationPhase.WAITING_FOR_PEER,
                fixture.alice.coordinator.states.value[started.transactionId]?.phase,
            )
            assertFalse(fixture.relay.wasAcknowledged(forgedCancel.deliveryId))
            assertTrue(fixture.relay.contains(forgedCancel.deliveryId))
        } finally {
            transactionId?.let { fixture.cancelBoth(it) }
            fixture.close()
        }
    }

    @Test
    fun coordinatorRejectsDirectoryAccountDifferentFromLocalIdentity() = runBlocking {
        val fixture = Fixture()
        try {
            val mismatchedDirectory = TestDirectory(
                authenticatedUserId = "different-signed-in-account",
                bundles = fixture.bundlesByUser,
            )
            val mismatchedCoordinator = SasVerificationCoordinator(
                localUserId = fixture.alice.userId,
                identityRepository = fixture.alice.identity,
                directoryRepository = mismatchedDirectory,
                transport = fixture.relay.client(fixture.alice.userId),
                ledger = fixture.alice.ledger,
                trustedDeviceRepository = fixture.alice.trust,
                clock = { FIXED_NOW },
            )
            val rejected = runCatching {
                mismatchedCoordinator.startVerification(
                    fixture.bob.userId,
                    fixture.bob.bundle.deviceId,
                )
            }
            assertTrue("A different authenticated account must fail closed", rejected.isFailure)
            assertTrue(fixture.relay.isEmpty())
        } finally {
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val suffix = UUID.randomUUID().toString().replace("-", "")
        val alice = TestDevice(
            context = context,
            userId = "alice-" + suffix,
            deviceId = "sas-alice-" + suffix.take(12),
            storeId = "sas-a-" + suffix.take(20),
            databaseName = "sas-a-" + suffix + ".db",
        )
        val bob = TestDevice(
            context = context,
            userId = "bob-" + suffix,
            deviceId = "sas-bob-" + suffix.take(12),
            storeId = "sas-b-" + suffix.take(20),
            databaseName = "sas-b-" + suffix + ".db",
        )
        val bundlesByUser = mapOf(
            alice.userId to listOf(alice.bundle),
            bob.userId to listOf(bob.bundle),
        )
        val relay = InMemorySasRelay()
        private val aliceDirectory = TestDirectory(alice.userId, bundlesByUser)
        private val bobDirectory = TestDirectory(bob.userId, bundlesByUser)

        init {
            alice.coordinator = alice.createCoordinator(aliceDirectory, relay.client(alice.userId))
            bob.coordinator = bob.createCoordinator(bobDirectory, relay.client(bob.userId))
        }

        suspend fun cancelBoth(transactionId: String) {
            runCatching { alice.coordinator.cancel(transactionId) }
            runCatching { bob.coordinator.cancel(transactionId) }
        }

        override fun close() {
            alice.close()
            bob.close()
        }
    }

    private class TestDevice(
        private val context: Context,
        val userId: String,
        deviceId: String,
        storeId: String,
        private val databaseName: String,
    ) : AutoCloseable {
        private val stateStore = CryptoStateStore(context, storeId)
        val bundle: PublicDeviceKeyBundle = SasVerificationCoordinatorTest.createBundle(stateStore, deviceId)
        val identity = TestIdentity(bundle)
        val trust = RecordingTrustStore()
        private val database = Room.databaseBuilder(
            context,
            LocalChatDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        val ledger = SasVerificationMessageLedger(
            database.sasVerificationMessageDao(),
            userId,
            bundle.deviceId,
        )
        lateinit var coordinator: SasVerificationCoordinator

        fun createCoordinator(
            directory: DeviceIdentityTransferDirectory,
            transport: SasVerificationTransport,
        ) = SasVerificationCoordinator(
            localUserId = userId,
            identityRepository = identity,
            directoryRepository = directory,
            transport = transport,
            ledger = ledger,
            trustedDeviceRepository = trust,
            clock = { FIXED_NOW },
        )

        override fun close() {
            database.close()
            context.deleteDatabase(databaseName)
            stateStore.delete()
        }
    }

    private class TestIdentity(
        private val bundle: PublicDeviceKeyBundle,
    ) : SasVerificationLocalIdentity {
        override fun ensurePublicKeyBundle(): PublicDeviceKeyBundle = bundle
        override fun requireIdentityUsable() = Unit
    }

    private class RecordingTrustStore : SasVerificationTrustStore {
        private val records = mutableSetOf<Triple<String, String, String>>()

        override fun markVerified(
            peerUserId: String,
            peerDeviceId: String,
            peerEd25519IdentityKey: String,
            verifiedAtMillis: Long,
        ) {
            records += Triple(peerUserId, peerDeviceId, peerEd25519IdentityKey)
        }

        fun contains(record: Triple<String, String, String>): Boolean = record in records
    }

    private class TestDirectory(
        override val authenticatedUserId: String,
        private val bundles: Map<String, List<PublicDeviceKeyBundle>>,
    ) : DeviceIdentityTransferDirectory {
        override suspend fun publishMyDevice(bundle: PublicDeviceKeyBundle) = Unit
        override suspend fun getUserDevices(uid: String): List<PublicDeviceKeyBundle> =
            bundles[uid].orEmpty()
        override suspend fun removeMyDevice(deviceId: String) = Unit
    }

    private class InMemorySasRelay {
        private val lock = Any()
        private val pending = mutableListOf<SasVerificationMessage>()
        private val acknowledged = mutableSetOf<String>()

        fun client(accountId: String): SasVerificationTransport =
            object : SasVerificationTransport {
                override suspend fun upload(message: SasVerificationMessage) {
                    require(message.senderUserId == accountId)
                    synchronized(lock) {
                        val existing = pending.firstOrNull { it.deliveryId == message.deliveryId }
                        if (existing == null) {
                            pending += message
                        } else {
                            check(existing == message) {
                                "A SAS delivery ID cannot be reused with different content"
                            }
                        }
                    }
                }

                override suspend fun readInbox(deviceId: String): List<SasVerificationInboxItem> =
                    synchronized(lock) {
                        pending.filter {
                            it.recipientUserId == accountId && it.recipientDeviceId == deviceId
                        }.sortedBy { it.createdAtMillis }
                            .map { SasVerificationInboxItem(it.deliveryId, it.senderUserId, it) }
                    }

                override suspend fun acknowledge(deviceId: String, deliveryId: String) {
                    synchronized(lock) {
                        val matching = pending.firstOrNull {
                            it.deliveryId == deliveryId &&
                                it.recipientUserId == accountId &&
                                it.recipientDeviceId == deviceId
                        }
                        if (matching != null) {
                            pending.remove(matching)
                            acknowledged += deliveryId
                        }
                    }
                }
            }

        fun inject(message: SasVerificationMessage) = synchronized(lock) {
            pending += message
        }

        fun wasAcknowledged(deliveryId: String): Boolean = synchronized(lock) {
            deliveryId in acknowledged
        }

        fun contains(deliveryId: String): Boolean = synchronized(lock) {
            pending.any { it.deliveryId == deliveryId }
        }

        fun isEmpty(): Boolean = synchronized(lock) { pending.isEmpty() }
    }

    private companion object {
        const val FIXED_NOW = 1_800_000_000_000L

        fun createBundle(
            store: CryptoStateStore,
            deviceId: String,
        ): PublicDeviceKeyBundle {
            val root = JSONObject(
                requireNotNull(NativeCryptoBridge.nativeEnsureDeviceIdentity(store, deviceId)) {
                    "Native test identity could not be created"
                }
            )
            val identity = root.getJSONObject("identityKeys")
            val fallback = root.getJSONObject("fallbackKey")
            val oneTimeKeys = root.getJSONArray("oneTimeKeys")
            return PublicDeviceKeyBundle(
                deviceId = deviceId,
                ed25519IdentityKey = identity.getString("ed25519"),
                curve25519IdentityKey = identity.getString("curve25519"),
                curve25519IdentityKeySignature = identity.getString("curve25519Signature"),
                fallbackKey = SignedOlmPreKey(
                    keyKind = fallback.getString("keyKind"),
                    keyId = fallback.getString("keyId"),
                    publicKey = fallback.getString("key"),
                    signature = fallback.getString("signature"),
                ),
                oneTimeKeys = (0 until oneTimeKeys.length()).map { index ->
                    val key = oneTimeKeys.getJSONObject(index)
                    SignedOlmPreKey(
                        keyKind = key.getString("keyKind"),
                        keyId = key.getString("keyId"),
                        publicKey = key.getString("key"),
                        signature = key.getString("signature"),
                    )
                },
            )
        }
    }
}
