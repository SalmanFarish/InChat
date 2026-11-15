package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SasVerificationMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(message: SasVerificationMessageEntity): Long

    @Query("SELECT * FROM sas_verification_messages WHERE deliveryId = :deliveryId LIMIT 1")
    fun getByDeliveryId(deliveryId: String): SasVerificationMessageEntity?

    @Query("DELETE FROM sas_verification_messages WHERE expiresAtMillis <= :nowMillis")
    fun deleteExpired(nowMillis: Long): Int
}
