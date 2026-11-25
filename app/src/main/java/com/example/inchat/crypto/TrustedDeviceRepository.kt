package com.example.inchat.crypto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Checks and records the local trust state of a published device identity. */
internal fun interface OlmDeviceTrustVerifier {
    /** True only after both users completed the SAS comparison for this exact key. */
    fun isVerified(
        peerUserId: String,
        peerDeviceId: String,
        peerEd25519IdentityKey: String,
    ): Boolean

    /**
     * Messaging may use a stable first-seen identity without forcing a SAS ceremony.
     * Implementations pin a new key once and reject a changed key. The default
     * keeps existing test doubles and strict policies fail-closed.
     */
    fun isTrustedForMessaging(
        peerUserId: String,
        peerDeviceId: String,
        peerEd25519IdentityKey: String,
    ): Boolean = isVerified(peerUserId, peerDeviceId, peerEd25519IdentityKey)
}

/**
 * Stores peer identity pins and SAS confirmations locally in an Android Keystore
 * AES-GCM encrypted file. Firebase is never an authority for trust decisions.
 *
 * An identity first encountered during ordinary messaging is pinned locally
 * (trust on first use). A later key change is rejected until the user completes
 * SAS verification for the new identity. First-use pinning is not displayed as
 * SAS verification and never creates a verifiedAt timestamp.
 */
internal class TrustedDeviceRepository(context: Context) : OlmDeviceTrustVerifier, SasVerificationTrustStore {
    private val stateStore = CryptoStateStore(
        context.applicationContext,
        STATE_ID,
    )

    override fun markVerified(
        peerUserId: String,
        peerDeviceId: String,
        peerEd25519IdentityKey: String,
        verifiedAtMillis: Long,
    ) = synchronized(LOCK) {
        validateIdentifiers(peerUserId, peerDeviceId)
        require(peerEd25519IdentityKey.isNotBlank()) { "Peer identity key is missing" }
        require(verifiedAtMillis >= 0L) { "Verification time is invalid" }

        val keyFingerprint = fingerprint(peerEd25519IdentityKey)
        val records = readRecords()
        val previous = records.firstOrNull {
            it.peerUserId == peerUserId &&
                it.peerDeviceId == peerDeviceId &&
                it.identityKeyFingerprint == keyFingerprint
        }
        val updated = records.filterNot {
            it.peerUserId == peerUserId && it.peerDeviceId == peerDeviceId
        }.toMutableList()
        updated += TrustedDeviceRecord(
            peerUserId = peerUserId,
            peerDeviceId = peerDeviceId,
            identityKeyFingerprint = keyFingerprint,
            firstSeenAtMillis = previous?.firstSeenAtMillis ?: verifiedAtMillis,
            verifiedAtMillis = verifiedAtMillis,
            revokedAtMillis = null,
        )
        writeRecords(updated)
        TRUST_CHANGES.tryEmit(Unit)
        Unit
    }

    /**
     * Pin the first observed identity for ordinary messaging. Existing pins are
     * accepted only while the published Ed25519 identity key remains unchanged.
     * A changed key is deliberately not overwritten here; it requires an explicit
     * SAS comparison before a new identity can become trusted.
     */
    override fun isTrustedForMessaging(
        peerUserId: String,
        peerDeviceId: String,
        peerEd25519IdentityKey: String,
    ): Boolean = synchronized(LOCK) {
        validateIdentifiers(peerUserId, peerDeviceId)
        require(peerEd25519IdentityKey.isNotBlank()) { "Peer identity key is missing" }
        val expectedFingerprint = fingerprint(peerEd25519IdentityKey)
        val records = readRecords()
        val existing = records.firstOrNull {
            it.peerUserId == peerUserId && it.peerDeviceId == peerDeviceId
        }

        when {
            existing == null -> {
                writeRecords(
                    records + TrustedDeviceRecord(
                        peerUserId = peerUserId,
                        peerDeviceId = peerDeviceId,
                        identityKeyFingerprint = expectedFingerprint,
                        firstSeenAtMillis = System.currentTimeMillis().coerceAtLeast(0L),
                        verifiedAtMillis = null,
                        revokedAtMillis = null,
                    )
                )
                TRUST_CHANGES.tryEmit(Unit)
                true
            }
            existing.identityKeyFingerprint == expectedFingerprint && existing.revokedAtMillis == null -> true
            else -> false
        }
    }

    /**
     * Read trust status without creating a first-use pin. This is safe for UI
     * display: opening Chat Info must never itself alter the messaging trust store.
     */
    fun messagingTrustStatus(
        peerUserId: String,
        peerDeviceId: String,
        peerEd25519IdentityKey: String,
    ): DeviceTrustStatus = synchronized(LOCK) {
        validateIdentifiers(peerUserId, peerDeviceId)
        require(peerEd25519IdentityKey.isNotBlank()) { "Peer identity key is missing" }
        val expectedFingerprint = fingerprint(peerEd25519IdentityKey)
        val existing = readRecords().firstOrNull {
            it.peerUserId == peerUserId && it.peerDeviceId == peerDeviceId
        }
        when {
            existing == null -> DeviceTrustStatus.NOT_SEEN
            existing.identityKeyFingerprint != expectedFingerprint ||
                existing.revokedAtMillis != null -> DeviceTrustStatus.BLOCKED_OR_CHANGED
            existing.verifiedAtMillis != null -> DeviceTrustStatus.SAS_VERIFIED
            else -> DeviceTrustStatus.FIRST_USE_PINNED
        }
    }

    /** Observe local pin/verification/revocation changes without exposing key material. */
    fun observeTrustChanges(): Flow<Unit> = TRUST_CHANGES

    /** Returns the verification time only after SAS confirmed this exact identity key. */
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
                it.identityKeyFingerprint == expectedFingerprint &&
                it.revokedAtMillis == null
        }?.verifiedAtMillis
    }

    /** SAS verification status is intentionally distinct from first-use messaging trust. */
    override fun isVerified(
        peerUserId: String,
        peerDeviceId: String,
        peerEd25519IdentityKey: String,
    ): Boolean = verifiedAtMillis(peerUserId, peerDeviceId, peerEd25519IdentityKey) != null

    /** Revocation is a persistent local block; messaging cannot silently TOFU-pin it again. */
    fun revoke(peerUserId: String, peerDeviceId: String): Boolean = synchronized(LOCK) {
        validateIdentifiers(peerUserId, peerDeviceId)
        val records = readRecords()
        val existing = records.firstOrNull {
            it.peerUserId == peerUserId && it.peerDeviceId == peerDeviceId
        }
        if (existing == null || existing.revokedAtMillis != null) {
            false
        } else {
            val revokedAt = System.currentTimeMillis().coerceAtLeast(0L)
            writeRecords(records.map { record ->
                if (record.peerUserId == peerUserId && record.peerDeviceId == peerDeviceId) {
                    record.copy(verifiedAtMillis = null, revokedAtMillis = revokedAt)
                } else {
                    record
                }
            })
            TRUST_CHANGES.tryEmit(Unit)
            true
        }
    }

    private fun readRecords(): List<TrustedDeviceRecord> {
        val plaintext = stateStore.read() ?: return emptyList()
        return try {
            val root = JSONObject(String(plaintext, Charsets.UTF_8))
            val version = root.getInt("schemaVersion")
            require(version in LEGACY_SCHEMA_VERSION..SCHEMA_VERSION) {
                "Unsupported trusted-device store version"
            }
            val array = root.getJSONArray("devices")
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val verifiedAt = if (
                        version == LEGACY_SCHEMA_VERSION ||
                        (item.has("verifiedAtMillis") && !item.isNull("verifiedAtMillis"))
                    ) {
                        item.getLong("verifiedAtMillis")
                    } else {
                        null
                    }
                    val revokedAt = if (
                        version >= SCHEMA_VERSION && item.has("revokedAtMillis") && !item.isNull("revokedAtMillis")
                    ) item.getLong("revokedAtMillis") else null
                    val record = TrustedDeviceRecord(
                        peerUserId = item.getString("peerUserId"),
                        peerDeviceId = item.getString("peerDeviceId"),
                        identityKeyFingerprint = item.getString("identityKeyFingerprint"),
                        firstSeenAtMillis = if (version == LEGACY_SCHEMA_VERSION) {
                            requireNotNull(verifiedAt)
                        } else {
                            item.getLong("firstSeenAtMillis")
                        },
                        verifiedAtMillis = verifiedAt,
                        revokedAtMillis = revokedAt,
                    )
                    validateIdentifiers(record.peerUserId, record.peerDeviceId)
                    require(FINGERPRINT_REGEX.matches(record.identityKeyFingerprint))
                    require(record.firstSeenAtMillis >= 0L)
                    require(record.verifiedAtMillis == null || record.verifiedAtMillis >= 0L)
                    require(record.revokedAtMillis == null || record.revokedAtMillis >= 0L)
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
            val item = JSONObject()
                .put("peerUserId", record.peerUserId)
                .put("peerDeviceId", record.peerDeviceId)
                .put("identityKeyFingerprint", record.identityKeyFingerprint)
                .put("firstSeenAtMillis", record.firstSeenAtMillis)
            record.verifiedAtMillis?.let { item.put("verifiedAtMillis", it) }
            record.revokedAtMillis?.let { item.put("revokedAtMillis", it) }
            array.put(item)
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
        val firstSeenAtMillis: Long,
        val verifiedAtMillis: Long?,
        val revokedAtMillis: Long?,
    )

    private companion object {
        const val STATE_ID = "trusted-devices-v1"
        const val LEGACY_SCHEMA_VERSION = 1
        const val SCHEMA_VERSION = 3
        val USER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        val FINGERPRINT_REGEX = Regex("[a-f0-9]{64}")
        val LOCK = Any()
        val TRUST_CHANGES = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    }
}
