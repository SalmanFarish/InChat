package com.example.inchat.crypto

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Durable, non-secret state for a device-identity handoff.
 *
 * The journal never stores transfer keys or Olm state. After export, the source
 * remains locked until the destination is published and the old directory entry
 * has been revoked. A retired record is a permanent local tombstone.
 */
internal class DeviceTransferJournal(
    context: Context,
    storageDirectory: File? = null,
) {
    private val directory = storageDirectory
        ?: File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(directory, FILE_NAME))

    data class PendingTransfer(
        val sourceDeviceId: String,
        val targetDeviceId: String,
        val transactionId: String?,
        val ownerUserId: String? = null,
        val sourceIdentityFingerprint: String? = null,
        val exported: Boolean = false,
        val acknowledged: Boolean = false,
        val retiring: Boolean = false,
        val retired: Boolean = false,
        val receiptSha256: String? = null,
    )

    fun pending(): PendingTransfer? = synchronized(LOCK) {
        val encoded = try {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            return@synchronized null
        }

        val json = try {
            JSONObject(encoded)
        } catch (failure: Exception) {
            throw IOException("Device transfer journal is malformed", failure)
        }
        val schema = json.optInt("schemaVersion")
        if (schema != LEGACY_SCHEMA_VERSION && schema != SCHEMA_VERSION) {
            throw IOException("Device transfer journal has an unsupported schema")
        }

        val status = json.optString("status")
        if (status !in ALL_STATUSES) {
            throw IOException("Device transfer journal has an unsupported state")
        }
        if (schema == LEGACY_SCHEMA_VERSION && status in setOf(STATUS_RETIRING, STATUS_RETIRED)) {
            throw IOException("Legacy device transfer journal has an unsupported state")
        }

        val source = json.optString("sourceDeviceId")
        val target = json.optString("targetDeviceId")
        if (!DEVICE_ID_REGEX.matches(source) ||
            !DEVICE_ID_REGEX.matches(target) ||
            source == target
        ) {
            throw IOException("Device transfer journal contains invalid device IDs")
        }

        // Earlier schema-1 records remain locked. They do not contain the account
        // and identity binding required for automated retirement.
        val transactionId = json.optString("transactionId").takeIf { it.isNotBlank() }
        if (transactionId != null && !TRANSACTION_ID_REGEX.matches(transactionId)) {
            throw IOException("Device transfer journal contains an invalid transaction ID")
        }
        val ownerUserId = json.optString("ownerUserId").takeIf { it.isNotBlank() }
        val fingerprint = json.optString("sourceIdentityFingerprint").takeIf { it.isNotBlank() }
        if (ownerUserId != null && !USER_ID_REGEX.matches(ownerUserId)) {
            throw IOException("Device transfer journal contains an invalid account ID")
        }
        if (fingerprint != null && !FINGERPRINT_REGEX.matches(fingerprint)) {
            throw IOException("Device transfer journal contains an invalid identity fingerprint")
        }
        val receiptSha256 = json.optString("receiptSha256").takeIf { it.isNotBlank() }
        if (receiptSha256 != null && !FINGERPRINT_REGEX.matches(receiptSha256)) {
            throw IOException("Device transfer journal contains an invalid receipt digest")
        }

        val exported = status != STATUS_PENDING
        val acknowledged = status in setOf(STATUS_ACKNOWLEDGED, STATUS_RETIRING, STATUS_RETIRED)
        val retiring = status in setOf(STATUS_RETIRING, STATUS_RETIRED)
        val retired = status == STATUS_RETIRED
        if (exported && transactionId == null) {
            throw IOException("Exported device transfer has no transaction ID")
        }
        if (acknowledged && receiptSha256 == null) {
            throw IOException("Acknowledged device transfer has no receipt digest")
        }
        if (schema == SCHEMA_VERSION &&
            (ownerUserId == null || fingerprint == null)
        ) {
            throw IOException("Device transfer journal is missing its account or identity binding")
        }
        if (retiring && (!acknowledged || transactionId == null)) {
            throw IOException("Retiring device transfer has no verified acknowledgement")
        }
        PendingTransfer(
            sourceDeviceId = source,
            targetDeviceId = target,
            transactionId = transactionId,
            ownerUserId = ownerUserId,
            sourceIdentityFingerprint = fingerprint,
            exported = exported,
            acknowledged = acknowledged,
            retiring = retiring,
            retired = retired,
            receiptSha256 = receiptSha256,
        )
    }

    /** Persist the source lock, account binding and identity fingerprint before export. */
    fun begin(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        ownerUserId: String,
        sourceIdentityFingerprint: String,
    ) = synchronized(LOCK) {
        validateIds(sourceDeviceId, targetDeviceId)
        require(TRANSACTION_ID_REGEX.matches(transactionId)) {
            "Device transfer transaction ID has an invalid format"
        }
        require(USER_ID_REGEX.matches(ownerUserId)) { "Account ID has an invalid format" }
        require(FINGERPRINT_REGEX.matches(sourceIdentityFingerprint)) {
            "Source identity fingerprint has an invalid format"
        }
        val next = PendingTransfer(
            sourceDeviceId = sourceDeviceId,
            targetDeviceId = targetDeviceId,
            transactionId = transactionId,
            ownerUserId = ownerUserId,
            sourceIdentityFingerprint = sourceIdentityFingerprint,
        )
        val existing = pending()
        if (existing != null) {
            check(existing == next) { "Another device transfer is already pending" }
            return@synchronized
        }
        write(
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("status", STATUS_PENDING)
                .put("sourceDeviceId", sourceDeviceId)
                .put("targetDeviceId", targetDeviceId)
                .put("transactionId", transactionId)
                .put("ownerUserId", ownerUserId)
                .put("sourceIdentityFingerprint", sourceIdentityFingerprint)
                .toString(),
        )
    }

    /** Record that encrypted export bytes have been created and may be delivered. */
    fun markExported(sourceDeviceId: String, targetDeviceId: String, transactionId: String) =
        synchronized(LOCK) {
            val existing = requireMatching(sourceDeviceId, targetDeviceId, transactionId)
            check(!existing.exported && !existing.acknowledged) {
                "Device transfer has already been exported"
            }
            writeRecord(existing, STATUS_EXPORTED)
        }

    /** Cancellation is safe only before an export has been created. */
    fun cancel(sourceDeviceId: String) = synchronized(LOCK) {
        require(DEVICE_ID_REGEX.matches(sourceDeviceId)) {
            "Source device ID has an invalid format"
        }
        val existing = pending() ?: return@synchronized
        check(existing.sourceDeviceId == sourceDeviceId) {
            "Pending transfer belongs to a different source device"
        }
        check(!existing.exported && !existing.acknowledged) {
            "A transfer cannot be cancelled after export"
        }
        atomicFile.delete()
    }

    /** Any pending, retiring or retired transfer keeps this installation locked. */
    fun requireUnlocked(sourceDeviceId: String) {
        val existing = pending() ?: return
        val reason = if (existing.retired) "has been retired" else "is locked"
        throw IllegalStateException(
            "Device identity $sourceDeviceId $reason while transfer " +
                "${existing.transactionId ?: "legacy"} is pending",
        )
    }

    fun requirePendingTarget(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
    ) {
        val existing = requireMatching(sourceDeviceId, targetDeviceId, transactionId)
        check(!existing.exported && !existing.acknowledged) {
            "Device transfer is no longer awaiting export"
        }
    }

    /** Require a transfer whose encrypted export may have reached the destination. */
    fun requirePending(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
    ) {
        val existing = requireMatching(sourceDeviceId, targetDeviceId, transactionId)
        check(existing.exported) {
            "Acknowledgement does not match an exported pending transfer"
        }
    }

    /** True only when the exact receipt was already durably accepted. */
    fun hasRecordedAcknowledgement(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        receiptSha256: String,
    ): Boolean = synchronized(LOCK) {
        val existing = requireMatching(sourceDeviceId, targetDeviceId, transactionId)
        if (!existing.acknowledged) return@synchronized false
        existing.receiptSha256 == receiptSha256
    }

    /** Persist that the source verified the destination's authenticated receipt. */
    fun markAcknowledged(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        receiptSha256: String,
    ) = synchronized(LOCK) {
        require(FINGERPRINT_REGEX.matches(receiptSha256)) {
            "Acknowledgement digest has an invalid format"
        }
        val existing = requireMatching(sourceDeviceId, targetDeviceId, transactionId)
        if (existing.acknowledged) {
            check(existing.receiptSha256 == receiptSha256) {
                "A different acknowledgement was already recorded for this transfer"
            }
            return@synchronized
        }
        check(existing.exported) {
            "Acknowledgement does not match an exported pending transfer"
        }
        writeRecord(existing.copy(acknowledged = true, receiptSha256 = receiptSha256), STATUS_ACKNOWLEDGED)
    }

    /** Persist the irreversible retirement decision before touching local keys. */
    fun markRetiring(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
    ) = synchronized(LOCK) {
        val existing = requireMatching(sourceDeviceId, targetDeviceId, transactionId)
        check(existing.acknowledged) {
            "Source retirement requires a verified destination acknowledgement"
        }
        if (existing.retiring) return@synchronized
        writeRecord(existing, STATUS_RETIRING)
    }

    /** Mark the source terminally retired after its local state has been deleted. */
    fun markRetired(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
    ) = synchronized(LOCK) {
        val existing = requireMatching(sourceDeviceId, targetDeviceId, transactionId)
        check(existing.retiring) {
            "Source retirement was not started"
        }
        if (existing.retired) return@synchronized
        writeRecord(existing, STATUS_RETIRED)
    }

    private fun requireMatching(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
    ): PendingTransfer {
        val existing = pending()
        check(
            existing != null &&
                existing.sourceDeviceId == sourceDeviceId &&
                existing.targetDeviceId == targetDeviceId &&
                existing.transactionId == transactionId
        ) {
            "Device transfer does not match the pending transaction"
        }
        return requireNotNull(existing)
    }

    private fun writeRecord(record: PendingTransfer, status: String) {
        write(
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("status", status)
                .put("sourceDeviceId", record.sourceDeviceId)
                .put("targetDeviceId", record.targetDeviceId)
                .put("transactionId", record.transactionId)
                .put("ownerUserId", record.ownerUserId)
                .put("sourceIdentityFingerprint", record.sourceIdentityFingerprint)
                .apply {
                    record.receiptSha256?.let { put("receiptSha256", it) }
                }
                .toString(),
        )
    }

    private fun write(encoded: String) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create device transfer journal directory")
        }
        val output = atomicFile.startWrite()
        try {
            output.write(encoded.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    private fun validateIds(source: String, target: String) {
        require(DEVICE_ID_REGEX.matches(source) && DEVICE_ID_REGEX.matches(target)) {
            "Device ID has an invalid format"
        }
        require(source != target) { "A device identity cannot be transferred to itself" }
    }

    private companion object {
        const val DIRECTORY_NAME = "inchat-crypto"
        const val FILE_NAME = "device-transfer.json"
        const val LEGACY_SCHEMA_VERSION = 1
        const val SCHEMA_VERSION = 2
        const val STATUS_PENDING = "pending"
        const val STATUS_EXPORTED = "exported"
        const val STATUS_ACKNOWLEDGED = "acknowledged"
        const val STATUS_RETIRING = "retiring"
        const val STATUS_RETIRED = "retired"
        val ALL_STATUSES = setOf(
            STATUS_PENDING,
            STATUS_EXPORTED,
            STATUS_ACKNOWLEDGED,
            STATUS_RETIRING,
            STATUS_RETIRED,
        )
        val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        val USER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,128}")
        val TRANSACTION_ID_REGEX = Regex("[0-9a-f]{32}")
        val FINGERPRINT_REGEX = Regex("[0-9a-f]{64}")
        val LOCK = Any()
    }
}
