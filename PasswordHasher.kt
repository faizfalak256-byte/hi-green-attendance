package com.example.util

import android.os.Build
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Cryptographic salted PBKDF2 password hashing utility.
 * Never stores or compares plain-text passwords in Firestore.
 */
object PasswordHasher {
    private const val SALT_BYTES = 16
    private const val ITERATIONS = 65536
    private const val KEY_LENGTH_BITS = 256
    private const val ALG_SHA256 = "pbkdf2_sha256"
    private const val ALG_SHA1 = "pbkdf2_sha1"

    fun hash(rawPassword: String): String {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        val (algTag, factoryAlg) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ALG_SHA256 to "PBKDF2WithHmacSHA256"
        } else {
            ALG_SHA1 to "PBKDF2WithHmacSHA1"
        }
        val derived = deriveKey(rawPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS, factoryAlg)
        return "$algTag$$ITERATIONS$${salt.toHex()}$${derived.toHex()}"
    }

    fun verify(rawPassword: String, storedDigest: String): Boolean {
        val parts = storedDigest.split('$')
        if (parts.size != 4) return false
        val algTag = parts[0]
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = parts[2].fromHex() ?: return false
        val expectedHash = parts[3].fromHex() ?: return false

        val factoryAlg = when (algTag) {
            ALG_SHA256 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "PBKDF2WithHmacSHA256" else return false
            ALG_SHA1 -> "PBKDF2WithHmacSHA1"
            else -> return false
        }
        val actualHash = deriveKey(
            rawPassword.toCharArray(),
            salt,
            iterations,
            expectedHash.size * 8,
            factoryAlg
        )
        return MessageDigest.isEqual(actualHash, expectedHash)
    }

    private fun deriveKey(
        password: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
        algorithm: String
    ): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i ->
                substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
