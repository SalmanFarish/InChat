package com.example.inchat.crypto

import android.content.Context
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Public metadata for a persisted local Megolm sender ratchet. */
internal data class OutboundMegolmSessionInfo(
    val sessionId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val created: Boolean,
)

/** Ciphertext metadata returned after the sender ratchet has been persisted. */
internal data class EncryptedMegolmMessage(
    val sessionId: String,
    val messageIndex: Long,
    val ciphertext: ByteArray,
)

/**
 * Narrow Android adapter for the isolated native Megolm session store.
 *
 * This class only accepts a single validated membership snapshot. Callers must
 * obtain it from the authenticated Worker-backed group membership source, not
 * synthesize it from the legacy group-members listener. Key distribution must
 * still travel through SAS-trusted Olm device sessions.
 *
 * It deliberately does not write to Firebase, Room chat tables, or the live UI.
 */
internal class MegolmSessionRepository(
    context: Context,
    private val identityRepository: DeviceIdentityRepository = DeviceIdentityRepository(context.applicationContext),
    private val authenticatedUserId: () -> String,
) {
    private val appContext = context.applicationContext

    private val localBundle: PublicDeviceKeyBundle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        identityRepository.ensurePublicKeyBundle()
    }

    private fun <T> withNativeState(block: (CryptoStateStore, String) -> T): T {
        identityRepository.requireIdentityUsable()
        val deviceId = localBundle.deviceId
        val store = CryptoStateStore(appContext, "device-$deviceId")
        return store.withExclusiveAccess { block(store, deviceId) }
    }

    /** Ensure one sender ratchet exists for this exact group membership epoch. */
    fun ensureOutboundSession(
        membership: GroupCryptoMembershipSnapshot,
    ): OutboundMegolmSessionInfo {
        require(membership.isValid()) { "Authoritative group membership snapshot is invalid" }
        val ownerUid = authenticatedUserId()
        require(ownerUid in membership.memberUserIds) {
            "Authenticated account is not a current group member"
        }
        val encoded = withNativeState { store, deviceId ->
            NativeCryptoBridge.nativeEnsureOutboundMegolmSession(
                store,
                deviceId,
                membership.groupId,
                membership.epoch,
                ownerUid,
            )
        } ?: throw IllegalStateException("Could not create or restore the outbound Megolm session")
        val result = JSONObject(encoded)
        val sessionId = result.getString("sessionId")
        require(sessionId.isNotBlank() && sessionId.length <= MAX_SESSION_ID_LENGTH) {
            "Native Megolm session ID is invalid"
        }
        require(result.getLong("membershipEpoch") == membership.epoch) {
            "Native Megolm session epoch does not match the membership snapshot"
        }
        return OutboundMegolmSessionInfo(
            sessionId = sessionId,
            groupId = membership.groupId,
            membershipEpoch = membership.epoch,
            created = result.getBoolean("created"),
        )
    }

    /**
     * Export the decryption capability for distribution only inside authenticated
     * Olm envelopes. The caller must clear the returned bytes after encoding.
     */
    fun exportOutboundSessionKey(
        membership: GroupCryptoMembershipSnapshot,
    ): ByteArray {
        require(membership.isValid()) { "Authoritative group membership snapshot is invalid" }
        val ownerUid = authenticatedUserId()
        require(ownerUid in membership.memberUserIds) {
            "Authenticated account is not a current group member"
        }
        return requireNotNull(
            withNativeState { store, deviceId ->
                NativeCryptoBridge.nativeExportOutboundMegolmSessionKey(
                    store,
                    deviceId,
                    membership.groupId,
                    membership.epoch,
                    ownerUid,
                )
            },
        ) { "Could not export the current outbound Megolm session key" }
    }

    /**
     * Import a session key only after the Olm control payload has been validated
     * against the authenticated receipt, SAS-trusted sender and current snapshot.
     */
    fun importInboundSessionKey(
        membership: GroupCryptoMembershipSnapshot,
        senderUserId: String,
        senderDeviceId: String,
        expectedSessionId: String,
        sessionKey: ByteArray,
    ): Boolean {
        require(membership.isValid()) { "Authoritative group membership snapshot is invalid" }
        val ownerUid = authenticatedUserId()
        require(ownerUid in membership.memberUserIds && senderUserId in membership.memberUserIds) {
            "Group key sender or recipient is not a current member"
        }
        require(senderDeviceId.matches(DEVICE_ID)) { "Sender device ID is invalid" }
        require(expectedSessionId.matches(SESSION_ID)) { "Megolm session ID is invalid" }
        require(sessionKey.size in MIN_SESSION_KEY_BYTES..MAX_SESSION_KEY_BYTES) {
            "Megolm session key has an invalid size"
        }
        if (senderUserId == ownerUid) {
            require(senderDeviceId != localBundle.deviceId) {
                "A device cannot import its own outbound Megolm session"
            }
        }
        return try {
            withNativeState { store, deviceId ->
                NativeCryptoBridge.nativeImportInboundMegolmSessionKey(
                    store,
                    deviceId,
                    membership.groupId,
                    membership.epoch,
                    senderUserId,
                    senderDeviceId,
                    expectedSessionId,
                    sessionKey,
                )
            }
        } finally {
            sessionKey.fill(0)
        }
    }

    /** Encrypt a group message and persist the advanced sender ratchet first. */
    fun encryptOutboundMessage(
        membership: GroupCryptoMembershipSnapshot,
        plaintext: String,
    ): EncryptedMegolmMessage {
        require(membership.isValid()) { "Authoritative group membership snapshot is invalid" }
        val ownerUid = authenticatedUserId()
        require(ownerUid in membership.memberUserIds) {
            "Authenticated account is not a current group member"
        }
        require(plaintext.isNotEmpty()) { "Group plaintext is empty" }
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        try {
            require(plaintextBytes.size <= MAX_PLAINTEXT_BYTES) {
                "Group plaintext exceeds the 64 KiB limit"
            }
        } finally {
            plaintextBytes.fill(0)
        }
        val encoded = withNativeState { store, deviceId ->
            NativeCryptoBridge.nativeEncryptOutboundMegolmMessage(
                store,
                deviceId,
                membership.groupId,
                membership.epoch,
                ownerUid,
                plaintext,
            )
        } ?: throw IllegalStateException("Could not encrypt the group message")
        val result = JSONObject(encoded)
        val sessionId = result.getString("sessionId")
        require(sessionId.matches(SESSION_ID)) { "Native Megolm session ID is invalid" }
        val index = result.getLong("messageIndex")
        require(index >= 0L && index <= MAX_MESSAGE_INDEX) {
            "Native Megolm message index is invalid"
        }
        val encodedCiphertext = result.getJSONArray("ciphertext")
        require(encodedCiphertext.length() in 1..MAX_CIPHERTEXT_BYTES) {
            "Native Megolm ciphertext has an invalid size"
        }
        val ciphertext = ByteArray(encodedCiphertext.length()) { i ->
            encodedCiphertext.getInt(i).also { require(it in 0..255) }.toByte()
        }
        return EncryptedMegolmMessage(sessionId, index, ciphertext)
    }

    private companion object {
        const val MAX_SESSION_ID_LENGTH = 128
        const val MIN_SESSION_KEY_BYTES = 32
        const val MAX_SESSION_KEY_BYTES = 1024
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        const val MAX_CIPHERTEXT_BYTES = 1024 * 1024
        const val MAX_MESSAGE_INDEX = 0xffff_ffffL
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
    }
}
