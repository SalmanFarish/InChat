package com.example.inchat.crypto

import org.json.JSONArray
import org.json.JSONObject
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await

/**
 * Publishes and retrieves public Olm device bundles through Firebase Realtime
 * Database. Firebase is an untrusted directory/transport: callers must verify
 * the Ed25519 signatures before using any returned key to establish a session.
 *
 * This repository deliberately does not create sessions, consume one-time keys,
 * or connect itself to normal app startup and messaging.
 */
internal class DeviceKeyDirectoryRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    /**
     * Publish this installation's public bundle under the authenticated account.
     * Private identity material and serialized Olm state are never included.
     */
    suspend fun publishMyDevice(bundle: PublicDeviceKeyBundle) {
        val uid = requireNotNull(auth.currentUser?.uid) {
            "Sign in before publishing a device identity"
        }
        validateBundle(bundle)
        verifyCryptographicSignatures(bundle)

        database.reference
            .child(ROOT_NODE)
            .child(uid)
            .child(bundle.deviceId)
            .setValue(bundle.toDirectoryMap())
            .await()
    }

    /** Read the public device directory for a user. Requires Firebase auth. */
    suspend fun getUserDevices(uid: String): List<PublicDeviceKeyBundle> {
        requireNotNull(auth.currentUser) { "Sign in before reading device identities" }
        require(UID_REGEX.matches(uid)) { "User ID has an invalid format" }

        val snapshot = database.reference
            .child(ROOT_NODE)
            .child(uid)
            .get()
            .await()

        return snapshot.children.mapNotNull { child ->
            parseDirectoryEntry(child)
        }.sortedBy { it.deviceId }
    }

    /** Remove only this installation's public directory entry. */
    suspend fun removeMyDevice(deviceId: String) {
        val uid = requireNotNull(auth.currentUser?.uid) {
            "Sign in before removing a device identity"
        }
        require(DEVICE_ID_REGEX.matches(deviceId)) {
            "Device ID has an invalid format"
        }

        database.reference
            .child(ROOT_NODE)
            .child(uid)
            .child(deviceId)
            .removeValue()
            .await()
    }

    private fun parseDirectoryEntry(snapshot: DataSnapshot): PublicDeviceKeyBundle {
        val schemaVersion = snapshot.child("schemaVersion").getValue(Int::class.javaObjectType) ?: 0
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported public device-key bundle version"
        }

        val deviceId = snapshot.child("deviceId").getValue(String::class.java).orEmpty()
        require(deviceId == snapshot.key && DEVICE_ID_REGEX.matches(deviceId)) {
            "Device directory entry has an invalid device ID"
        }

        val bundle = PublicDeviceKeyBundle(
            deviceId = deviceId,
            ed25519IdentityKey = snapshot.child("ed25519IdentityKey")
                .getValue(String::class.java).orEmpty(),
            curve25519IdentityKey = snapshot.child("curve25519IdentityKey")
                .getValue(String::class.java).orEmpty(),
            curve25519IdentityKeySignature = snapshot.child("curve25519IdentityKeySignature")
                .getValue(String::class.java).orEmpty(),
            fallbackKey = snapshot.child("fallbackKey").takeIf { it.exists() }
                ?.let(::parsePrekey),
            oneTimeKeys = snapshot.child("oneTimeKeys").children.map(::parsePrekey),
        )
        validateBundle(bundle)
        verifyCryptographicSignatures(bundle)
        return bundle
    }

    private fun parsePrekey(snapshot: DataSnapshot): SignedOlmPreKey =
        SignedOlmPreKey(
            keyKind = snapshot.child("keyKind").getValue(String::class.java).orEmpty(),
            keyId = snapshot.child("keyId").getValue(String::class.java).orEmpty(),
            publicKey = snapshot.child("publicKey").getValue(String::class.java).orEmpty(),
            signature = snapshot.child("signature").getValue(String::class.java).orEmpty(),
        ).also { key ->
            require(key.keyKind == "one_time" || key.keyKind == "fallback") {
                "Device directory contains an unknown prekey kind"
            }
            require(
                key.keyId.isNotBlank() &&
                    key.publicKey.isNotBlank() &&
                    key.signature.isNotBlank(),
            ) {
                "Device directory contains an incomplete signed prekey"
            }
        }

    /**
     * The directory is untrusted. Verify the identity-to-Curve25519 binding and
     * every signed prekey inside Rust before exposing a bundle to session code.
     */
    private fun verifyCryptographicSignatures(bundle: PublicDeviceKeyBundle) {
        val identityKeys = JSONObject()
            .put("ed25519", bundle.ed25519IdentityKey)
            .put("curve25519", bundle.curve25519IdentityKey)
            .put("curve25519Signature", bundle.curve25519IdentityKeySignature)

        fun SignedOlmPreKey.toNativeJson(): JSONObject = JSONObject()
            .put("keyKind", keyKind)
            .put("keyId", keyId)
            .put("key", publicKey)
            .put("signature", signature)

        val oneTimeKeys = JSONArray().apply {
            bundle.oneTimeKeys.forEach { put(it.toNativeJson()) }
        }
        val encoded = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("deviceId", bundle.deviceId)
            .put("identityKeys", identityKeys)
            .put("fallbackKey", requireNotNull(bundle.fallbackKey).toNativeJson())
            .put("oneTimeKeys", oneTimeKeys)
            .toString()

        check(NativeCryptoBridge.nativeVerifyDeviceKeyBundle(encoded)) {
            "Device directory bundle failed cryptographic signature verification"
        }
    }

    private fun validateBundle(bundle: PublicDeviceKeyBundle) {
        require(DEVICE_ID_REGEX.matches(bundle.deviceId)) {
            "Device ID has an invalid format"
        }
        require(
            bundle.ed25519IdentityKey.isNotBlank() &&
                bundle.curve25519IdentityKey.isNotBlank() &&
                bundle.curve25519IdentityKeySignature.isNotBlank(),
        ) {
            "Device identity bundle is incomplete"
        }
        require(bundle.fallbackKey?.keyKind == "fallback") {
            "Device identity bundle has no valid fallback key"
        }
        require(bundle.oneTimeKeys.size <= MAX_ONE_TIME_KEYS) {
            "Device identity bundle contains too many one-time keys"
        }
        require(bundle.oneTimeKeys.all { it.keyKind == "one_time" }) {
            "Device identity bundle contains an invalid one-time key"
        }
    }

    private fun PublicDeviceKeyBundle.toDirectoryMap(): Map<String, Any> = mapOf(
        "schemaVersion" to SCHEMA_VERSION,
        "deviceId" to deviceId,
        "ed25519IdentityKey" to ed25519IdentityKey,
        "curve25519IdentityKey" to curve25519IdentityKey,
        "curve25519IdentityKeySignature" to curve25519IdentityKeySignature,
        "fallbackKey" to requireNotNull(fallbackKey).toDirectoryMap(),
        "oneTimeKeys" to oneTimeKeys.map { it.toDirectoryMap() },
        "publishedAt" to ServerValue.TIMESTAMP,
    )

    private fun SignedOlmPreKey.toDirectoryMap(): Map<String, String> = mapOf(
        "keyKind" to keyKind,
        "keyId" to keyId,
        "publicKey" to publicKey,
        "signature" to signature,
    )

    private companion object {
        const val ROOT_NODE = "deviceKeys"
        const val SCHEMA_VERSION = 1
        const val MAX_ONE_TIME_KEYS = 20
        val UID_REGEX = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
