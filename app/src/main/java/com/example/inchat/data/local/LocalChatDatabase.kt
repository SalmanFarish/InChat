package com.example.inchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.inchat.crypto.OlmDeliveryQueueDao
import com.example.inchat.crypto.OlmDeviceDeliveryEntity
import com.example.inchat.crypto.OlmInboundReceiptDao
import com.example.inchat.crypto.OlmInboundReceiptEntity

@Database(
    entities = [CachedMessageEntity::class, OlmDeviceDeliveryEntity::class, OlmInboundReceiptEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class LocalChatDatabase : RoomDatabase() {
    abstract fun messageDao(): LocalChatDao
    abstract fun olmDeliveryQueueDao(): OlmDeliveryQueueDao
    abstract fun olmInboundReceiptDao(): OlmInboundReceiptDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE cached_messages ADD COLUMN isPending INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE cached_messages ADD COLUMN systemEvent TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS olm_device_deliveries (
                        deliveryId TEXT NOT NULL, messageId TEXT NOT NULL, recipientUserId TEXT NOT NULL,
                        recipientDeviceId TEXT NOT NULL, envelope TEXT NOT NULL, status TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL, nextAttemptAtMillis INTEGER NOT NULL,
                        leaseUntilMillis INTEGER NOT NULL, lastError TEXT, PRIMARY KEY(deliveryId)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_olm_device_deliveries_status_nextAttemptAtMillis_leaseUntilMillis ON olm_device_deliveries(status, nextAttemptAtMillis, leaseUntilMillis)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_olm_device_deliveries_messageId ON olm_device_deliveries(messageId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_olm_device_deliveries_messageId_recipientUserId_recipientDeviceId ON olm_device_deliveries(messageId, recipientUserId, recipientDeviceId)")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS olm_inbound_receipts (
                        receiptId TEXT NOT NULL, senderUserId TEXT NOT NULL, senderDeviceId TEXT NOT NULL,
                        messageId TEXT NOT NULL, recipientDeviceId TEXT NOT NULL, envelope TEXT NOT NULL,
                        envelopeSha256 TEXT NOT NULL, status TEXT NOT NULL, receivedAtMillis INTEGER NOT NULL,
                        processedAtMillis INTEGER, PRIMARY KEY(receiptId)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_olm_inbound_receipts_senderUserId_senderDeviceId_messageId_recipientDeviceId ON olm_inbound_receipts(senderUserId, senderDeviceId, messageId, recipientDeviceId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_olm_inbound_receipts_status_receivedAtMillis ON olm_inbound_receipts(status, receivedAtMillis)")
            }
        }

        @Volatile private var INSTANCE: LocalChatDatabase? = null

        fun getInstance(context: Context): LocalChatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, LocalChatDatabase::class.java, "inchat-local.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { INSTANCE = it }
            }
    }
}
