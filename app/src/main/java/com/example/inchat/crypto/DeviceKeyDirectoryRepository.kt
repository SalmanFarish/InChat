package com.example.inchat.crypto

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Publishes and retrieves public Olm device bundles through Firebase Realtime
 * Database. Firebase is an untrusted directory/transport: callers must verify
 * the Ed25519 signatures before using any returned key to establish a session.
 *
 * This repository deliberately does not create sessions or consume one-time
 * keys as a side effect of ordinary chat messaging. Device publication may
 * refresh only the matching private FCM token registration.
 */
internal class DeviceKeyDirectoryRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val retiredDeviceMailboxPurger: RetiredDeviceMailboxPurger = RetiredDeviceMailboxPurger(),
) : DeviceIdentityTransferDirectory, OlmOutboundKeyDirectory {
    /** Current Firebase account used as the owner of this device directory. */
    override val authenticatedUserId: String
        get() = requireNotNull(auth.currentUser?.uid) {
            "Sign in before using the device key directory"
        }

    /**
     * Publish this installation's public bundle under the authenticated account.
     * Private identity material and serialized Olm state are never included.
     */
    override suspend fun publishMyDevice(bundle: PublicDeviceKeyBundle) {
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

        // Login-time token registration may have run before this Olm identity
        // existed. Refresh the device-specific token mapping as part of
        // publishing the already-created device; token failure must not undo
        // a successful public-key publication.
        registerDeviceScopedFcmToken(uid, bundle.deviceId)
    }

    private suspend fun registerDeviceScopedFcmToken(uid: String, deviceId: String) {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            if (
                token.isBlank() ||
                token.length > MAX_FCM_TOKEN_LENGTH ||
                auth.currentUser?.uid != uid
            ) {
                return
            }

            database.reference
                .child(PRIVATE_USERS_NODE)
                .child(uid)
                .child(DEVICE_FCM_TOKENS_NODE)
                .child(deviceId)
                .setValue(token)
                .await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Could not register the published device's encrypted push token.", error)
        }
    }

    /**
     * Make this installation ready for messaging without rotating its published
     * one-time prekeys on every app launch or message send.
     *
     * Existing, locally recoverable devices are reused and only have their activity
     * and device-scoped push token refreshed. A missing directory entry or local
     * encrypted identity triggers first-time publication. Replenishment is kept as
     * a separate operation so ordinary sends remain idempotent.
     */
    suspend fun ensureMyDevicePublished(
        identityRepository: DeviceIdentityRepository,
        refreshActivity: Boolean = true,
    ): PublicDeviceKeyBundle {
        val ownerUserId = authenticatedUserId
        val deviceId = identityRepository.currentDeviceId()
        require(auth.currentUser?.uid == ownerUserId) {
            "Authenticated account changed while preparing this device identity"
        }

        val existing = getUserDevices(ownerUserId)
            .firstOrNull { it.deviceId == deviceId }
        if (existing != null && identityRepository.hasLocalIdentityState()) {
            // Regular sends only need the existing signed bundle. Startup/device
            // management refreshes activity and the FCM token; doing both RTDB
            // operations for every message adds latency without improving delivery.
            if (refreshActivity) {
                touchMyDevice(deviceId)
                registerDeviceScopedFcmToken(ownerUserId, deviceId)
            }
            require(auth.currentUser?.uid == ownerUserId) {
                "Authenticated account changed while refreshing this device"
            }
            return existing
        }

        return replenishAndPublishMyDevice(identityRepository)
    }

    /**
     * Publish the current unpublished prekey batch, then mark it as published
     * locally. Call only for first-time registration or an intentional refresh,
     * never as a routine per-message readiness check.
     */
    suspend fun replenishAndPublishMyDevice(
        identityRepository: DeviceIdentityRepository,
    ): PublicDeviceKeyBundle {
        val ownerUserId = authenticatedUserId
        val bundle = identityRepository.ensurePublicKeyBundle()
        require(auth.currentUser?.uid == ownerUserId) {
            "Authenticated account changed while preparing this device identity"
        }
        publishMyDevice(bundle)
        require(auth.currentUser?.uid == ownerUserId) {
            "Authenticated account changed while publishing this device identity"
        }
        identityRepository.markPublicKeyBundlePublished()
        return bundle
    }

    /** Read the public device directory for a user. Requires Firebase auth. */
    override suspend fun getUserDevices(uid: String): List<PublicDeviceKeyBundle> {
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

    /** Update the activity timestamp for this installation without rewriting its keys. */
    suspend fun touchMyDevice(deviceId: String) {
        val uid = requireNotNull(auth.currentUser?.uid) {
            "Sign in before updating device activity"
        }
        require(DEVICE_ID_REGEX.matches(deviceId)) { "Device ID has an invalid format" }
        val deviceReference = database.reference
            .child(ROOT_NODE)
            .child(uid)
            .child(deviceId)
        require(deviceReference.get().await().exists()) {
            "This device is not published in the account directory"
        }
        deviceReference.child("lastSeenAt").setValue(ServerValue.TIMESTAMP).await()
    }

    /**
     * Atomically claim a published one-time prekey. Claims live in a separate
     * path so the public directory remains owner-write-only and no server
     * function (paid or otherwise) is required.
     *
     * Returns false when another authenticated client has already claimed it.
     */
    override suspend fun claimOneTimePreKey(
        targetUid: String,
        deviceId: String,
        keyId: String,
        scope: OlmPreKeyClaimScope,
    ): Boolean {
        val claimantUid = requireNotNull(auth.currentUser?.uid) {
            "Sign in before claiming a one-time prekey"
        }
        val claimRecord = scope.toClaimRecord(claimantUid)
        require(UID_REGEX.matches(targetUid)) { "User ID has an invalid format" }
        require(DEVICE_ID_REGEX.matches(deviceId)) { "Device ID has an invalid format" }
        require(KEY_ID_REGEX.matches(keyId)) { "Prekey ID has an invalid format" }

        val publishedKeys = database.reference
            .child(ROOT_NODE)
            .child(targetUid)
            .child(deviceId)
            .child("oneTimeKeys")
            .get()
            .await()
        require(publishedKeys.children.any {
            it.child("keyId").getValue(String::class.java) == keyId
        }) {
            "The requested one-time prekey is not in the published device bundle"
        }
        require(auth.currentUser?.uid == claimantUid) {
            "Authenticated account changed while resolving the one-time prekey"
        }

        // Create the claimant-owned reverse index first. The database rules
        // require this marker before accepting the immutable one-time claim.
        // If the app stops between these writes, account deletion can safely
        // remove the dangling marker; it can never leave an unindexed claim.
        val claimantIndexReference = database.reference
            .child(CLAIMANT_INDEX_NODE)
            .child(claimantUid)
            .child(targetUid)
            .child(deviceId)
            .child(keyId)
        val existingIndex = claimantIndexReference.get().await()
        if (existingIndex.getValue(Boolean::class.java) != true) {
            claimantIndexReference.setValue(true).await()
        }

        val claimReference = database.reference
            .child(CLAIMS_NODE)
            .child(targetUid)
            .child(deviceId)
            .child(keyId)

        return try {
            // A conditional RTDB write is evaluated atomically by the server.
            // The pre-created reverse index is mandatory in the claim rule.
            claimReference.setValue(claimRecord).await()
            true
        } catch (error: com.google.firebase.database.DatabaseException) {
            if (error.message?.contains("Permission denied", ignoreCase = true) == true) {
                // Permission denial is definitive: this claim did not commit.
                // Remove our unused marker; if cleanup itself fails, account
                // deletion will find and remove it later.
                runCatching { claimantIndexReference.removeValue().await() }
                false
            } else {
                // A transport failure may have happened after the server
                // committed the claim, so retain the marker for safe cleanup.
                throw error
            }
        }
    }

    /**
     * Claim the first available one-time prekey for a specific remote device.
     * Concurrent callers may race on the same key; the database transaction
     * ensures only one succeeds, and this method tries the next candidate.
     *
     * Returns null when the device has no unclaimed one-time prekeys.
     */
    suspend fun claimAvailableOneTimePreKey(
        targetUid: String,
        deviceId: String,
        scope: OlmPreKeyClaimScope,
    ): SignedOlmPreKey? {
        require(DEVICE_ID_REGEX.matches(deviceId)) { "Device ID has an invalid format" }
        val device = getUserDevices(targetUid).firstOrNull { it.deviceId == deviceId }
            ?: return null

        for (prekey in device.oneTimeKeys) {
            if (claimOneTimePreKey(targetUid, deviceId, prekey.keyId, scope)) {
                return prekey
            }
        }
        return null
    }

    /** Remove only this installation's public directory entry. */
    override suspend fun removeMyDevice(deviceId: String) {
        val uid = requireNotNull(auth.currentUser?.uid) {
            "Sign in before removing a device identity"
        }
        require(DEVICE_ID_REGEX.matches(deviceId)) {
            "Device ID has an invalid format"
        }

        // Revoke the public key bundle first. Rules intentionally permit the
        // account owner to delete claim records only after this directory entry
        // is absent, preventing an owner from resetting claims for an active key.
        database.reference
            .child(ROOT_NODE)
            .child(uid)
            .child(deviceId)
            .removeValue()
            .await()

        // The device is already revoked, so no new encrypted push may be
        // addressed to it. Remove its private FCM registration as part of the
        // same idempotent cleanup; never clear the account-wide legacy token.
        database.reference
            .child(PRIVATE_USERS_NODE)
            .child(uid)
            .child(DEVICE_FCM_TOKENS_NODE)
            .child(deviceId)
            .removeValue()
            .await()

        // A failure here is safe: the device is already revoked. A retry is
        // idempotent and completes cleanup of its now-orphaned claim records.
        database.reference
            .child(CLAIMS_NODE)
            .child(uid)
            .child(deviceId)
            .removeValue()
            .await()

        // Revoke the public key and remove dependent directory state before
        // purging the matching temporary ciphertext mailbox. If Worker cleanup
        // fails, identity-transfer retirement remains retryable and this method
        // can safely be called again.
        retiredDeviceMailboxPurger.purgeAfterRevocation(uid, deviceId)
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
            lastSeenAtMillis = snapshot.child("lastSeenAt").getValue(Long::class.javaObjectType),
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
        "lastSeenAt" to ServerValue.TIMESTAMP,
    )

    private fun SignedOlmPreKey.toDirectoryMap(): Map<String, String> = mapOf(
        "keyKind" to keyKind,
        "keyId" to keyId,
        "publicKey" to publicKey,
        "signature" to signature,
    )

    private companion object {
        const val TAG = "DeviceKeyDirectory"
        const val MAX_FCM_TOKEN_LENGTH = 4096
        const val PRIVATE_USERS_NODE = "privateUsers"
        const val DEVICE_FCM_TOKENS_NODE = "olmDeviceFcmTokens"
        const val ROOT_NODE = "deviceKeys"
        const val CLAIMS_NODE = "deviceKeyClaims"
        const val CLAIMANT_INDEX_NODE = "deviceKeyClaimsByClaimant"
        const val SCHEMA_VERSION = 1
        const val MAX_ONE_TIME_KEYS = 20
        val UID_REGEX = Regex("[A-Za-z0-9_-]{1,128}")
        val DEVICE_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
        val KEY_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
