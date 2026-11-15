package com.example.inchat.crypto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Stores verified peer-device identities locally in an Android Keystore AES-GCM
 * encrypted file. Firebase is not used as an authority for trust decisions.
 */
internal class TrustedDeviceRepository(context: Context) {
    private val stateStore = CryptoStateStore(
        context.applicationContext,
        STATE_ID,
    )

    fun markVerified(
        peerUserId: String,
        peerDeviceId: String,
        peerEd25519IdentityKey: String,
        verifiedAtMillis: Long,
    ) = synchronized(LOCK) {
        validateIdentifiers(peerUserId, peerDeviceId)
        require(peerEd25519IdentityKey.isNotBlank()) { "Peer identity key is missing" }
        require(verifiedAtMillis >= 0L) { "Verification time is invalid" }

        val fingerprint = fingerprint(peerEd25519IdentityKey)
        val records = readRecords().filterNot {
            it.peerUserId == peerUserId && it.peerDeviceId == peerDeviceId
        }.toMutableList()
        records += TrustedDeviceRecord(
            peerUserId = peerUserId,
            peerDeviceId = peerDeviceId,
            identityKeyFingerprint = fingerprint,
            verifiedAtMillis = verifiedAtMillis,
        )
        writeRecords(records)
    }

    /** Returns the verification time only when the currently published key matches. */
    fun verifiedAtMillis(
        peerUserId: String,
        peerDeviceId: String,
        peerEd25519IdentityKey: String,
    ): Long? = synchronized(LOCK) {
        validateIdentifiers(peerUserId, peerDeviceId)
        if (peerEd25519IdentityKey.isBlank()) return@synchronized null
        val expectedFingerprint = fingerprint(peerEd25519IdentityKey)
        readRecords().firstOrNull {
            it.peerUserId == peerUserId &&
                it.peerDeviceId == peerDeviceId &&
                it.identityKeyFingerprint == expectedFingerprint
        }?.verifiedAtMillis
    }

    /** Revocation is local to this installation and removes the stored trust decision. */
    fun revoke(peerUserId: String, peerDeviceId: String): Boolean = synchronized(LOCK) {
        validateIdentifiers(peerUserId, peerDeviceId)
        val records = readRecords()
        val remaining = records.filterNot {
            it.peerUserId == peerUserId && it.peerDeviceId == peerDeviceId
        }
        if (remaining.size == records.size) {
            false
        } else {
            writeRecords(remaining)
            true
        }
    }

    private fun readRecords(): List<TrustedDeviceRecord> {
        val plaintext = stateStore.read() ?: return emptyList()
        return try {
            val root = JSONObject(String(plaintext, Charsets.UTF_8))
            require(root.getInt("schemaVersion") == SCHEMA_VERSION) {
                "Unsupported trusted-device store version"
            }
            val array = root.getJSONArray("devices")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val record = TrustedDeviceRecord(
                        peerUserId = item.getString("peerUserId"),
                        peerDeviceId = item.getString("peerDeviceId"),
                        identityKeyFingerprint = item.getString("identityKeyFingerprint"),
                        verifiedAtMillis = item.getLong("verifiedAtMillis"),
                    )
                    validateIdentifiers(record.peerUserId, record.peerDeviceId)
                    require(FINGERPRINT_REGEX.matches(record.identityKeyFingerprint))
                    require(record.verifiedAtMillis >= 0L)
                    add(record)
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeRecords(records: List<TrustedDeviceRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("peerUserId", record.peerUserId)
                    .put("peerDeviceId", record.peerDeviceId)
                    .put("identityKeyFingerprint", record.identityKeyFingerprint)
                    .put("verifiedAtMillis", record.verifiedAtMillis)
            )
        }
        val plaintext = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("devices", array)
            .toString()
            .toByteArray(Charsets.UTF_8)
        try {
            stateStore.write(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validateIdentifiers(userId: String, deviceId: String) {
        require(USER_ID_REGEX.matches(userId)) { "Peer user ID has an invalid format" }
        require(DEVICE_ID_REGEX.matches(deviceId)) { "Peer device ID has an invalid format" }
    }

    private fun fingerprint(identityKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(identityKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class TrustedDeviceRecord(
        val peerUserId: String,
        val peerDeviceId: String,
        val identityKeyFingerprint: String,
        val verifiedAtMillis: Long,
    )

    private companion object {
        const val STATE_ID = "trusted-devices-v1"
        const val SCHEMA_VERSION = 1
        val USER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        val FINGERPRINT_REGEX = Regex("[a-f0-9]{64}")
        val LOCK = Any()
    }
}
