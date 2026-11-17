package com.example.inchat.crypto

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Durable local state for a device-identity handoff.
 *
 * This journal stores routing/receipt metadata only, never transfer keys or Olm
 * state. Both pending and acknowledged transfers remain locked until a separate,
 * fully implemented source-retirement step is introduced.
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
        val acknowledged: Boolean = false,
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
        if (json.optInt("schemaVersion") != SCHEMA_VERSION) {
            throw IOException("Device transfer journal has an unsupported schema")
        }

        val status = json.optString("status")
        if (status != STATUS_PENDING && status != STATUS_ACKNOWLEDGED) {
            throw IOException("Device transfer journal has an unsupported state")
        }
        val source = json.optString("sourceDeviceId")
        val target = json.optString("targetDeviceId")
        if (!DEVICE_ID_REGEX.matches(source) ||
            !DEVICE_ID_REGEX.matches(target) ||
            source == target
        ) {
            throw IOException("Device transfer journal contains invalid device IDs")
        }

        // Schema-1 journals written by the earlier prototype may not have a
        // transaction ID. They remain locked and can only be cancelled safely.
        val transactionId = json.optString("transactionId").takeIf { it.isNotBlank() }
        if (transactionId != null && !TRANSACTION_ID_REGEX.matches(transactionId)) {
            throw IOException("Device transfer journal contains an invalid transaction ID")
        }
        val receiptSha256 = json.optString("receiptSha256").takeIf { it.isNotBlank() }
        if (receiptSha256 != null && !RECEIPT_HASH_REGEX.matches(receiptSha256)) {
            throw IOException("Device transfer journal contains an invalid receipt digest")
        }
        val acknowledged = status == STATUS_ACKNOWLEDGED
        if (acknowledged && (transactionId == null || receiptSha256 == null)) {
            throw IOException("Acknowledged device transfer has incomplete receipt metadata")
        }

        PendingTransfer(source, target, transactionId, acknowledged, receiptSha256)
    }

    /** Persist the source lock and unique transaction ID before exporting. */
    fun begin(sourceDeviceId: String, targetDeviceId: String, transactionId: String) =
        synchronized(LOCK) {
            validateIds(sourceDeviceId, targetDeviceId)
            require(TRANSACTION_ID_REGEX.matches(transactionId)) {
                "Device transfer transaction ID has an invalid format"
            }
            val next = PendingTransfer(sourceDeviceId, targetDeviceId, transactionId)
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
                    .toString(),
            )
        }

    /** Unlock only a matching, not-yet-acknowledged source transfer. */
    fun cancel(sourceDeviceId: String) = synchronized(LOCK) {
        require(DEVICE_ID_REGEX.matches(sourceDeviceId)) {
            "Source device ID has an invalid format"
        }
        val existing = pending() ?: return@synchronized
        check(existing.sourceDeviceId == sourceDeviceId) {
            "Pending transfer belongs to a different source device"
        }
        check(!existing.acknowledged) {
            "An acknowledged transfer cannot be cancelled"
        }
        atomicFile.delete()
    }

    fun requireUnlocked(sourceDeviceId: String) {
        val existing = pending() ?: return
        throw IllegalStateException(
            "Device identity $sourceDeviceId is locked while transfer " +
                "${existing.transactionId ?: "legacy"} is pending",
        )
    }

    fun requirePendingTarget(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
    ) {
        val existing = pending()
        check(
            existing == PendingTransfer(sourceDeviceId, targetDeviceId, transactionId),
        ) {
            "Export does not match the pending device transfer"
        }
    }

    fun requirePending(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
    ) {
        val existing = pending()
        check(
            existing == PendingTransfer(sourceDeviceId, targetDeviceId, transactionId),
        ) {
            "Acknowledgement does not match the pending device transfer"
        }
    }

    /** Persist that the source verified the destination's authenticated receipt. */
    fun markAcknowledged(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        receiptSha256: String,
    ) = synchronized(LOCK) {
        require(RECEIPT_HASH_REGEX.matches(receiptSha256)) {
            "Acknowledgement digest has an invalid format"
        }
        val existing = pending()
        check(
            existing == PendingTransfer(sourceDeviceId, targetDeviceId, transactionId),
        ) {
            "Acknowledgement does not match the pending device transfer"
        }
        write(
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("status", STATUS_ACKNOWLEDGED)
                .put("sourceDeviceId", sourceDeviceId)
                .put("targetDeviceId", targetDeviceId)
                .put("transactionId", transactionId)
                .put("receiptSha256", receiptSha256)
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
        const val SCHEMA_VERSION = 1
        const val STATUS_PENDING = "pending"
        const val STATUS_ACKNOWLEDGED = "acknowledged"
        val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        val TRANSACTION_ID_REGEX = Regex("[0-9a-f]{32}")
        val RECEIPT_HASH_REGEX = Regex("[0-9a-f]{64}")
        val LOCK = Any()
    }
}
