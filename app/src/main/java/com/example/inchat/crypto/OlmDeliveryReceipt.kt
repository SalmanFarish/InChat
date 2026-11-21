package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable

/**
 * Durable sender-side proof that a target device staged an encrypted
 * envelope in its local inbound receipt ledger. This includes remote
 * recipients and the sender's own companion devices. The record deliberately
 * excludes both ciphertext and plaintext.
 */
@Entity(
    tableName = "olm_delivery_receipts",
    indices = [
        Index(value = ["ownerUserId", "senderDeviceId", "recipientUserId", "messageId"]),
        Index(value = ["messageId"]),
    ],
)
data class OlmDeliveryReceiptEntity(
    @PrimaryKey val deliveryId: String,
    val ownerUserId: String,
    val senderDeviceId: String,
    val messageId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val acknowledgedAtMillis: Long,
)

@Dao
interface OlmDeliveryReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(receipt: OlmDeliveryReceiptEntity): Long

    @Query("SELECT * FROM olm_delivery_receipts WHERE deliveryId = :deliveryId LIMIT 1")
    fun getByDeliveryId(deliveryId: String): OlmDeliveryReceiptEntity?

    @Query(
        "SELECT DISTINCT messageId FROM olm_delivery_receipts " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND recipientUserId = :recipientUserId ORDER BY messageId ASC"
    )
    fun observeDeliveredMessageIds(
        ownerUserId: String,
        senderDeviceId: String,
        recipientUserId: String,
    ): Flow<List<String>>

    @Query(
        "SELECT DISTINCT messageId FROM olm_delivery_receipts " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND recipientUserId = :recipientUserId ORDER BY messageId ASC"
    )
    fun getDeliveredMessageIds(
        ownerUserId: String,
        senderDeviceId: String,
        recipientUserId: String,
    ): List<String>


    @Query(
        "SELECT recipientDeviceId FROM olm_delivery_receipts " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND recipientUserId = :recipientUserId AND messageId = :messageId " +
            "ORDER BY recipientDeviceId ASC"
    )
    fun getAcknowledgedRecipientDeviceIds(
        ownerUserId: String,
        senderDeviceId: String,
        recipientUserId: String,
        messageId: String,
    ): List<String>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM olm_delivery_receipts " +
            "WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId " +
            "AND recipientUserId = :recipientUserId AND messageId = :messageId)"
    )
    fun observeHasRecipientAcknowledgement(
        ownerUserId: String,
        senderDeviceId: String,
        recipientUserId: String,
        messageId: String,
    ): Flow<Boolean>
}

/** Persists a recipient acknowledgement before its ciphertext outbox row is deleted. */
internal class RoomOlmDeliveryAcknowledgementRecorder(
    private val database: RoomDatabase,
    private val dao: OlmDeliveryReceiptDao,
) {
    suspend fun record(
        acknowledgement: OlmDeliveryAcknowledgement,
        acknowledgedAtMillis: Long,
    ) {
        require(acknowledgedAtMillis >= 0L) { "Acknowledgement time cannot be negative" }
        require(acknowledgement.senderUserId.matches(USER_ID_PATTERN))
        require(acknowledgement.senderDeviceId.matches(DEVICE_ID_PATTERN))
        require(acknowledgement.recipientUserId.matches(USER_ID_PATTERN))
        require(acknowledgement.recipientDeviceId.matches(DEVICE_ID_PATTERN))
        require(acknowledgement.messageId.matches(MESSAGE_ID_PATTERN))
        require(acknowledgement.deliveryId.matches(DELIVERY_ID_PATTERN))

        // Keep sender-owned companion-device receipts as well. Queries for a
        // remote recipient remain scoped by recipientUserId, so these local
        // sync acknowledgements cannot mark the other account as delivered.
        val receipt = OlmDeliveryReceiptEntity(
            deliveryId = acknowledgement.deliveryId,
            ownerUserId = acknowledgement.senderUserId,
            senderDeviceId = acknowledgement.senderDeviceId,
            messageId = acknowledgement.messageId,
            recipientUserId = acknowledgement.recipientUserId,
            recipientDeviceId = acknowledgement.recipientDeviceId,
            acknowledgedAtMillis = acknowledgedAtMillis,
        )
        withContext(Dispatchers.IO) {
            database.runInTransaction(Callable {
                dao.insertIfAbsent(receipt)
            })
        }
    }

    private companion object {
        val USER_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val MESSAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
        val DELIVERY_ID_PATTERN = Regex("[A-Fa-f0-9]{64}")
    }
}
