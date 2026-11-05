package com.example

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-based, authenticated encryption for UTF-8 text. */
object CipherShieldEngine {
    private val MAGIC_V3 = "CSv3".toByteArray(Charsets.US_ASCII)
    private const val HEADER_SIZE = 38
    private const val GCM_TAG_SIZE = 16
    private const val HMAC_SIZE = 64
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val KDF_PBKDF2_SHA512: Byte = 0x01
    private const val CURRENT_ITERATIONS = 600_000
    private const val MIN_ACCEPTED_ITERATIONS = 100_000
    private const val MAX_ACCEPTED_ITERATIONS = 2_000_000

    // Bound attacker-controlled allocation/CPU while retaining multi-megabyte config support.
    const val MAX_PLAINTEXT_BYTES = 5 * 1024 * 1024
    const val MAX_PASSWORD_CHARS = 1_024
    private const val MAX_DECODED_PAYLOAD_BYTES = MAX_PLAINTEXT_BYTES + HEADER_SIZE + GCM_TAG_SIZE + HMAC_SIZE
    private const val MAX_CANONICAL_BASE64_CHARS = ((MAX_DECODED_PAYLOAD_BYTES + 2) / 3) * 4
    private const val MAX_BASE64_CHARS = MAX_CANONICAL_BASE64_CHARS + 8_192
    const val MAX_ENCODED_PAYLOAD_CHARS = MAX_BASE64_CHARS
    private val secureRandom = SecureRandom()

    /** Returns a version only when the complete unauthenticated envelope is structurally safe. */
    fun detectVersion(input: String): String? {
        return try {
            val normalized = normalizeBase64(input)
            val decoded = Base64.decode(normalized, Base64.NO_WRAP)
            try {
                validateEnvelope(decoded)
            } finally {
                Arrays.fill(decoded, 0.toByte())
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Creates a CSv3 payload using AES-256-GCM and split-key HMAC-SHA512. */
    fun shield(plainText: String, password: CharArray): String {
        validatePassword(password)
        require(plainText.length <= MAX_PLAINTEXT_BYTES) { "Plaintext exceeds the 5 MiB safety limit" }
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(plainText))
        val plainBytes = try {
            require(encoded.remaining() <= MAX_PLAINTEXT_BYTES) { "Plaintext exceeds the 5 MiB safety limit" }
            ByteArray(encoded.remaining()).also(encoded::get)
        } finally {
            if (encoded.hasArray()) Arrays.fill(encoded.array(), 0.toByte())
        }

        val salt = ByteArray(SALT_SIZE)
        val iv = ByteArray(IV_SIZE)
        var derived: ByteArray? = null
        var aesBytes: ByteArray? = null
        var hmacBytes: ByteArray? = null
        var ciphertext: ByteArray? = null
        var authenticated: ByteArray? = null
        var hmac: ByteArray? = null
        var payload: ByteArray? = null
        try {
            secureRandom.nextBytes(salt)
            secureRandom.nextBytes(iv)
            derived = deriveKey(password, salt, CURRENT_ITERATIONS, 512)
            aesBytes = derived.copyOfRange(0, 32)
            hmacBytes = derived.copyOfRange(32, 64)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesBytes, "AES"), GCMParameterSpec(128, iv))
            ciphertext = cipher.doFinal(plainBytes)

            authenticated = ByteArray(HEADER_SIZE + ciphertext.size)
            System.arraycopy(MAGIC_V3, 0, authenticated, 0, MAGIC_V3.size)
            authenticated[4] = 0x03
            authenticated[5] = KDF_PBKDF2_SHA512
            writeIntBigEndian(CURRENT_ITERATIONS, authenticated, 6)
            System.arraycopy(salt, 0, authenticated, 10, SALT_SIZE)
            System.arraycopy(iv, 0, authenticated, 26, IV_SIZE)
            System.arraycopy(ciphertext, 0, authenticated, HEADER_SIZE, ciphertext.size)

            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(hmacBytes, "HmacSHA512"))
            hmac = mac.doFinal(authenticated)
            payload = ByteArray(authenticated.size + hmac.size)
            System.arraycopy(authenticated, 0, payload, 0, authenticated.size)
            System.arraycopy(hmac, 0, payload, authenticated.size, hmac.size)
            return Base64.encodeToString(payload, Base64.NO_WRAP)
        } finally {
            wipe(plainBytes, salt, iv, derived, aesBytes, hmacBytes, ciphertext, authenticated, hmac, payload)
        }
    }

    /** Decrypts authenticated CSv2/CSv3 payloads; CSv2 is retained for migration only. */
    fun unshield(payloadBase64: String, password: CharArray): String {
        validatePassword(password)
        val normalized = normalizeBase64(payloadBase64)
        val decoded = try {
            Base64.decode(normalized, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Payload is not valid Base64", e)
        }

        var salt: ByteArray? = null
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        var receivedHmac: ByteArray? = null
        var computedHmac: ByteArray? = null
        var hmacInput: ByteArray? = null
        var derived: ByteArray? = null
        var aesBytes: ByteArray? = null
        var hmacBytes: ByteArray? = null
        var decrypted: ByteArray? = null
        try {
            require(decoded.size <= MAX_DECODED_PAYLOAD_BYTES) { "Payload exceeds the 5 MiB safety limit" }
            val magic = validateEnvelope(decoded)
            val iterations = readIntBigEndian(decoded, 6)
            salt = decoded.copyOfRange(10, 10 + SALT_SIZE)
            iv = decoded.copyOfRange(26, 26 + IV_SIZE)

            if (magic == "CSv3") {
                val authenticatedSize = decoded.size - HMAC_SIZE
                ciphertext = decoded.copyOfRange(HEADER_SIZE, authenticatedSize)
                receivedHmac = decoded.copyOfRange(authenticatedSize, decoded.size)
                derived = deriveKey(password, salt, iterations, 512)
                aesBytes = derived.copyOfRange(0, 32)
                hmacBytes = derived.copyOfRange(32, 64)
                hmacInput = decoded.copyOfRange(0, authenticatedSize)
                val mac = Mac.getInstance("HmacSHA512")
                mac.init(SecretKeySpec(hmacBytes, "HmacSHA512"))
                computedHmac = mac.doFinal(hmacInput)
                if (!MessageDigest.isEqual(receivedHmac, computedHmac)) {
                    throw SecurityException("Integrity check failed. Incorrect password or modified ciphertext.")
                }
            } else {
                ciphertext = decoded.copyOfRange(HEADER_SIZE, decoded.size)
                derived = deriveKey(password, salt, iterations, 256)
                aesBytes = derived.copyOf()
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesBytes, "AES"), GCMParameterSpec(128, iv))
            decrypted = cipher.doFinal(ciphertext)
            val characters = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decrypted))
            return try {
                characters.toString()
            } finally {
                if (characters.hasArray()) Arrays.fill(characters.array(), '\u0000')
            }
        } finally {
            wipe(decoded, salt, iv, ciphertext, receivedHmac, computedHmac, hmacInput, derived, aesBytes, hmacBytes, decrypted)
        }
    }

    private fun validateEnvelope(decoded: ByteArray): String {
        require(decoded.size >= 4) { "Payload is too short to contain a header" }
        val magic = String(decoded, 0, 4, Charsets.US_ASCII)
        require(magic == "CSv2" || magic == "CSv3") { "Invalid payload signature" }
        val minimum = if (magic == "CSv3") HEADER_SIZE + GCM_TAG_SIZE + HMAC_SIZE else HEADER_SIZE + GCM_TAG_SIZE
        require(decoded.size >= minimum) { "$magic payload is truncated or corrupted" }
        require(decoded.size <= MAX_PLAINTEXT_BYTES + minimum) { "Payload exceeds the 5 MiB safety limit" }
        require(decoded[4].toInt() == if (magic == "CSv3") 3 else 2) { "Unsupported payload version" }
        require(decoded[5] == KDF_PBKDF2_SHA512) { "Unsupported key derivation function" }
        require(readIntBigEndian(decoded, 6) in MIN_ACCEPTED_ITERATIONS..MAX_ACCEPTED_ITERATIONS) {
            "Unsafe PBKDF2 work factor"
        }
        return magic
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int, bits: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, bits)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun validatePassword(password: CharArray) {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(password.size <= MAX_PASSWORD_CHARS) { "Password exceeds the safety limit" }
    }

    /** Accept line-wrapped payloads while rejecting non-canonical or ambiguous Base64. */
    private fun normalizeBase64(input: String): String {
        require(input.length <= MAX_BASE64_CHARS) { "Payload exceeds the 5 MiB safety limit" }
        val normalized = StringBuilder(input.length.coerceAtMost(MAX_CANONICAL_BASE64_CHARS))
        input.forEach { char ->
            when {
                char == ' ' || char == '\t' || char == '\r' || char == '\n' -> Unit
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
                    char == '+' || char == '/' || char == '=' -> {
                    require(normalized.length < MAX_CANONICAL_BASE64_CHARS) {
                        "Payload exceeds the 5 MiB safety limit"
                    }
                    normalized.append(char)
                }
                else -> throw IllegalArgumentException("Payload is not valid Base64")
            }
        }
        require(normalized.isNotEmpty()) { "Payload is empty" }
        require(normalized.length % 4 == 0) { "Payload is not canonical Base64" }
        val paddingIndex = normalized.indexOf("=")
        if (paddingIndex >= 0) {
            val paddingLength = normalized.length - paddingIndex
            require(paddingLength in 1..2 && (paddingIndex until normalized.length).all { normalized[it] == '=' }) {
                "Payload is not canonical Base64"
            }
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val lastValue = alphabet.indexOf(normalized[paddingIndex - 1])
            val unusedBits = if (paddingLength == 2) 15 else 3
            require(lastValue >= 0 && (lastValue and unusedBits) == 0) {
                "Payload has non-zero Base64 padding bits"
            }
        }
        return normalized.toString()
    }

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)

    private fun writeIntBigEndian(value: Int, bytes: ByteArray, offset: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun wipe(vararg arrays: ByteArray?) {
        arrays.forEach { bytes -> bytes?.let { Arrays.fill(it, 0.toByte()) } }
    }
}
