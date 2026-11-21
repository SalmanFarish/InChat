package com.example.inchat.crypto

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import java.util.concurrent.Callable

/** Shared upper bound for group-key fan-out, persistence, and recovery. */
internal const val MAX_OLM_GROUP_KEY_DISTRIBUTION_TARGETS = 500

/** Non-secret, immutable description of one group-key distribution. */
@Entity(tableName = "olm_group_key_distributions", indices = [Index(value = ["ownerUserId", "senderDeviceId", "groupId", "membershipEpoch"])])
data class OlmGroupKeyDistributionEntity(
    @PrimaryKey val recordId: String,
    val ownerUserId: String,
    val senderDeviceId: String,
    val distributionId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val megolmSessionId: String,
    /** Canonical sorted user/device pairs; IDs cannot contain comma or slash. */
    val targetDevices: String,
) {
    /** Decode and validate the persisted canonical target set for recovery. */
    internal fun targetDevicePairs(): List<Pair<String, String>> {
        require(targetDevices.isNotBlank()) { "Persisted group-key target set is empty" }
        require(targetDevices.count { it == ',' } < MAX_OLM_GROUP_KEY_DISTRIBUTION_TARGETS) { "Persisted group-key target set exceeds the device limit" }
        val decoded = targetDevices.split(',').map { item ->
            val separator = item.indexOf('/')
            require(separator > 0 && separator == item.lastIndexOf('/') && separator < item.lastIndex) {
                "Persisted group-key target entry is malformed"
            }
            val userId = item.substring(0, separator)
            val deviceId = item.substring(separator + 1)
            require(USER_ID.matches(userId) && DEVICE_ID.matches(deviceId)) {
                "Persisted group-key target identity is invalid"
            }
            userId to deviceId
        }
        val canonical = decoded.distinct().sortedWith(
            compareBy<Pair<String, String>> { it.first }.thenBy { it.second },
        )
        require(decoded == canonical) { "Persisted group-key target set is not canonical" }
        return decoded
    }

    /** Reject tampered identity fields before a caller uses this row for recovery. */
    internal fun requireValidForRecovery() {
        val canonical = OlmGroupKeyDistributionRecord(
            ownerUserId = ownerUserId,
            senderDeviceId = senderDeviceId,
            distributionId = distributionId,
            groupId = groupId,
            membershipEpoch = membershipEpoch,
            megolmSessionId = megolmSessionId,
            targetDevices = targetDevicePairs(),
        ).toEntity()
        require(canonical == this) {
            "Persisted group-key distribution identity or metadata is invalid"
        }
    }

    private companion object {
        val USER_ID = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

@Dao
interface OlmGroupKeyDistributionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(record: OlmGroupKeyDistributionEntity): Long

    @Query("SELECT * FROM olm_group_key_distributions WHERE recordId = :recordId LIMIT 1")
    fun get(recordId: String): OlmGroupKeyDistributionEntity?

    @Query("SELECT * FROM olm_group_key_distributions WHERE ownerUserId = :ownerUserId AND senderDeviceId = :senderDeviceId ORDER BY groupId ASC, membershipEpoch ASC, distributionId ASC")
    fun getAllForDevice(ownerUserId: String, senderDeviceId: String): List<OlmGroupKeyDistributionEntity>
}

internal data class OlmGroupKeyDistributionRecord(
    val ownerUserId: String,
    val senderDeviceId: String,
    val distributionId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val megolmSessionId: String,
    val targetDevices: List<Pair<String, String>>,
) {
    internal fun toEntity(): OlmGroupKeyDistributionEntity {
        val sorted = targetDevices.distinct().sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        require(sorted.size == targetDevices.size && sorted.isNotEmpty()) { "Group-key targets must be non-empty and unique" }
        require(sorted.size <= MAX_OLM_GROUP_KEY_DISTRIBUTION_TARGETS) { "Group-key targets exceed the device delivery limit" }
        require(sorted.all { (user, device) -> USER_ID.matches(user) && DEVICE_ID.matches(device) })
        require(USER_ID.matches(ownerUserId) && DEVICE_ID.matches(senderDeviceId))
        require(ID.matches(distributionId) && ID.matches(groupId) && SESSION_ID.matches(megolmSessionId) && membershipEpoch >= 0L)
        return OlmGroupKeyDistributionEntity(
            recordId = recordId(ownerUserId, senderDeviceId, distributionId),
            ownerUserId = ownerUserId, senderDeviceId = senderDeviceId, distributionId = distributionId,
            groupId = groupId, membershipEpoch = membershipEpoch, megolmSessionId = megolmSessionId,
            targetDevices = sorted.joinToString(",") { "${it.first}/${it.second}" },
        )
    }

    companion object {
        internal fun recordId(owner: String, device: String, distribution: String) = "$owner:$device:$distribution"
        private val USER_ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        private val ID = Regex("[A-Za-z0-9_-]{1,128}")
        private val SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
    }
}

internal interface OlmGroupKeyDistributionMetadataStore {
    fun persistIfAbsent(record: OlmGroupKeyDistributionRecord)
    fun get(ownerUserId: String, senderDeviceId: String, distributionId: String): OlmGroupKeyDistributionEntity?
    fun getAllForDevice(ownerUserId: String, senderDeviceId: String): List<OlmGroupKeyDistributionEntity>
}

internal class RoomOlmGroupKeyDistributionMetadataStore(
    private val database: RoomDatabase,
    private val dao: OlmGroupKeyDistributionDao,
) : OlmGroupKeyDistributionMetadataStore {
    override fun persistIfAbsent(record: OlmGroupKeyDistributionRecord) {
        val entity = record.toEntity()
        database.runInTransaction(Callable {
            dao.insertIfAbsent(entity)
            val persisted = requireNotNull(dao.get(entity.recordId)) { "Group-key metadata insert was not durable" }
            check(persisted == entity) { "Stable group-key distribution ID reused with conflicting metadata" }
        })
    }
    override fun get(ownerUserId: String, senderDeviceId: String, distributionId: String) =
        dao.get(OlmGroupKeyDistributionRecord.recordId(ownerUserId, senderDeviceId, distributionId))
            ?.also { it.requireValidForRecovery() }

    override fun getAllForDevice(ownerUserId: String, senderDeviceId: String) =
        dao.getAllForDevice(ownerUserId, senderDeviceId).onEach { it.requireValidForRecovery() }
}