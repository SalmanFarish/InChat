package com.example.inchat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * QR-relayed proof that a destination installation controls a pairing key which
 * is independent of the identity key being transferred.
 *
 * The destination private key is generated and retained by Android Keystore.
 * Only its public key and a transaction-bound signature leave the device.
 * This is the authentication primitive; it deliberately does not transport
 * identity state or replace the user's visual SAS comparison.
 */
internal class DevicePairingProtocol(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Ephemeral ECDH private keys deliberately live only in this protocol
     * instance's memory. A process restart invalidates the pairing and requires
     * a fresh QR exchange; no transfer secret is written to disk or QR data.
     */
    private data class EphemeralSession(
        val keyPair: KeyPair,
        val sourceDeviceId: String,
        val targetDeviceId: String,
        val transactionId: String,
        val sourceNonce: String,
        val targetNonce: String,
        val expiresAtMillis: Long,
        val peerEphemeralPublicKey: String,
    )

    private val ephemeralSessions = mutableMapOf<String, EphemeralSession>()
    data class Response(
        val encoded: String,
        val sasCode: String,
    )

    data class VerifiedPairing internal constructor(
        val sourceDeviceId: String,
        val targetDeviceId: String,
        val transactionId: String,
        val targetPairingPublicKey: String,
        val sasCode: String,
        val expiresAtMillis: Long,
        internal val sourceEphemeralPublicKey: String,
        internal val targetEphemeralPublicKey: String,
        internal val sourceNonce: String,
        internal val targetNonce: String,
    )

    /** Source displays this request as a QR code for the destination to scan. */
    fun createRequest(sourceDeviceId: String, transactionId: String): String {
        require(DEVICE_ID.matches(sourceDeviceId))
        require(TRANSACTION_ID.matches(transactionId))
        val now = clock().coerceAtLeast(0L)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val ephemeral = generateEphemeralKeyPair()
        val sourceEphemeralPublicKey = encode(ephemeral.public.encoded)
        synchronized(ephemeralSessions) {
            ephemeralSessions[transactionId] = EphemeralSession(
                keyPair = ephemeral,
                sourceDeviceId = sourceDeviceId,
                targetDeviceId = "",
                transactionId = transactionId,
                sourceNonce = encode(nonce),
                targetNonce = "",
                expiresAtMillis = now + LIFETIME_MILLIS,
                peerEphemeralPublicKey = "",
            )
        }
        return try {
            JSONObject()
                .put("protocol", PROTOCOL)
                .put("sourceDeviceId", sourceDeviceId)
                .put("transactionId", transactionId)
                .put("sourceNonce", encode(nonce))
                .put("sourceEphemeralPublicKey", sourceEphemeralPublicKey)
                .put("expiresAt", now + LIFETIME_MILLIS)
                .toString()
        } finally {
            nonce.fill(0)
        }
    }

    /**
     * Destination scans the source QR, creates/loads its installation-specific
     * Keystore key, and returns a signed response QR plus the SAS to compare.
     */
    fun respondToRequest(encodedRequest: String, targetDeviceId: String): Response {
        require(DEVICE_ID.matches(targetDeviceId))
        val request = parseRequest(encodedRequest)
        val now = clock().coerceAtLeast(0L)
        validateRequest(request, now)
        val sourceDeviceId = request.getString("sourceDeviceId")
        require(sourceDeviceId != targetDeviceId) { "A device cannot pair with itself" }

        val pair = loadOrCreateKeyPair(targetDeviceId)
        val publicKey = encode(pair.public.encoded)
        val targetNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val targetEphemeral = generateEphemeralKeyPair()
        val targetEphemeralPublicKey = encode(targetEphemeral.public.encoded)
        val sourceEphemeralPublicKey = request.getString("sourceEphemeralPublicKey")
        val transactionId = request.getString("transactionId")
        synchronized(ephemeralSessions) {
            ephemeralSessions[transactionId] = EphemeralSession(
                keyPair = targetEphemeral,
                sourceDeviceId = sourceDeviceId,
                targetDeviceId = targetDeviceId,
                transactionId = transactionId,
                sourceNonce = request.getString("sourceNonce"),
                targetNonce = encode(targetNonce),
                expiresAtMillis = request.getLong("expiresAt"),
                peerEphemeralPublicKey = sourceEphemeralPublicKey,
            )
        }
        try {
            val transcript = transcript(
                sourceDeviceId,
                targetDeviceId,
                request.getString("transactionId"),
                request.getString("sourceNonce"),
                encode(targetNonce),
                request.getLong("expiresAt"),
                publicKey,
                sourceEphemeralPublicKey,
                targetEphemeralPublicKey,
            )
            val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
            signer.initSign(pair.private, random)
            signer.update(transcript)
            val signature = encode(signer.sign())
            val response = JSONObject()
                .put("protocol", PROTOCOL)
                .put("sourceDeviceId", sourceDeviceId)
                .put("targetDeviceId", targetDeviceId)
                .put("transactionId", request.getString("transactionId"))
                .put("sourceNonce", request.getString("sourceNonce"))
                .put("targetNonce", encode(targetNonce))
                .put("expiresAt", request.getLong("expiresAt"))
                .put("targetPairingPublicKey", publicKey)
                .put("sourceEphemeralPublicKey", sourceEphemeralPublicKey)
                .put("targetEphemeralPublicKey", targetEphemeralPublicKey)
                .put("signature", signature)
                .toString()
            return Response(response, sasCode(transcript))
        } finally {
            targetNonce.fill(0)
        }
    }

    /**
     * Destination calls this only after its user compares the SAS and confirms.
     * The second signature prevents the source from treating an unconfirmed QR
     * response as an authenticated pairing.
     */
    fun confirmResponse(
        encodedResponse: String,
        targetDeviceId: String,
        confirmedSasCode: String,
    ): String {
        require(DEVICE_ID.matches(targetDeviceId))
        val response = parseObject(encodedResponse)
        require(response.getString("protocol") == PROTOCOL)
        require(response.getString("targetDeviceId") == targetDeviceId)
        val now = clock().coerceAtLeast(0L)
        require(response.getLong("expiresAt") > now) { "Pairing request has expired" }
        val publicKey = response.getString("targetPairingPublicKey")
        val transcript = transcript(
            response.getString("sourceDeviceId"),
            targetDeviceId,
            response.getString("transactionId"),
            response.getString("sourceNonce"),
            response.getString("targetNonce"),
            response.getLong("expiresAt"),
            publicKey,
            response.getString("sourceEphemeralPublicKey"),
            response.getString("targetEphemeralPublicKey"),
        )
        require(constantTimeEquals(sasCode(transcript), confirmedSasCode)) {
            "The displayed pairing codes do not match"
        }
        val pair = loadOrCreateKeyPair(targetDeviceId)
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(pair.private, random)
        signer.update(transcript)
        signer.update(DESTINATION_CONFIRMATION_SUFFIX)
        return JSONObject()
            .put("protocol", PROTOCOL)
            .put("sourceDeviceId", response.getString("sourceDeviceId"))
            .put("targetDeviceId", targetDeviceId)
            .put("transactionId", response.getString("transactionId"))
            .put("sourceNonce", response.getString("sourceNonce"))
            .put("targetNonce", response.getString("targetNonce"))
            .put("expiresAt", response.getLong("expiresAt"))
            .put("targetPairingPublicKey", publicKey)
            .put("sourceEphemeralPublicKey", response.getString("sourceEphemeralPublicKey"))
            .put("targetEphemeralPublicKey", response.getString("targetEphemeralPublicKey"))
            .put("confirmationSignature", encode(signer.sign()))
            .toString()
    }

    /**
     * Source scans the destination's final confirmation QR. Both signatures
     * must verify, and the source user must independently confirm the same SAS.
     */
    fun verifyConfirmation(
        encodedRequest: String,
        encodedResponse: String,
        encodedConfirmation: String,
        expectedSourceDeviceId: String,
        expectedTransactionId: String,
        confirmedSasCode: String,
    ): VerifiedPairing {
        val request = parseRequest(encodedRequest)
        val response = parseObject(encodedResponse)
        val now = clock().coerceAtLeast(0L)
        validateRequest(request, now)
        require(response.getString("protocol") == PROTOCOL) { "Unsupported pairing response" }

        val sourceDeviceId = request.getString("sourceDeviceId")
        val transactionId = request.getString("transactionId")
        require(sourceDeviceId == expectedSourceDeviceId) { "Pairing request belongs to another source device" }
        require(transactionId == expectedTransactionId) { "Pairing transaction does not match" }
        require(response.getString("sourceDeviceId") == sourceDeviceId)
        require(response.getString("transactionId") == transactionId)
        require(response.getString("sourceNonce") == request.getString("sourceNonce")) {
            "Pairing response does not match this QR request"
        }
        require(response.getLong("expiresAt") == request.getLong("expiresAt"))
        require(response.getLong("expiresAt") > now) { "Pairing request has expired" }

        val targetDeviceId = response.getString("targetDeviceId")
        require(DEVICE_ID.matches(targetDeviceId) && targetDeviceId != sourceDeviceId) {
            "Destination device ID is invalid"
        }
        val confirmation = parseObject(encodedConfirmation)
        require(confirmation.getString("protocol") == PROTOCOL)
        listOf("sourceDeviceId", "targetDeviceId", "transactionId", "sourceNonce",
            "targetNonce", "targetPairingPublicKey", "sourceEphemeralPublicKey",
            "targetEphemeralPublicKey").forEach { field ->
            require(confirmation.getString(field) == response.getString(field)) {
                "Destination confirmation does not match its pairing response"
            }
        }
        require(confirmation.getLong("expiresAt") == response.getLong("expiresAt"))
        val publicKeyBytes = decode(response.getString("targetPairingPublicKey"), MAX_PUBLIC_KEY_BYTES)
        val targetNonce = decode(response.getString("targetNonce"), NONCE_BYTES)
        val signatureBytes = decode(response.getString("signature"), MAX_SIGNATURE_BYTES)
        val confirmationBytes = decode(
            confirmation.getString("confirmationSignature"), MAX_SIGNATURE_BYTES,
        )
        require(targetNonce.size == NONCE_BYTES)
        try {
            val publicKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyBytes))
            val transcript = transcript(
                sourceDeviceId,
                targetDeviceId,
                transactionId,
                request.getString("sourceNonce"),
                response.getString("targetNonce"),
                request.getLong("expiresAt"),
                response.getString("targetPairingPublicKey"),
                response.getString("sourceEphemeralPublicKey"),
                response.getString("targetEphemeralPublicKey"),
            )
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(transcript)
            require(verifier.verify(signatureBytes)) {
                "Destination pairing-key proof is invalid"
            }
            require(constantTimeEquals(sasCode(transcript), confirmedSasCode)) {
                "The displayed pairing codes do not match"
            }
            val confirmationVerifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            confirmationVerifier.initVerify(publicKey)
            confirmationVerifier.update(transcript)
            confirmationVerifier.update(DESTINATION_CONFIRMATION_SUFFIX)
            require(confirmationVerifier.verify(confirmationBytes)) {
                "Destination user confirmation is invalid"
            }
            return VerifiedPairing(
                sourceDeviceId = sourceDeviceId,
                targetDeviceId = targetDeviceId,
                transactionId = transactionId,
                targetPairingPublicKey = response.getString("targetPairingPublicKey"),
                sasCode = sasCode(transcript),
                expiresAtMillis = request.getLong("expiresAt"),
                sourceEphemeralPublicKey = response.getString("sourceEphemeralPublicKey"),
                targetEphemeralPublicKey = response.getString("targetEphemeralPublicKey"),
                sourceNonce = request.getString("sourceNonce"),
                targetNonce = response.getString("targetNonce"),
            )
        } finally {
            publicKeyBytes.fill(0)
            targetNonce.fill(0)
            signatureBytes.fill(0)
            confirmationBytes.fill(0)
        }
    }

    /**
     * Derive the one-time transfer key on the source after verified SAS and
     * destination confirmation. The source ephemeral private key is consumed.
     */
    fun deriveSourceTransferKey(pairing: VerifiedPairing): ByteArray =
        deriveTransferKey(pairing, expectedSource = true)

    /**
     * Derive the same one-time transfer key on the destination after its user
     * confirmed the SAS. The destination ephemeral private key is consumed.
     */
    fun deriveDestinationTransferKey(targetDeviceId: String, transactionId: String): ByteArray {
        val session = synchronized(ephemeralSessions) {
            ephemeralSessions.remove(transactionId)
        } ?: throw IllegalStateException("Pairing session expired; scan a new QR code")
        require(session.targetDeviceId == targetDeviceId) {
            "Pairing session belongs to another destination"
        }
        require(session.expiresAtMillis > clock()) { "Pairing session expired" }
        return deriveEcdhKey(
            session.keyPair,
            session.peerEphemeralPublicKey,
            session.sourceDeviceId,
            session.targetDeviceId,
            session.transactionId,
            session.sourceNonce,
            session.targetNonce,
        )
    }

    /** Discard ephemeral key material when the user cancels or pairing expires. */
    fun clearEphemeralSession(transactionId: String) {
        synchronized(ephemeralSessions) { ephemeralSessions.remove(transactionId) }
    }

    private fun deriveTransferKey(pairing: VerifiedPairing, expectedSource: Boolean): ByteArray {
        require(expectedSource)
        require(pairing.expiresAtMillis > clock()) { "Pairing session expired" }
        val session = synchronized(ephemeralSessions) {
            ephemeralSessions.remove(pairing.transactionId)
        } ?: throw IllegalStateException("Pairing session expired; scan a new QR code")
        require(
            session.sourceDeviceId == pairing.sourceDeviceId &&
                session.transactionId == pairing.transactionId &&
                session.expiresAtMillis == pairing.expiresAtMillis &&
                session.targetDeviceId.isEmpty()
        ) { "Source pairing session does not match the verified destination" }
        return deriveEcdhKey(
            session.keyPair,
            pairing.targetEphemeralPublicKey,
            pairing.sourceDeviceId,
            pairing.targetDeviceId,
            pairing.transactionId,
            pairing.sourceNonce,
            pairing.targetNonce,
        )
    }

    private fun generateEphemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()

    private fun deriveEcdhKey(
        localPair: KeyPair,
        peerPublicKeyEncoded: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        sourceNonce: String,
        targetNonce: String,
    ): ByteArray {
        val peerBytes = decode(peerPublicKeyEncoded, MAX_PUBLIC_KEY_BYTES)
        val sharedSecret = try {
            val peerKey = java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(peerBytes))
            KeyAgreement.getInstance("ECDH").run {
                init(localPair.private)
                doPhase(peerKey, true)
                generateSecret()
            }
        } finally {
            peerBytes.fill(0)
        }
        val context = listOf(
            "INCHAT-DEVICE-TRANSFER-ECDH-SALT-V1",
            sourceDeviceId,
            targetDeviceId,
            transactionId,
            sourceNonce,
            targetNonce,
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        val salt = MessageDigest.getInstance("SHA-256").digest(context)
        context.fill(0)
        try {
            val extract = Mac.getInstance("HmacSHA256")
            extract.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = extract.doFinal(sharedSecret)
            try {
                val expand = Mac.getInstance("HmacSHA256")
                expand.init(SecretKeySpec(prk, "HmacSHA256"))
                expand.update("INCHAT-DEVICE-IDENTITY-TRANSFER-KEY-V1".toByteArray(StandardCharsets.UTF_8))
                expand.update(1.toByte())
                return expand.doFinal().copyOf(TRANSFER_KEY_BYTES)
            } finally {
                prk.fill(0)
            }
        } finally {
            sharedSecret.fill(0)
            salt.fill(0)
        }
    }

    /** Remove this installation's pairing key only during an explicit reset/revocation flow. */
    fun deletePairingKey(deviceId: String) {
        require(DEVICE_ID.matches(deviceId))
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        store.deleteEntry(alias(deviceId))
    }

    private fun loadOrCreateKeyPair(deviceId: String): KeyPair {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val keyAlias = alias(deviceId)
        if (store.containsAlias(keyAlias)) {
            val privateKey = store.getKey(keyAlias, null) as java.security.PrivateKey
            val publicKey = store.getCertificate(keyAlias).publicKey
            return KeyPair(publicKey, privateKey)
        }

        val generator = KeyPairGenerator.getInstance("EC", ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private fun parseRequest(encoded: String): JSONObject {
        require(encoded.length <= MAX_QR_LENGTH) { "Pairing QR is too large" }
        val value = parseObject(encoded)
        require(value.getString("protocol") == PROTOCOL) { "Unsupported pairing request" }
        require(DEVICE_ID.matches(value.getString("sourceDeviceId")))
        require(TRANSACTION_ID.matches(value.getString("transactionId")))
        val nonce = decode(value.getString("sourceNonce"), NONCE_BYTES)
        val ephemeralPublicKey = decode(
            value.getString("sourceEphemeralPublicKey"),
            MAX_PUBLIC_KEY_BYTES,
        )
        try {
            require(nonce.size == NONCE_BYTES) { "Pairing request nonce is invalid" }
            java.security.KeyFactory.getInstance("EC").generatePublic(
                java.security.spec.X509EncodedKeySpec(ephemeralPublicKey),
            )
        } finally {
            nonce.fill(0)
            ephemeralPublicKey.fill(0)
        }
        return value
    }

    private fun validateRequest(request: JSONObject, now: Long) {
        val expiry = request.getLong("expiresAt")
        require(expiry > now && expiry - now <= LIFETIME_MILLIS) {
            "Pairing request has expired or has an invalid lifetime"
        }
    }

    private fun parseObject(encoded: String): JSONObject {
        require(encoded.length <= MAX_QR_LENGTH) { "Pairing QR is too large" }
        return JSONObject(encoded)
    }

    private fun transcript(
        sourceDeviceId: String,
        targetDeviceId: String,
        transactionId: String,
        sourceNonce: String,
        targetNonce: String,
        expiresAt: Long,
        publicKey: String,
        sourceEphemeralPublicKey: String,
        targetEphemeralPublicKey: String,
    ): ByteArray = listOf(
        DOMAIN,
        sourceDeviceId,
        targetDeviceId,
        transactionId,
        sourceNonce,
        targetNonce,
        expiresAt.toString(),
        publicKey,
        sourceEphemeralPublicKey,
        targetEphemeralPublicKey,
    ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)

    private fun sasCode(transcript: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(transcript)
        return try {
            val value = ((digest[0].toLong() and 0xff) shl 24) or
                ((digest[1].toLong() and 0xff) shl 16) or
                ((digest[2].toLong() and 0xff) shl 8) or
                (digest[3].toLong() and 0xff)
            "%06d".format(value % 1_000_000L)
        } finally {
            digest.fill(0)
        }
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            actual.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun alias(deviceId: String) = "$KEY_ALIAS_PREFIX$deviceId"

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(
        bytes,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun decode(value: String, maxBytes: Int): ByteArray {
        require(value.length <= maxBytes * 2) { "Pairing field is too large" }
        return Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private companion object {
        const val PROTOCOL = "INCHAT-PAIR-1"
        const val DOMAIN = "INCHAT-DESTINATION-PAIRING-PROOF-V1"
        val DESTINATION_CONFIRMATION_SUFFIX =
            "\nINCHAT-DESTINATION-USER-CONFIRMED-V1".toByteArray(StandardCharsets.UTF_8)
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS_PREFIX = "inchat-device-pairing-v1-"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val NONCE_BYTES = 32
        const val MAX_PUBLIC_KEY_BYTES = 256
        const val MAX_SIGNATURE_BYTES = 128
        const val TRANSFER_KEY_BYTES = 32
        const val MAX_QR_LENGTH = 4096
        const val LIFETIME_MILLIS = 2 * 60 * 1000L
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
        val TRANSACTION_ID = Regex("[A-Za-z0-9_-]{16,64}")
    }
}
