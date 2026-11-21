package com.example.inchat.crypto

import android.content.Context
import com.example.inchat.data.model.GroupCryptoMembershipSnapshot
import java.util.Base64
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

/** Sender ciphertext staged in the encrypted native state until the Room outbox commits. */
internal data class StagedOutboundMegolmMessage(
    val ownerUserId: String,
    val groupId: String,
    val membershipEpoch: Long,
    val messageId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val sentAtMillis: Long,
    val encryptedMessage: EncryptedMegolmMessage,
)

/** Native staged receive result; plaintext exists only in memory after native persistence. */
internal data class StagedMegolmDecryption(
    val receiptId: String,
    val message: DecryptedGroupMessage,
    val ciphertext: ByteArray,
    val receivedAtMillis: Long,
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
) : MegolmInboundSessionKeyImporter {
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
     * Read the exact sender ratchet for recovery without creating a replacement.
     * A missing or mismatched session returns null and leaves native state unchanged.
     */
    fun getExistingOutboundSession(
        membership: GroupCryptoMembershipSnapshot,
    ): OutboundMegolmSessionInfo? {
        require(membership.isValid()) { "Authoritative group membership snapshot is invalid" }
        val ownerUid = authenticatedUserId()
        require(ownerUid in membership.memberUserIds) {
            "Authenticated account is not a current group member"
        }
        val encoded = withNativeState { store, deviceId ->
            NativeCryptoBridge.nativeGetExistingOutboundMegolmSession(
                store, deviceId, membership.groupId, membership.epoch, ownerUid,
            )
        } ?: return null
        val result = JSONObject(encoded)
        val sessionId = result.getString("sessionId")
        require(sessionId.isNotBlank() && sessionId.length <= MAX_SESSION_ID_LENGTH) {
            "Native Megolm session ID is invalid"
        }
        require(result.getLong("membershipEpoch") == membership.epoch &&
            !result.getBoolean("created")) {
            "Native recovery session metadata is invalid"
        }
        return OutboundMegolmSessionInfo(
            sessionId = sessionId,
            groupId = membership.groupId,
            membershipEpoch = membership.epoch,
            created = false,
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
    override fun importInboundSessionKey(
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

    /**
     * Encrypt one outbound message and stage its exact ciphertext together with
     * the advanced native sender ratchet. Call finalizeOutboundMessage only after
     * the complete per-device Olm fan-out is committed to the Room outbox.
     */
    fun prepareOutboundMessage(
        membership: GroupCryptoMembershipSnapshot,
        messageId: String,
        plaintext: String,
        sentAtMillis: Long,
    ): StagedOutboundMegolmMessage {
        require(membership.isValid()) { "Authoritative group membership snapshot is invalid" }
        val ownerUid = authenticatedUserId()
        require(ownerUid in membership.memberUserIds) {
            "Authenticated account is not a current group member"
        }
        require(messageId.matches(ID)) { "Group message ID is invalid" }
        require(sentAtMillis >= 0L) { "Group message timestamp cannot be negative" }
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
                store = store,
                deviceId = deviceId,
                groupId = membership.groupId,
                membershipEpoch = membership.epoch,
                senderUserId = ownerUid,
                messageId = messageId,
                sentAtMillis = sentAtMillis,
                plaintext = plaintext,
            )
        } ?: throw IllegalStateException("Could not stage the outbound group message")
        return parseStagedOutboundMegolmMessage(
            encoded = encoded,
            expectedOwnerUserId = ownerUid,
            expectedGroupId = membership.groupId,
            expectedMembershipEpoch = membership.epoch,
            expectedMessageId = messageId,
            expectedSenderDeviceId = localBundle.deviceId,
            expectedSentAtMillis = sentAtMillis,
        )
    }

    /** List sender ciphertexts that survived process death before Room outbox commit. */
    fun pendingOutboundMessageIds(): List<String> {
        val encoded = withNativeState { store, deviceId ->
            NativeCryptoBridge.nativeListPendingOutboundMegolmMessageIds(store, deviceId)
        } ?: throw IllegalStateException("Could not list pending outbound Megolm messages")
        val array = JSONArray(encoded)
        require(array.length() <= MAX_PENDING_OUTBOUND_MESSAGES) {
            "Native pending outbound Megolm message count exceeds the limit"
        }
        return (0 until array.length()).map { index ->
            array.getString(index).also {
                require(it.matches(ID)) { "Native pending outbound Megolm message ID is invalid" }
            }
        }
    }

    /** Read the exact previously encrypted ciphertext without advancing the sender ratchet. */
    fun readPendingOutboundMessage(messageId: String): StagedOutboundMegolmMessage {
        require(messageId.matches(ID)) { "Group message ID is invalid" }
        val ownerUid = authenticatedUserId()
        val deviceId = localBundle.deviceId
        val encoded = withNativeState { store, nativeDeviceId ->
            NativeCryptoBridge.nativeReadPendingOutboundMegolmMessage(
                store,
                nativeDeviceId,
                messageId,
            )
        } ?: throw IllegalStateException("Could not read pending outbound Megolm message")
        return parseStagedOutboundMegolmMessage(
            encoded = encoded,
            expectedOwnerUserId = ownerUid,
            expectedGroupId = null,
            expectedMembershipEpoch = null,
            expectedMessageId = messageId,
            expectedSenderDeviceId = deviceId,
            expectedSentAtMillis = null,
        )
    }

    /** Clear the native staged ciphertext only after all recipient envelopes are durable in Room. */
    fun finalizeOutboundMessage(messageId: String): Boolean {
        require(messageId.matches(ID)) { "Group message ID is invalid" }
        return withNativeState { store, deviceId ->
            NativeCryptoBridge.nativeFinalizeOutboundMegolmMessage(store, deviceId, messageId)
        }
    }

    private fun parseStagedOutboundMegolmMessage(
        encoded: String,
        expectedOwnerUserId: String,
        expectedGroupId: String?,
        expectedMembershipEpoch: Long?,
        expectedMessageId: String,
        expectedSenderDeviceId: String,
        expectedSentAtMillis: Long?,
    ): StagedOutboundMegolmMessage {
        if (encoded.startsWith(NATIVE_ERROR_PREFIX)) {
            throw IllegalStateException(encoded.removePrefix(NATIVE_ERROR_PREFIX))
        }
        val result = JSONObject(encoded)
        val ownerUserId = result.getString("ownerUserId")
        val groupId = result.getString("groupId")
        val membershipEpoch = result.getLong("membershipEpoch")
        val messageId = result.getString("messageId")
        val senderUserId = result.getString("senderUserId")
        val senderDeviceId = result.getString("senderDeviceId")
        val sentAtMillis = result.getLong("sentAtMillis")
        val sessionId = result.getString("sessionId")
        val messageIndex = result.getLong("messageIndex")
        require(ownerUserId == expectedOwnerUserId && senderUserId == expectedOwnerUserId) {
            "Native staged outbound Megolm message belongs to another account"
        }
        require(expectedGroupId == null || groupId == expectedGroupId) {
            "Native staged outbound Megolm message belongs to another group"
        }
        require(expectedMembershipEpoch == null || membershipEpoch == expectedMembershipEpoch) {
            "Native staged outbound Megolm epoch does not match the current membership"
        }
        require(messageId == expectedMessageId && messageId.matches(ID)) {
            "Native staged outbound Megolm message ID does not match the request"
        }
        require(senderDeviceId == expectedSenderDeviceId && senderDeviceId.matches(DEVICE_ID)) {
            "Native staged outbound Megolm message belongs to another sender device"
        }
        require(membershipEpoch > 0L && sessionId.matches(SESSION_ID)) {
            "Native staged outbound Megolm session metadata is invalid"
        }
        require(expectedSentAtMillis == null || sentAtMillis == expectedSentAtMillis) {
            "Native staged outbound Megolm timestamp does not match the request"
        }
        require(sentAtMillis >= 0L && messageIndex in 0L until MAX_MESSAGE_INDEX) {
            "Native staged outbound Megolm timestamp or message index is invalid"
        }
        val array = result.getJSONArray("ciphertext")
        require(array.length() in 1..MAX_CIPHERTEXT_BYTES) {
            "Native staged outbound Megolm ciphertext has an invalid size"
        }
        val ciphertext = ByteArray(array.length()) { index ->
            array.getInt(index).also { require(it in 0..255) }.toByte()
        }
        return StagedOutboundMegolmMessage(
            ownerUserId = ownerUserId,
            groupId = groupId,
            membershipEpoch = membershipEpoch,
            messageId = messageId,
            senderUserId = senderUserId,
            senderDeviceId = senderDeviceId,
            sentAtMillis = sentAtMillis,
            encryptedMessage = EncryptedMegolmMessage(
                sessionId = sessionId,
                messageIndex = messageIndex,
                ciphertext = ciphertext,
            ),
        )
    }

    /**
     * Stage one already-authenticated Olm group payload. Caller responsibilities:
     * verify the sender's current SAS trust and decode the payload against the
     * authenticated outer Olm receipt before calling this method.
     *
     * Native code persists the advanced Megolm ratchet and plaintext together.
     * Do not expose this method to a raw Firebase ciphertext listener.
     */
    fun prepareInboundMessage(
        membership: GroupCryptoMembershipSnapshot,
        payload: OlmGroupMessagePayload,
        receiptId: String,
        receivedAtMillis: Long,
    ): StagedMegolmDecryption {
        require(membership.isValid()) { "Authoritative group membership snapshot is invalid" }
        require(receiptId.matches(RECEIPT_ID)) { "Inbound group receipt ID is invalid" }
        require(receivedAtMillis >= 0L) { "Group receipt time cannot be negative" }
        val ownerUid = authenticatedUserId()
        val localDeviceId = localBundle.deviceId
        require(ownerUid in membership.memberUserIds &&
            payload.senderUserId in membership.memberUserIds &&
            payload.recipientUserId in membership.memberUserIds
        ) { "Group sender or recipient is not a current member" }
        require(payload.groupId == membership.groupId &&
            payload.membershipEpoch == membership.epoch
        ) { "Group message membership snapshot is stale or belongs to another group" }
        require(payload.recipientUserId == ownerUid && payload.recipientDeviceId == localDeviceId) {
            "Group message is not addressed to this account and device"
        }
        OlmGroupMessagePayloadCodec.requireCurrentMembers(payload, membership.memberUserIds)
        require(payload.messageIndex in 0L..MAX_MESSAGE_INDEX) {
            "Megolm message index is invalid"
        }
        val ciphertext = try {
            Base64.getUrlDecoder().decode(payload.ciphertext)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Megolm ciphertext is not valid Base64URL", error)
        }
        require(ciphertext.isNotEmpty() && ciphertext.size <= MAX_CIPHERTEXT_BYTES) {
            "Megolm ciphertext has an invalid size"
        }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext) == payload.ciphertext) {
            "Megolm ciphertext is not canonical Base64URL"
        }
        try {
            val encoded = withNativeState { store, deviceId ->
                NativeCryptoBridge.nativePrepareInboundMegolmDecryption(
                    store = store,
                    deviceId = deviceId,
                    receiptId = receiptId,
                    ownerUserId = ownerUid,
                    groupId = payload.groupId,
                    membershipEpoch = payload.membershipEpoch,
                    messageId = payload.messageId,
                    senderUserId = payload.senderUserId,
                    senderDeviceId = payload.senderDeviceId,
                    sessionId = payload.megolmSessionId,
                    expectedMessageIndex = payload.messageIndex,
                    sentAtMillis = payload.sentAtMillis,
                    receivedAtMillis = receivedAtMillis,
                    ciphertext = ciphertext,
                )
            } ?: throw IllegalStateException("Could not stage inbound Megolm decryption")
            return parseStagedMegolmDecryption(
                encoded = encoded,
                expectedReceiptId = receiptId,
                expectedOwnerUserId = ownerUid,
                expectedRecipientDeviceId = localDeviceId,
            )
        } finally {
            ciphertext.fill(0)
        }
    }

    /** List staged receipts after process restart so the Room commit can be replayed. */
    fun pendingInboundDecryptionReceiptIds(): List<String> {
        val encoded = withNativeState { store, deviceId ->
            NativeCryptoBridge.nativeListPendingMegolmDecryptionReceipts(store, deviceId)
        } ?: throw IllegalStateException("Could not list pending Megolm decryptions")
        val array = JSONArray(encoded)
        require(array.length() <= MAX_PENDING_DECRYPTIONS) {
            "Native pending Megolm receipt count exceeds the limit"
        }
        return (0 until array.length()).map { index ->
            array.getString(index).also {
                require(it.matches(RECEIPT_ID)) { "Native pending Megolm receipt ID is invalid" }
            }
        }
    }

    /** Read an already-staged plaintext for idempotent Room-commit recovery. */
    fun readPendingInboundDecryption(receiptId: String): StagedMegolmDecryption {
        require(receiptId.matches(RECEIPT_ID)) { "Inbound group receipt ID is invalid" }
        val ownerUid = authenticatedUserId()
        val localDeviceId = localBundle.deviceId
        val encoded = withNativeState { store, deviceId ->
            NativeCryptoBridge.nativeReadPendingMegolmDecryption(store, deviceId, receiptId)
        } ?: throw IllegalStateException("Could not read pending Megolm decryption")
        return parseStagedMegolmDecryption(
            encoded = encoded,
            expectedReceiptId = receiptId,
            expectedOwnerUserId = ownerUid,
            expectedRecipientDeviceId = localDeviceId,
        )
    }

    /** Clear a staged plaintext only after its encrypted Room row and replay receipt commit. */
    fun finalizeInboundDecryption(receiptId: String): Boolean {
        require(receiptId.matches(RECEIPT_ID)) { "Inbound group receipt ID is invalid" }
        return withNativeState { store, deviceId ->
            NativeCryptoBridge.nativeFinalizeInboundMegolmDecryption(store, deviceId, receiptId)
        }
    }

    private fun parseStagedMegolmDecryption(
        encoded: String,
        expectedReceiptId: String,
        expectedOwnerUserId: String,
        expectedRecipientDeviceId: String,
    ): StagedMegolmDecryption {
        if (encoded.startsWith(NATIVE_ERROR_PREFIX)) {
            throw IllegalStateException(encoded.removePrefix(NATIVE_ERROR_PREFIX))
        }
        val result = JSONObject(encoded)
        val receiptId = result.getString("receiptId")
        val ownerUserId = result.getString("ownerUserId")
        val groupId = result.getString("groupId")
        val messageId = result.getString("messageId")
        val membershipEpoch = result.getLong("membershipEpoch")
        val senderUserId = result.getString("senderUserId")
        val senderDeviceId = result.getString("senderDeviceId")
        val sessionId = result.getString("sessionId")
        val messageIndex = result.getLong("messageIndex")
        val sentAtMillis = result.getLong("sentAtMillis")
        val receivedAtMillis = result.getLong("receivedAtMillis")
        val plaintext = result.getString("plaintext")
        require(receiptId == expectedReceiptId && receiptId.matches(RECEIPT_ID)) {
            "Native staged Megolm receipt does not match the requested receipt"
        }
        require(ownerUserId == expectedOwnerUserId) {
            "Native staged Megolm message belongs to another account"
        }
        require(expectedRecipientDeviceId == localBundle.deviceId) {
            "Native staged Megolm message belongs to another local device"
        }
        require(groupId.matches(ID) && messageId.matches(ID) && senderUserId.matches(ID)) {
            "Native staged Megolm message identity metadata is invalid"
        }
        require(senderDeviceId.matches(DEVICE_ID) && sessionId.matches(SESSION_ID)) {
            "Native staged Megolm sender or session ID is invalid"
        }
        require(membershipEpoch > 0L && messageIndex in 0L..MAX_MESSAGE_INDEX) {
            "Native staged Megolm epoch or message index is invalid"
        }
        require(sentAtMillis >= 0L && receivedAtMillis >= 0L) {
            "Native staged Megolm timestamp is invalid"
        }
        require(plaintext.isNotEmpty() && plaintext.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) {
            "Native staged Megolm plaintext has an invalid size"
        }
        val array = result.getJSONArray("ciphertext")
        require(array.length() in 1..MAX_CIPHERTEXT_BYTES) {
            "Native staged Megolm ciphertext has an invalid size"
        }
        val ciphertext = ByteArray(array.length()) { index ->
            array.getInt(index).also { require(it in 0..255) }.toByte()
        }
        return StagedMegolmDecryption(
            receiptId = receiptId,
            message = DecryptedGroupMessage(
                ownerUserId = ownerUserId,
                groupId = groupId,
                messageId = messageId,
                membershipEpoch = membershipEpoch,
                senderUserId = senderUserId,
                senderDeviceId = senderDeviceId,
                megolmSessionId = sessionId,
                messageIndex = messageIndex,
                sentAtMillis = sentAtMillis,
                text = plaintext,
            ),
            ciphertext = ciphertext,
            receivedAtMillis = receivedAtMillis,
        )
    }

    private companion object {
        const val MAX_SESSION_ID_LENGTH = 128
        const val MAX_PENDING_DECRYPTIONS = 64
        const val MAX_PENDING_OUTBOUND_MESSAGES = 128
        const val NATIVE_ERROR_PREFIX = "INCHAT_CRYPTO_ERROR:"
        val RECEIPT_ID = Regex("[A-Fa-f0-9]{64}")
        const val MIN_SESSION_KEY_BYTES = 32
        const val MAX_SESSION_KEY_BYTES = 1024
        const val MAX_PLAINTEXT_BYTES = 64 * 1024
        const val MAX_CIPHERTEXT_BYTES = 1024 * 1024
        const val MAX_MESSAGE_INDEX = 0xffff_ffffL
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val SESSION_ID = Regex("[A-Za-z0-9_+/-]{16,128}={0,2}")
        val ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
