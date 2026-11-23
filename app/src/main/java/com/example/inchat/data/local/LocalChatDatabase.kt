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
import com.example.inchat.crypto.EncryptedGroupMessageDao
import com.example.inchat.crypto.EncryptedGroupMessageEntity
import com.example.inchat.crypto.EncryptedGroupMessageReceiptEntity
import com.example.inchat.crypto.EncryptedGroupPendingMessageEntity
import com.example.inchat.crypto.OlmDeliveryQueueDao
import com.example.inchat.crypto.OlmDeliveryReceiptDao
import com.example.inchat.crypto.OlmDeliveryReceiptEntity
import com.example.inchat.crypto.OlmDeviceDeliveryEntity
import com.example.inchat.crypto.EncryptedGroupOutgoingCommitEntity
import com.example.inchat.crypto.EncryptedDirectOutgoingCommitEntity
import com.example.inchat.crypto.OlmGroupKeyDistributionDao
import com.example.inchat.crypto.OlmGroupKeyDistributionEntity
import com.example.inchat.crypto.OlmGroupKeyDistributionMetadataStore
import com.example.inchat.crypto.RoomOlmGroupKeyDistributionMetadataStore
import com.example.inchat.crypto.RoomOlmGroupKeyDistributionReadiness
import com.example.inchat.crypto.OlmInboundReceiptDao
import com.example.inchat.crypto.OlmInboundReceiptEntity
import com.example.inchat.crypto.SasVerificationMessageDao
import com.example.inchat.crypto.SasVerificationMessageEntity

@Database(
    entities = [CachedMessageEntity::class, OlmDeviceDeliveryEntity::class, OlmDeliveryReceiptEntity::class, OlmInboundReceiptEntity::class, SasVerificationMessageEntity::class, EncryptedOlmMessageEntity::class, EncryptedGroupMessageEntity::class, EncryptedGroupMessageReceiptEntity::class, EncryptedGroupPendingMessageEntity::class, OlmGroupKeyDistributionEntity::class, EncryptedGroupOutgoingCommitEntity::class, EncryptedDirectOutgoingCommitEntity::class],
    version = 20,
    exportSchema = false,
)
abstract class LocalChatDatabase : RoomDatabase() {
    abstract fun messageDao(): LocalChatDao
    abstract fun olmDeliveryQueueDao(): OlmDeliveryQueueDao
    abstract fun olmDeliveryReceiptDao(): OlmDeliveryReceiptDao
    abstract fun olmInboundReceiptDao(): OlmInboundReceiptDao
    abstract fun olmGroupKeyDistributionDao(): OlmGroupKeyDistributionDao

    /** One database-backed metadata store shared by key distribution and readiness checks. */
    internal fun olmGroupKeyDistributionMetadataStore(): OlmGroupKeyDistributionMetadataStore =
        RoomOlmGroupKeyDistributionMetadataStore(this, olmGroupKeyDistributionDao())

    /** Fail-closed Room verifier using both the immutable distribution ledger and delivery receipts. */
    internal fun olmGroupKeyDistributionReadiness(): RoomOlmGroupKeyDistributionReadiness =
        RoomOlmGroupKeyDistributionReadiness(olmDeliveryReceiptDao(), olmGroupKeyDistributionMetadataStore())
    abstract fun encryptedOlmMessageDao(): EncryptedOlmMessageDao
    abstract fun encryptedGroupMessageDao(): EncryptedGroupMessageDao
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
        internal val MIGRATION_10_11 = object : Migration(10, 11) {
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

        /** Separate E2EE group content and a durable Megolm replay ledger. */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS encrypted_group_messages (
                        ownerUserId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        membershipEpoch INTEGER NOT NULL,
                        senderUserId TEXT NOT NULL,
                        senderDeviceId TEXT NOT NULL,
                        megolmSessionId TEXT NOT NULL,
                        messageIndex INTEGER NOT NULL,
                        sentAtMillis INTEGER NOT NULL,
                        contentEncryptionVersion INTEGER NOT NULL,
                        textNonceBase64 TEXT NOT NULL,
                        textCiphertextBase64 TEXT NOT NULL,
                        PRIMARY KEY(ownerUserId, groupId, messageId)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_encrypted_group_messages_ownerUserId_groupId_sentAtMillis " +
                        "ON encrypted_group_messages(ownerUserId, groupId, sentAtMillis)"
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS encrypted_group_message_receipts (
                        ownerUserId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        senderUserId TEXT NOT NULL,
                        senderDeviceId TEXT NOT NULL,
                        megolmSessionId TEXT NOT NULL,
                        messageIndex INTEGER NOT NULL,
                        membershipEpoch INTEGER NOT NULL,
                        messageId TEXT NOT NULL,
                        sentAtMillis INTEGER NOT NULL,
                        receiptId TEXT NOT NULL,
                        ciphertextSha256 TEXT NOT NULL,
                        receivedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(ownerUserId, groupId, senderUserId, senderDeviceId, megolmSessionId, messageIndex)
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_encrypted_group_message_receipts_receiptId " +
                        "ON encrypted_group_message_receipts(receiptId)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_encrypted_group_message_receipts_ownerUserId_groupId_receivedAtMillis " +
                        "ON encrypted_group_message_receipts(ownerUserId, groupId, receivedAtMillis)"
                )
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS olm_group_key_distributions (recordId TEXT NOT NULL, ownerUserId TEXT NOT NULL, senderDeviceId TEXT NOT NULL, distributionId TEXT NOT NULL, groupId TEXT NOT NULL, membershipEpoch INTEGER NOT NULL, megolmSessionId TEXT NOT NULL, targetDevices TEXT NOT NULL, PRIMARY KEY(recordId))")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_olm_group_key_distributions_ownerUserId_senderDeviceId_groupId_membershipEpoch ON olm_group_key_distributions(ownerUserId, senderDeviceId, groupId, membershipEpoch)")
            }
        }

        /** Account-scope inbound ciphertext receipts without guessing owners for legacy rows. */
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Old rows were captured against an installation-scoped device ID. That
                // installation may have switched accounts, so assigning an owner would be unsafe.
                // Keep recipientUserId NULL: scoped processor queries will not adopt these rows.
                database.execSQL("ALTER TABLE olm_inbound_receipts ADD COLUMN recipientUserId TEXT")
                database.execSQL(
                    "DROP INDEX IF EXISTS index_olm_inbound_receipts_senderUserId_senderDeviceId_messageId_recipientDeviceId"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_olm_inbound_receipts_recipientUserId_senderUserId_senderDeviceId_messageId_recipientDeviceId " +
                        "ON olm_inbound_receipts(recipientUserId, senderUserId, senderDeviceId, messageId, recipientDeviceId)"
                )
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS encrypted_group_pending_messages (
                        ownerUserId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        sentAtMillis INTEGER NOT NULL,
                        contentEncryptionVersion INTEGER NOT NULL,
                        textNonceBase64 TEXT NOT NULL,
                        textCiphertextBase64 TEXT NOT NULL,
                        PRIMARY KEY(ownerUserId, groupId, messageId)
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_encrypted_group_pending_messages_ownerUserId_groupId_sentAtMillis " +
                        "ON encrypted_group_pending_messages(ownerUserId, groupId, sentAtMillis)"
                )
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS encrypted_group_outgoing_commits (
                        ownerUserId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        senderDeviceId TEXT NOT NULL,
                        membershipEpoch INTEGER NOT NULL,
                        megolmSessionId TEXT NOT NULL,
                        messageIndex INTEGER NOT NULL,
                        sentAtMillis INTEGER NOT NULL,
                        recipientDeviceCount INTEGER NOT NULL,
                        PRIMARY KEY(ownerUserId, groupId, messageId)
                    )
                """.trimIndent())
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_encrypted_group_outgoing_commits_ownerUserId_senderDeviceId " +
                        "ON encrypted_group_outgoing_commits(ownerUserId, senderDeviceId)"
                )
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE olm_device_deliveries ADD COLUMN groupId TEXT")
                database.execSQL("ALTER TABLE olm_device_deliveries ADD COLUMN membershipEpoch INTEGER")
            }
        }

        /**
         * Backfill conversation scope for already-queued group ciphertext using
         * the immutable group commit/distribution ledgers. Rows without one
         * unambiguous durable group identity remain unscoped and are handled
         * fail-closed by the delivery worker.
         */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    UPDATE olm_device_deliveries
                    SET groupId = (
                        SELECT group_commit.groupId
                        FROM encrypted_group_outgoing_commits AS group_commit
                        WHERE group_commit.ownerUserId = olm_device_deliveries.ownerUserId
                          AND group_commit.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND group_commit.messageId = olm_device_deliveries.messageId
                        LIMIT 1
                    ),
                    membershipEpoch = (
                        SELECT group_commit.membershipEpoch
                        FROM encrypted_group_outgoing_commits AS group_commit
                        WHERE group_commit.ownerUserId = olm_device_deliveries.ownerUserId
                          AND group_commit.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND group_commit.messageId = olm_device_deliveries.messageId
                        LIMIT 1
                    )
                    WHERE groupId IS NULL AND membershipEpoch IS NULL
                      AND (
                        SELECT COUNT(*)
                        FROM encrypted_group_outgoing_commits AS group_commit
                        WHERE group_commit.ownerUserId = olm_device_deliveries.ownerUserId
                          AND group_commit.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND group_commit.messageId = olm_device_deliveries.messageId
                      ) = 1
                      AND NOT EXISTS (
                        SELECT 1
                        FROM olm_group_key_distributions AS distribution
                        WHERE distribution.ownerUserId = olm_device_deliveries.ownerUserId
                          AND distribution.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND distribution.distributionId = olm_device_deliveries.messageId
                      )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE olm_device_deliveries
                    SET groupId = (
                        SELECT distribution.groupId
                        FROM olm_group_key_distributions AS distribution
                        WHERE distribution.ownerUserId = olm_device_deliveries.ownerUserId
                          AND distribution.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND distribution.distributionId = olm_device_deliveries.messageId
                        LIMIT 1
                    ),
                    membershipEpoch = (
                        SELECT distribution.membershipEpoch
                        FROM olm_group_key_distributions AS distribution
                        WHERE distribution.ownerUserId = olm_device_deliveries.ownerUserId
                          AND distribution.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND distribution.distributionId = olm_device_deliveries.messageId
                        LIMIT 1
                    )
                    WHERE groupId IS NULL AND membershipEpoch IS NULL
                      AND (
                        SELECT COUNT(*)
                        FROM olm_group_key_distributions AS distribution
                        WHERE distribution.ownerUserId = olm_device_deliveries.ownerUserId
                          AND distribution.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND distribution.distributionId = olm_device_deliveries.messageId
                      ) = 1
                      AND NOT EXISTS (
                        SELECT 1
                        FROM encrypted_group_outgoing_commits AS group_commit
                        WHERE group_commit.ownerUserId = olm_device_deliveries.ownerUserId
                          AND group_commit.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND group_commit.messageId = olm_device_deliveries.messageId
                      )
                    """.trimIndent()
                )
            }
        }

        /**
         * Persist the original direct peer/conversation on each device copy.
         * Group scope comes from durable group ledgers. Legacy direct rows are
         * recovered only from one matching encrypted local message; ambiguous
         * rows are left unscoped so the delivery worker fails closed.
         */
        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE olm_device_deliveries ADD COLUMN conversationId TEXT")
                database.execSQL("ALTER TABLE olm_device_deliveries ADD COLUMN conversationPeerUserId TEXT")
                database.execSQL(
                    "UPDATE olm_device_deliveries SET conversationId = groupId " +
                        "WHERE groupId IS NOT NULL AND membershipEpoch IS NOT NULL"
                )
                // A legacy delivery to another account carries an unambiguous
                // direct peer in recipientUserId, provided no durable group
                // message or key-distribution record owns the same message ID.
                database.execSQL(
                    """
                    UPDATE olm_device_deliveries
                    SET conversationId = CASE
                        WHEN ownerUserId < recipientUserId THEN ownerUserId || '_' || recipientUserId
                        ELSE recipientUserId || '_' || ownerUserId
                    END,
                    conversationPeerUserId = recipientUserId
                    WHERE groupId IS NULL AND membershipEpoch IS NULL
                      AND ownerUserId != recipientUserId
                      AND NOT EXISTS (
                        SELECT 1
                        FROM encrypted_group_outgoing_commits AS group_commit
                        WHERE group_commit.ownerUserId = olm_device_deliveries.ownerUserId
                          AND group_commit.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND group_commit.messageId = olm_device_deliveries.messageId
                      )
                      AND NOT EXISTS (
                        SELECT 1
                        FROM olm_group_key_distributions AS distribution
                        WHERE distribution.ownerUserId = olm_device_deliveries.ownerUserId
                          AND distribution.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND distribution.distributionId = olm_device_deliveries.messageId
                      )
                    """.trimIndent()
                )
                database.execSQL(
                    """
                    UPDATE olm_device_deliveries
                    SET conversationId = (
                        SELECT message.conversationId
                        FROM encrypted_olm_messages AS message
                        WHERE message.ownerUserId = olm_device_deliveries.ownerUserId
                          AND message.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND message.messageId = olm_device_deliveries.messageId
                        LIMIT 1
                    ),
                    conversationPeerUserId = (
                        SELECT message.conversationPeerUserId
                        FROM encrypted_olm_messages AS message
                        WHERE message.ownerUserId = olm_device_deliveries.ownerUserId
                          AND message.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND message.messageId = olm_device_deliveries.messageId
                        LIMIT 1
                    )
                    WHERE groupId IS NULL AND membershipEpoch IS NULL
                      AND (
                        SELECT COUNT(*)
                        FROM encrypted_olm_messages AS message
                        WHERE message.ownerUserId = olm_device_deliveries.ownerUserId
                          AND message.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND message.messageId = olm_device_deliveries.messageId
                      ) = 1
                      AND NOT EXISTS (
                        SELECT 1
                        FROM encrypted_group_outgoing_commits AS group_commit
                        WHERE group_commit.ownerUserId = olm_device_deliveries.ownerUserId
                          AND group_commit.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND group_commit.messageId = olm_device_deliveries.messageId
                      )
                      AND NOT EXISTS (
                        SELECT 1
                        FROM olm_group_key_distributions AS distribution
                        WHERE distribution.ownerUserId = olm_device_deliveries.ownerUserId
                          AND distribution.senderDeviceId = olm_device_deliveries.senderDeviceId
                          AND distribution.distributionId = olm_device_deliveries.messageId
                      )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE TABLE IF NOT EXISTS encrypted_direct_outgoing_commits (ownerUserId TEXT NOT NULL, conversationId TEXT NOT NULL, conversationPeerUserId TEXT NOT NULL, messageId TEXT NOT NULL, senderDeviceId TEXT NOT NULL, sentAtMillis INTEGER NOT NULL, recipientDeviceCount INTEGER NOT NULL, PRIMARY KEY(ownerUserId, conversationId, messageId))")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_encrypted_direct_outgoing_commits_ownerUserId_senderDeviceId ON encrypted_direct_outgoing_commits(ownerUserId, senderDeviceId)")
            }
        }

        @Volatile private var INSTANCE: LocalChatDatabase? = null

        fun getInstance(context: Context): LocalChatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, LocalChatDatabase::class.java, "inchat-local.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
                    .build().also { INSTANCE = it }
            }
    }
}
