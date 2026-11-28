package com.example.inchat.crypto

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.inchat.data.local.LocalChatDatabase
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OlmGroupKeyDistributionReadinessTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        LocalChatDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun groupCiphertextMustWaitUntilEveryRemoteDeviceAcknowledgesExactEpochKey() {
        val dao = database.olmDeliveryReceiptDao()
        val membership = GroupCryptoMembershipSnapshot(
            groupId = "group-1",
            epoch = 7L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        )
        val keyDistribution = keyDistribution()
        val messageDistribution = messageDistribution()
        val metadataStore = database.olmGroupKeyDistributionMetadataStore()
        metadataStore.persistIfAbsent(
            OlmGroupKeyDistributionRecord(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                distributionId = distributionId(),
                groupId = "group-1",
                membershipEpoch = 7L,
                megolmSessionId = "1234567890abcdef",
                targetDevices = listOf("bob" to "bob-phone", "bob" to "bob-tablet"),
            ),
        )
        val readiness = RoomOlmGroupKeyDistributionReadiness(dao, metadataStore)

        dao.insertIfAbsent(
            receipt(
                deliveryId = "a".repeat(64),
                recipientDeviceId = "bob-phone",
                messageId = distributionId(),
            ),
        )

        var incompleteRejected = false
        try {
            readiness.requireReady(
                "alice",
                "alice-phone",
                membership,
                keyDistribution,
                messageDistribution,
            )
        } catch (_: IllegalArgumentException) {
            incompleteRejected = true
        }
        assertTrue("A partial key distribution must block group ciphertext", incompleteRejected)

        dao.insertIfAbsent(
            receipt(
                deliveryId = "b".repeat(64),
                recipientDeviceId = "bob-tablet",
                messageId = distributionId(),
            ),
        )
        readiness.requireReady(
            "alice",
            "alice-phone",
            membership,
            keyDistribution,
            messageDistribution,
        )
    }


    @Test
    fun readinessAcceptsEveryMailboxUploadWithoutWaitingForClientAcknowledgements() {
        val membership = GroupCryptoMembershipSnapshot(
            groupId = "group-1",
            epoch = 7L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        )
        val distributionId = distributionId()
        val metadataStore = database.olmGroupKeyDistributionMetadataStore()
        metadataStore.persistIfAbsent(
            OlmGroupKeyDistributionRecord(
                ownerUserId = "alice",
                senderDeviceId = "alice-phone",
                distributionId = distributionId,
                groupId = "group-1",
                membershipEpoch = 7L,
                megolmSessionId = "1234567890abcdef",
                targetDevices = listOf("bob" to "bob-phone", "bob" to "bob-tablet"),
            ),
        )
        val queueDao = database.olmDeliveryQueueDao()
        val readiness = RoomOlmGroupKeyDistributionReadiness(
            deliveryReceipts = database.olmDeliveryReceiptDao(),
            metadataStore = metadataStore,
            deliveryStatesForDistribution = { owner, sender, distribution, group, epoch ->
                queueDao.getTargetStatesForDistribution(
                    ownerUserId = owner,
                    senderDeviceId = sender,
                    distributionId = distribution,
                    groupId = group,
                    membershipEpoch = epoch,
                )
            },
        )

        queueDao.insertIfAbsent(
            listOf(mailboxDelivery("a".repeat(64), distributionId, "bob-phone", "UPLOADED")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            readiness.requireMailboxAccepted("alice", "alice-phone", membership, "1234567890abcdef")
        }

        queueDao.insertIfAbsent(
            listOf(mailboxDelivery("b".repeat(64), distributionId, "bob-tablet", "UPLOADED")),
        )
        val token = readiness.requireMailboxAccepted(
            "alice",
            "alice-phone",
            membership,
            "1234567890abcdef",
        )
        assertEquals(setOf("bob" to "bob-phone", "bob" to "bob-tablet"), token.targetDevices)
        assertTrue(database.olmDeliveryReceiptDao()
            .getAcknowledgedTargetIdentitiesForDistribution("alice", "alice-phone", distributionId)
            .isEmpty())
    }

    @Test
    fun distributionMetadataIsDurableImmutableAndIdempotent() {
        val store = database.olmGroupKeyDistributionMetadataStore()
        val record = OlmGroupKeyDistributionRecord(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            distributionId = "group-key-8",
            groupId = "group-1",
            membershipEpoch = 8L,
            megolmSessionId = "abcdef0123456789",
            targetDevices = listOf("bob" to "bob-tablet", "bob" to "bob-phone"),
        )

        store.persistIfAbsent(record)
        store.persistIfAbsent(record.copy(targetDevices = record.targetDevices.reversed()))

        val persisted = store.get("alice", "alice-phone", "group-key-8")
        assertEquals(record.toEntity(), persisted)
        assertEquals(
            listOf("bob" to "bob-phone", "bob" to "bob-tablet"),
            persisted?.targetDevicePairs(),
        )
        assertThrows(IllegalStateException::class.java) {
            store.persistIfAbsent(record.copy(megolmSessionId = "fedcba9876543210"))
        }
    }

    @Test
    fun recoveryScanRejectsPersistedMetadataWithTamperedRecordIdentity() {
        val record = OlmGroupKeyDistributionRecord(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            distributionId = "group-key-tampered",
            groupId = "group-1",
            membershipEpoch = 10L,
            megolmSessionId = "0123456789abcdef",
            targetDevices = listOf("bob" to "bob-phone"),
        )
        database.olmGroupKeyDistributionDao().insertIfAbsent(
            record.toEntity().copy(recordId = "forged-record-id"),
        )

        val store = database.olmGroupKeyDistributionMetadataStore()
        assertThrows(IllegalArgumentException::class.java) {
            store.getAllForDevice("alice", "alice-phone")
        }
    }

    @Test
    fun distributionMetadataIsScopedToAccountAndSenderDevice() {
        val store = database.olmGroupKeyDistributionMetadataStore()
        val record = OlmGroupKeyDistributionRecord(
            ownerUserId = "alice",
            senderDeviceId = "alice-phone",
            distributionId = "group-key-9",
            groupId = "group-1",
            membershipEpoch = 9L,
            megolmSessionId = "0123456789abcdef",
            targetDevices = listOf("bob" to "bob-phone"),
        )
        store.persistIfAbsent(record)

        assertEquals(null, store.get("mallory", "alice-phone", "group-key-9"))
        assertEquals(null, store.get("alice", "alice-tablet", "group-key-9"))
        assertEquals(listOf(record.toEntity()), store.getAllForDevice("alice", "alice-phone"))
    }


    private fun mailboxDelivery(
        deliveryId: String,
        messageId: String,
        recipientDeviceId: String,
        status: String,
    ) = OlmDeviceDeliveryEntity(
        deliveryId = deliveryId,
        messageId = messageId,
        recipientUserId = "bob",
        recipientDeviceId = recipientDeviceId,
        ownerUserId = "alice",
        senderDeviceId = "alice-phone",
        groupId = "group-1",
        membershipEpoch = 7L,
        conversationId = "group-1",
        conversationPeerUserId = null,
        envelope = "ciphertext-envelope",
        status = status,
        attemptCount = 1,
        nextAttemptAtMillis = 0L,
        leaseUntilMillis = 0L,
        lastError = null,
    )

    private fun receipt(
        deliveryId: String,
        recipientDeviceId: String,
        messageId: String,
    ) = OlmDeliveryReceiptEntity(
        deliveryId = deliveryId,
        ownerUserId = "alice",
        senderDeviceId = "alice-phone",
        messageId = messageId,
        recipientUserId = "bob",
        recipientDeviceId = recipientDeviceId,
        acknowledgedAtMillis = 1_800_000_000_000L,
    )

    private fun distributionId() = OlmGroupSessionKeyDistributionId.create(
        membership = GroupCryptoMembershipSnapshot(
            groupId = "group-1",
            epoch = 7L,
            members = mapOf("alice" to "admin", "bob" to "member"),
        ),
        senderUserId = "alice",
        senderDeviceId = "alice-phone",
        megolmSessionId = "1234567890abcdef",
    )

    private fun keyDistribution() = OlmGroupKeyDistributionReadinessToken(
        ownerUserId = "alice",
        senderDeviceId = "alice-phone",
        distributionId = distributionId(),
        groupId = "group-1",
        membershipEpoch = 7L,
        megolmSessionId = "1234567890abcdef",
        targetDevices = setOf("bob" to "bob-phone", "bob" to "bob-tablet"),
    )

    private fun messageDistribution() = PreparedOlmGroupMessageDistribution(
        messageId = "group-message-1",
        groupId = "group-1",
        membershipEpoch = 7L,
        megolmSessionId = "1234567890abcdef",
        deliveries = listOf(
            messageDelivery("bob-phone", "session-phone"),
            messageDelivery("bob-tablet", "session-tablet"),
        ),
        skippedUntrustedDeviceCount = 0,
    )

    private fun keyDelivery(deviceId: String, sessionId: String) =
        PreparedOlmGroupKeyDelivery(
            recipientUserId = "bob",
            recipientDeviceId = deviceId,
            sessionId = sessionId,
            envelope = envelope(deviceId, sessionId),
        )

    private fun messageDelivery(deviceId: String, sessionId: String) =
        PreparedOlmGroupMessageDelivery(
            recipientUserId = "bob",
            recipientDeviceId = deviceId,
            sessionId = sessionId,
            envelope = envelope(deviceId, sessionId),
        )

    private fun envelope(deviceId: String, sessionId: String) =
        EncryptedOlmEnvelopeCodec.encode(
            EncryptedOlmEnvelope(
                senderDeviceId = "alice-phone",
                recipientDeviceId = deviceId,
                sessionId = sessionId,
                ciphertext = ByteArray(32) { it.toByte() },
            ),
        )
}
