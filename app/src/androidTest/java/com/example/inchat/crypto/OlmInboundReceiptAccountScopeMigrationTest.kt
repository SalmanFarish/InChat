package com.example.inchat.crypto

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.inchat.data.local.CachedMessageEntity
import com.example.inchat.data.local.LocalChatDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Exact v13 receipt shape, before recipientUserId was added. */
@Entity(
    tableName = "olm_inbound_receipts",
    indices = [
        Index(value = ["senderUserId", "senderDeviceId", "messageId", "recipientDeviceId"], unique = true),
        Index(value = ["status", "receivedAtMillis"]),
    ],
)
data class VersionThirteenOlmInboundReceiptEntity(
    @PrimaryKey val receiptId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val messageId: String,
    val recipientDeviceId: String,
    val envelope: String,
    val envelopeSha256: String,
    val status: String,
    val receivedAtMillis: Long,
    val processedAtMillis: Long?,
)

@Dao
interface VersionThirteenOlmInboundReceiptDao {
    @Insert
    fun insertAll(receipts: List<VersionThirteenOlmInboundReceiptEntity>)
}

@Database(
    entities = [
        CachedMessageEntity::class,
        OlmDeviceDeliveryEntity::class,
        OlmDeliveryReceiptEntity::class,
        VersionThirteenOlmInboundReceiptEntity::class,
        SasVerificationMessageEntity::class,
        EncryptedOlmMessageEntity::class,
        EncryptedGroupMessageEntity::class,
        EncryptedGroupMessageReceiptEntity::class,
        OlmGroupKeyDistributionEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class VersionThirteenInboundReceiptDatabase : RoomDatabase() {
    abstract fun inboundReceipts(): VersionThirteenOlmInboundReceiptDao
}

class OlmInboundReceiptAccountScopeMigrationTest {
    @Test
    fun migrationPreservesLegacyRowsButNeverAssignsThemToAReceivingAccount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "olm-inbound-account-migration-" + UUID.randomUUID() + ".db"
        context.deleteDatabase(name)
        val legacy = Room.databaseBuilder(
            context,
            VersionThirteenInboundReceiptDatabase::class.java,
            name,
        ).allowMainThreadQueries().build()

        val pending = VersionThirteenOlmInboundReceiptEntity(
            receiptId = "legacy-pending",
            senderUserId = "alice",
            senderDeviceId = "sender-device",
            messageId = "legacy-pending-message",
            recipientDeviceId = "receiver-device",
            envelope = "{}",
            envelopeSha256 = "0".repeat(64),
            status = OlmInboundReceiptStatus.PENDING.name,
            receivedAtMillis = 10L,
            processedAtMillis = null,
        )
        val committed = pending.copy(
            receiptId = "legacy-processed",
            messageId = "legacy-processed-message",
            status = OlmInboundReceiptStatus.PROCESSED.name,
            processedAtMillis = 20L,
        )
        try {
            legacy.inboundReceipts().insertAll(listOf(pending, committed))
        } finally {
            legacy.close()
        }

        val upgraded = Room.databaseBuilder(context, LocalChatDatabase::class.java, name)
            .addMigrations(LocalChatDatabase.MIGRATION_13_14)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = upgraded.olmInboundReceiptDao()
            val retainedPending = requireNotNull(dao.getByReceiptId("legacy-pending"))
            val retainedCommitted = requireNotNull(dao.getByReceiptId("legacy-processed"))

            assertNull(retainedPending.recipientUserId)
            assertNull(retainedCommitted.recipientUserId)
            assertEquals(OlmInboundReceiptStatus.PENDING.name, retainedPending.status)
            assertEquals(OlmInboundReceiptStatus.PROCESSED.name, retainedCommitted.status)
            assertEquals(20L, retainedCommitted.processedAtMillis)
            assertTrue(dao.getPending("account-a", 10).isEmpty())
            assertTrue(dao.getPending("account-b", 10).isEmpty())
            assertTrue(dao.getAwaitingFinalization("account-a", 10).isEmpty())
            assertTrue(dao.getAwaitingFinalization("account-b", 10).isEmpty())
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
