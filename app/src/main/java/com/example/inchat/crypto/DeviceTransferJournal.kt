package com.example.inchat.crypto

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Durable local state for the source side of a device-identity handoff.
 *
 * This journal contains routing metadata only, never keys or Olm state. A pending
 * transfer is fail-closed across process death: identity use stays blocked until
 * the handoff is explicitly cancelled or a later authenticated completion flow
 * is implemented. It intentionally has no "complete" or delete operation yet.
 */
internal class DeviceTransferJournal(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)
    private val atomicFile = AtomicFile(File(directory, FILE_NAME))

    data class PendingTransfer(
        val sourceDeviceId: String,
        val targetDeviceId: String,
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
        if (json.optInt("schemaVersion") != SCHEMA_VERSION ||
            json.optString("status") != STATUS_PENDING
        ) {
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
        PendingTransfer(source, target)
    }

    /** Persist the source lock before any transfer export can occur. */
    fun begin(sourceDeviceId: String, targetDeviceId: String) = synchronized(LOCK) {
        validateIds(sourceDeviceId, targetDeviceId)
        val existing = pending()
        if (existing != null) {
            check(existing == PendingTransfer(sourceDeviceId, targetDeviceId)) {
                "Another device transfer is already pending"
            }
            return@synchronized
        }
        write(
            JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("status", STATUS_PENDING)
                .put("sourceDeviceId", sourceDeviceId)
                .put("targetDeviceId", targetDeviceId)
                .toString(),
        )
    }

    /** Unlock only the matching source transfer; never clears another transfer. */
    fun cancel(sourceDeviceId: String) = synchronized(LOCK) {
        require(DEVICE_ID_REGEX.matches(sourceDeviceId)) {
            "Source device ID has an invalid format"
        }
        val existing = pending() ?: return@synchronized
        check(existing.sourceDeviceId == sourceDeviceId) {
            "Pending transfer belongs to a different source device"
        }
        atomicFile.delete()
    }

    fun requireUnlocked(sourceDeviceId: String) {
        val existing = pending() ?: return
        check(existing == null) {
            "This device identity is locked while a transfer is pending"
        }
    }

    fun requirePendingTarget(sourceDeviceId: String, targetDeviceId: String) {
        val existing = pending()
        check(existing == PendingTransfer(sourceDeviceId, targetDeviceId)) {
            "Begin a device transfer to this destination before exporting"
        }
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
        val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        val LOCK = Any()
    }
}
