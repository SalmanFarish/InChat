package com.example.inchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.inchat.crypto.DecryptedOlmMessage
import com.example.inchat.crypto.EncryptedOlmMessageDao
import com.example.inchat.crypto.EncryptedOlmMessageEntity
import com.example.inchat.crypto.EncryptedOlmMessageVault
import com.example.inchat.crypto.OlmDeliveryQueueDao
import com.example.inchat.crypto.OlmDeliveryReceiptDao
import com.example.inchat.crypto.OlmDeliveryReceiptEntity
import com.example.inchat.crypto.OlmDeviceDeliveryEntity
import com.example.inchat.crypto.OlmInboundReceiptDao
import com.example.inchat.crypto.OlmInboundReceiptEntity
import com.example.inchat.crypto.SasVerificationMessageDao
import com.example.inchat.crypto.SasVerificationMessageEntity

@Database(
    entities = [CachedMessageEntity::class, OlmDeviceDeliveryEntity::class, OlmDeliveryReceiptEntity::class, OlmInboundReceiptEntity::class, SasVerificationMessageEntity::class, EncryptedOlmMessageEntity::class],
    version = 11,
    exportSchema = false,
)
abstract class LocalChatDatabase : RoomDatabase() {
    abstract fun messageDao(): LocalChatDao
    abstract fun olmDeliveryQueueDao(): OlmDeliveryQueueDao
    abstract fun olmDeliveryReceiptDao(): OlmDeliveryReceiptDao
    abstract fun olmInboundReceiptDao(): OlmInboundReceiptDao
    abstract fun encryptedOlmMessageDao(): EncryptedOlmMessageDao
    abstract fun sasVerificationMessageDao(): SasVerificationMessageDao

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

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sas_verification_messages (
                        deliveryId TEXT NOT NULL, transactionId TEXT NOT NULL,
                        senderUserId TEXT NOT NULL, senderDeviceId TEXT NOT NULL,
                        recipientUserId TEXT NOT NULL, recipientDeviceId TEXT NOT NULL,
                        type TEXT NOT NULL, payloadSha256 TEXT NOT NULL,
                        expiresAtMillis INTEGER NOT NULL, receivedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(deliveryId)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sas_verification_messages_transactionId_senderUserId_senderDeviceId_type ON sas_verification_messages(transactionId, senderUserId, senderDeviceId, type)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sas_verification_messages_expiresAtMillis ON sas_verification_messages(expiresAtMillis)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE sas_verification_messages ADD COLUMN processedAtMillis INTEGER"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE olm_device_deliveries ADD COLUMN ownerUserId TEXT")
                database.execSQL("ALTER TABLE olm_device_deliveries ADD COLUMN senderDeviceId TEXT")
                database.execSQL("DROP INDEX IF EXISTS index_olm_device_deliveries_messageId_recipientUserId_recipientDeviceId")
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_olm_device_deliveries_ownerUserId_senderDeviceId_status_nextAttemptAtMillis_leaseUntilMillis " +
                        "ON olm_device_deliveries(ownerUserId, senderDeviceId, status, nextAttemptAtMillis, leaseUntilMillis)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_olm_device_deliveries_ownerUserId_senderDeviceId_messageId_recipientUserId_recipientDeviceId " +
                        "ON olm_device_deliveries(ownerUserId, senderDeviceId, messageId, recipientUserId, recipientDeviceId)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS olm_delivery_receipts (
                        deliveryId TEXT NOT NULL, ownerUserId TEXT NOT NULL,
                        senderDeviceId TEXT NOT NULL, messageId TEXT NOT NULL,
                        recipientUserId TEXT NOT NULL, recipientDeviceId TEXT NOT NULL,
                        acknowledgedAtMillis INTEGER NOT NULL, PRIMARY KEY(deliveryId)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_olm_delivery_receipts_ownerUserId_senderDeviceId_recipientUserId_messageId " +
                        "ON olm_delivery_receipts(ownerUserId, senderDeviceId, recipientUserId, messageId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_olm_delivery_receipts_messageId " +
                        "ON olm_delivery_receipts(messageId)"
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS encrypted_olm_messages (
                        ownerUserId TEXT NOT NULL,
                        conversationId TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        conversationPeerUserId TEXT NOT NULL,
                        senderUserId TEXT NOT NULL,
                        senderDeviceId TEXT NOT NULL,
                        recipientUserId TEXT NOT NULL,
                        recipientDeviceId TEXT NOT NULL,
                        sentAtMillis INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        PRIMARY KEY(ownerUserId, conversationId, messageId)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_encrypted_olm_messages_ownerUserId_conversationId_sentAtMillis " +
                        "ON encrypted_olm_messages(ownerUserId, conversationId, sentAtMillis)"
                )
            }
        }

        /**
         * Re-encrypt every version-10 plaintext text column before replacing the table.
         * Room runs migrations transactionally; if a row cannot be authenticated/encrypted,
         * the migration fails and the original v10 table remains intact for recovery.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS encrypted_olm_messages_new")
                database.execSQL(
                    """
                    CREATE TABLE encrypted_olm_messages_new (
                        ownerUserId TEXT NOT NULL,
                        conversationId TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        conversationPeerUserId TEXT NOT NULL,
                        senderUserId TEXT NOT NULL,
                        senderDeviceId TEXT NOT NULL,
                        recipientUserId TEXT NOT NULL,
                        recipientDeviceId TEXT NOT NULL,
                        sentAtMillis INTEGER NOT NULL,
                        contentEncryptionVersion INTEGER NOT NULL,
                        textNonceBase64 TEXT NOT NULL,
                        textCiphertextBase64 TEXT NOT NULL,
                        PRIMARY KEY(ownerUserId, conversationId, messageId)
                    )
                    """.trimIndent()
                )

                val vault = EncryptedOlmMessageVault()
                database.query(
                    """
                    SELECT ownerUserId, conversationId, messageId, conversationPeerUserId,
                           senderUserId, senderDeviceId, recipientUserId, recipientDeviceId,
                           sentAtMillis, text
                    FROM encrypted_olm_messages
                    """.trimIndent()
                ).use { cursor ->
                    val ownerIndex = cursor.getColumnIndexOrThrow("ownerUserId")
                    val conversationIndex = cursor.getColumnIndexOrThrow("conversationId")
                    val messageIndex = cursor.getColumnIndexOrThrow("messageId")
                    val peerIndex = cursor.getColumnIndexOrThrow("conversationPeerUserId")
                    val senderIndex = cursor.getColumnIndexOrThrow("senderUserId")
                    val senderDeviceIndex = cursor.getColumnIndexOrThrow("senderDeviceId")
                    val recipientIndex = cursor.getColumnIndexOrThrow("recipientUserId")
                    val recipientDeviceIndex = cursor.getColumnIndexOrThrow("recipientDeviceId")
                    val timestampIndex = cursor.getColumnIndexOrThrow("sentAtMillis")
                    val textIndex = cursor.getColumnIndexOrThrow("text")

                    while (cursor.moveToNext()) {
                        val plaintextMessage = DecryptedOlmMessage(
                            ownerUserId = cursor.getString(ownerIndex),
                            conversationId = cursor.getString(conversationIndex),
                            messageId = cursor.getString(messageIndex),
                            conversationPeerUserId = cursor.getString(peerIndex),
                            senderUserId = cursor.getString(senderIndex),
                            senderDeviceId = cursor.getString(senderDeviceIndex),
                            recipientUserId = cursor.getString(recipientIndex),
                            recipientDeviceId = cursor.getString(recipientDeviceIndex),
                            sentAtMillis = cursor.getLong(timestampIndex),
                            text = cursor.getString(textIndex),
                        )
                        val encrypted = vault.encrypt(plaintextMessage)
                        database.execSQL(
                            """
                            INSERT INTO encrypted_olm_messages_new (
                                ownerUserId, conversationId, messageId, conversationPeerUserId,
                                senderUserId, senderDeviceId, recipientUserId, recipientDeviceId,
                                sentAtMillis, contentEncryptionVersion, textNonceBase64,
                                textCiphertextBase64
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf(
                                encrypted.ownerUserId,
                                encrypted.conversationId,
                                encrypted.messageId,
                                encrypted.conversationPeerUserId,
                                encrypted.senderUserId,
                                encrypted.senderDeviceId,
                                encrypted.recipientUserId,
                                encrypted.recipientDeviceId,
                                encrypted.sentAtMillis,
                                encrypted.contentEncryptionVersion,
                                encrypted.textNonceBase64,
                                encrypted.textCiphertextBase64,
                            ),
                        )
                    }
                }

                database.execSQL("DROP TABLE encrypted_olm_messages")
                database.execSQL(
                    "ALTER TABLE encrypted_olm_messages_new RENAME TO encrypted_olm_messages"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_encrypted_olm_messages_ownerUserId_conversationId_sentAtMillis " +
                        "ON encrypted_olm_messages(ownerUserId, conversationId, sentAtMillis)"
                )
            }
        }

        @Volatile private var INSTANCE: LocalChatDatabase? = null

        fun getInstance(context: Context): LocalChatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, LocalChatDatabase::class.java, "inchat-local.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build().also { INSTANCE = it }
            }
    }
}
