package com.example.inchat.data.security

import com.example.inchat.data.model.RecoveryCodeSet
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

object RecoveryCodeManager {

    private const val CODE_COUNT = 5

    /*
     * 32-character alphabet.
     *
     * Excluded:
     *
     * 0
     * 1
     * I
     * O
     *
     * This makes codes easier for users to read and type.
     */
    private const val ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private const val RANDOM_CODE_LENGTH = 16

    /*
     * 128-bit random salt.
     */
    private const val SALT_SIZE_BYTES = 16

    private val secureRandom =
        SecureRandom()

    /*
     * Generates a complete set of recovery codes.
     *
     * The plaintext codes are returned only so the UI can show
     * them to the user.
     *
     * The hashes are what should eventually be stored remotely.
     */
    fun generateRecoveryCodeSet(): RecoveryCodeSet {

        val salt =
            ByteArray(
                SALT_SIZE_BYTES
            )

        secureRandom.nextBytes(
            salt
        )

        val saltHex =
            bytesToHex(
                salt
            )

        val codes =
            mutableListOf<String>()

        val hashes =
            mutableListOf<String>()

        repeat(
            CODE_COUNT
        ) {

            val code =
                generateCode()

            val normalizedCode =
                normalizeCode(
                    code
                )

            val hash =
                hashCode(
                    salt = salt,
                    normalizedCode = normalizedCode
                )

            codes.add(
                code
            )

            hashes.add(
                hash
            )
        }

        return RecoveryCodeSet(
            codes =
                codes,

            saltHex =
                saltHex,

            codeHashesHex =
                hashes
        )
    }

    /*
     * Verifies a supplied recovery code against one stored hash.
     *
     * This helper will also be useful later for local tests.
     */
    fun verifyCode(
        code: String,
        saltHex: String,
        storedHashHex: String
    ): Boolean {

        if (
            code.isBlank() ||
            saltHex.isBlank() ||
            storedHashHex.isBlank()
        ) {
            return false
        }

        return try {

            val salt =
                hexToBytes(
                    saltHex
                )

            val normalizedCode =
                normalizeCode(
                    code
                )

            val calculatedHash =
                hashCode(
                    salt = salt,
                    normalizedCode = normalizedCode
                )

            constantTimeEquals(
                calculatedHash,
                storedHashHex
            )

        } catch (
            _: Exception
        ) {

            false
        }
    }

    /*
     * Normalizes a code before hashing.
     *
     * Users can type:
     *
     * inch-abcd-efgh-ijkl-mnop
     *
     * or:
     *
     * INCH-ABCD-EFGH-IJKL-MNOP
     *
     * Both are treated identically.
     */
    fun normalizeCode(
        code: String
    ): String {

        return code
            .trim()
            .uppercase()
            .replace(
                "-",
                ""
            )
            .removePrefix(
                "INCH"
            )
    }

    /*
     * Generates 16 random characters.
     *
     * 16 characters × 5 bits = 80 bits of randomness.
     */
    private fun generateCode(): String {

        val rawCode =
            StringBuilder(
                RANDOM_CODE_LENGTH
            )

        repeat(
            RANDOM_CODE_LENGTH
        ) {

            val index =
                secureRandom.nextInt(
                    ALPHABET.length
                )

            rawCode.append(
                ALPHABET[index]
            )
        }

        /*
         * 16 characters displayed as four groups.
         *
         * Example:
         *
         * INCH-A7F2-9KQP-X4M8-2PLD
         */
        return buildString {

            append(
                "INCH-"
            )

            append(
                rawCode.substring(
                    0,
                    4
                )
            )

            append("-")

            append(
                rawCode.substring(
                    4,
                    8
                )
            )

            append("-")

            append(
                rawCode.substring(
                    8,
                    12
                )
            )

            append("-")

            append(
                rawCode.substring(
                    12,
                    16
                )
            )
        }
    }

    /*
     * SHA-256(salt || normalizedCode)
     */
    private fun hashCode(
        salt: ByteArray,
        normalizedCode: String
    ): String {

        val messageDigest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        messageDigest.update(
            salt
        )

        messageDigest.update(
            normalizedCode.toByteArray(
                StandardCharsets.UTF_8
            )
        )

        return bytesToHex(
            messageDigest.digest()
        )
    }

    /*
     * Constant-time string comparison.
     *
     * This avoids returning early when characters differ.
     */
    private fun constantTimeEquals(
        first: String,
        second: String
    ): Boolean {

        val firstBytes =
            first
                .lowercase()
                .toByteArray(
                    StandardCharsets.US_ASCII
                )

        val secondBytes =
            second
                .lowercase()
                .toByteArray(
                    StandardCharsets.US_ASCII
                )

        if (
            firstBytes.size !=
            secondBytes.size
        ) {
            return false
        }

        var result = 0

        for (
        index in firstBytes.indices
        ) {

            result =
                result or
                        (
                                firstBytes[index]
                                    .toInt()
                                        xor
                                        secondBytes[index]
                                            .toInt()
                                )
        }

        return result == 0
    }

    private fun bytesToHex(
        bytes: ByteArray
    ): String {

        val result =
            StringBuilder(
                bytes.size * 2
            )

        for (
        byte in bytes
        ) {

            result.append(
                "%02x".format(
                    byte
                )
            )
        }

        return result.toString()
    }

    private fun hexToBytes(
        hex: String
    ): ByteArray {

        val cleanHex =
            hex.trim()

        if (
            cleanHex.isEmpty() ||
            cleanHex.length % 2 != 0
        ) {
            throw IllegalArgumentException(
                "Invalid hexadecimal value"
            )
        }

        if (
            !cleanHex.matches(
                Regex(
                    "^[0-9a-fA-F]+$"
                )
            )
        ) {
            throw IllegalArgumentException(
                "Invalid hexadecimal value"
            )
        }

        return ByteArray(
            cleanHex.length / 2
        ) { index ->

            cleanHex
                .substring(
                    index * 2,
                    index * 2 + 2
                )
                .toInt(
                    16
                )
                .toByte()
        }
    }
}