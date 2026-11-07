package com.pocketstream.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AuthProtocol {
    const val VERSION = 2
    const val HEADER_SESSION = "X-PS-Session"
    const val HEADER_TIMESTAMP = "X-PS-Timestamp"
    const val HEADER_NONCE = "X-PS-Nonce"
    const val HEADER_SIGNATURE = "X-PS-Signature"
    const val MAX_TIMESTAMP_SKEW_SEC = 30L

    private val secureRandom = SecureRandom()

    fun generateSecureKey(): String {
        val bytes = ByteArray(32) // 256 bits
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun generateNonce(): String {
        val bytes = ByteArray(12)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun computeSignature(
        key: String,
        method: String,
        path: String,
        timestamp: Long,
        nonce: String,
        bodyHash: String = ""
    ): String {
        val message = "${method.uppercase()}\n$path\n$timestamp\n$nonce\n$bodyHash"
        return hmacSha256(key, message)
    }

    fun sha256Hex(data: String): String {
        if (data.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun verifySignature(
        key: String,
        method: String,
        path: String,
        timestamp: Long,
        nonce: String,
        bodyHash: String,
        candidateSignature: String,
        currentEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        if (Math.abs(currentEpochSeconds - timestamp) > MAX_TIMESTAMP_SKEW_SEC) {
            return false
        }
        val expected = computeSignature(key, method, path, timestamp, nonce, bodyHash)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            candidateSignature.toByteArray(Charsets.UTF_8)
        )
    }

    private fun hmacSha256(key: String, data: String): String {
        val keyBytes = try {
            Base64.getUrlDecoder().decode(key)
        } catch (_: Exception) {
            key.toByteArray(Charsets.UTF_8)
        }
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(keyBytes, "HmacSHA256")
        mac.init(secretKey)
        val rawHmac = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac)
    }
}
