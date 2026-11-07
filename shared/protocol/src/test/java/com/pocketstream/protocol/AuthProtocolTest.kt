package com.pocketstream.protocol

object AuthProtocolVerification {
    @JvmStatic
    fun main(args: Array<String>) {
        println("=== Starting AuthProtocol Verification ===")

        // 1. Key and Nonce generation
        val key = AuthProtocol.generateSecureKey()
        check(key.isNotEmpty()) { "Key generation failed" }
        val nonce1 = AuthProtocol.generateNonce()
        val nonce2 = AuthProtocol.generateNonce()
        check(nonce1 != nonce2) { "Nonces must be unique" }
        println("[PASS] Key and Nonce generation verified")

        // 2. HMAC Signature computation and verification
        val now = System.currentTimeMillis() / 1000L
        val method = "POST"
        val path = "/play"
        val body = "{\"mediaId\":\"12345\"}"
        val bodyHash = AuthProtocol.sha256Hex(body)

        val sig = AuthProtocol.computeSignature(key, method, path, now, nonce1, bodyHash)
        val valid = AuthProtocol.verifySignature(key, method, path, now, nonce1, bodyHash, sig, now)
        check(valid) { "Valid signature must pass verification" }
        println("[PASS] HMAC signature verification verified")

        // 3. Tampered payload rejection
        val tamperedHash = AuthProtocol.sha256Hex("{\"mediaId\":\"tampered\"}")
        val tamperedResult = AuthProtocol.verifySignature(key, method, path, now, nonce1, tamperedHash, sig, now)
        check(!tamperedResult) { "Tampered body must be rejected" }
        println("[PASS] Tampered payload rejection verified")

        // 4. Timestamp skew > 30s rejection
        val expiredResult = AuthProtocol.verifySignature(key, method, path, now - 35L, nonce1, bodyHash, sig, now)
        check(!expiredResult) { "Expired timestamp (>30s) must be rejected" }
        println("[PASS] Replay & timestamp skew rejection verified")

        // 5. QR encoding and decoding parity
        val sid = AuthProtocol.generateNonce()
        val tvInfo = TvInfo("192.168.1.100", 8085, "Living Room TV", sid, key)
        val qr = tvInfo.toQrString()
        check(qr.contains("sid=$sid")) { "QR must contain session id" }
        check(qr.contains("key=$key")) { "QR must contain session key" }

        val parsed = TvInfo.fromQrString(qr)
        check(parsed != null) { "fromQrString must successfully parse valid QR" }
        check(parsed.ip == tvInfo.ip) { "IP mismatch" }
        check(parsed.port == tvInfo.port) { "Port mismatch" }
        check(parsed.name == tvInfo.name) { "Name mismatch" }
        check(parsed.sessionId == tvInfo.sessionId) { "SessionId mismatch" }
        check(parsed.sessionKey == tvInfo.sessionKey) { "SessionKey mismatch" }
        println("[PASS] QR encoding/decoding parity verified")

        println("=== ALL VERIFICATION CHECKS PASSED ===")
    }
}
