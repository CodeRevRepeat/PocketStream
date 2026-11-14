package com.pocketstream.tv.receiver

import android.util.Log
import com.pocketstream.tv.util.TvCapabilityDetector
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

data class TvPlaybackInfo(
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val title: String = "",
    val streamUrl: String = ""
)

class TvReceiverServer(
    port: Int = 8085,
    private val getConnectedPhoneIp: () -> String?,
    private val getSessionKey: () -> String = { "" },
    private val getSessionId: () -> String = { "" },
    private val onPlayRequested: (streamUrl: String, title: String, durationMs: Long, startPositionMs: Long, audioUrl: String?) -> Unit,
    private val onControlRequested: (action: String, positionMs: Long) -> Unit,
    private val getPlaybackStatus: () -> TvPlaybackInfo,
    private val onPairRequested: (phoneIp: String, phonePort: Int, deviceName: String) -> Unit = { _, _, _ -> },
    private val onDisconnectRequested: () -> Unit = {},
    private val onRefreshRequested: () -> Unit = {}
) : NanoHTTPD(port) {

    private val seenNonces = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())
    private val failedAttempts = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>() // IP -> (count, lockoutUntil)
    @Volatile private var immediatePairedPhoneIp: String? = null

    private fun isIpLockedOut(clientIp: String): Boolean {
        val entry = failedAttempts[clientIp] ?: return false
        val (count, lockoutUntil) = entry
        val now = System.currentTimeMillis()
        if (now < lockoutUntil) return true
        if (count >= 5 && now >= lockoutUntil) {
            failedAttempts.remove(clientIp)
        }
        return false
    }

    private fun recordFailedAttempt(clientIp: String) {
        val now = System.currentTimeMillis()
        val entry = failedAttempts[clientIp]
        val currentCount = if (entry != null && now < entry.second + 300_000L) entry.first + 1 else 1
        val lockoutUntil = if (currentCount >= 5) now + 60_000L else now
        failedAttempts[clientIp] = Pair(currentCount, lockoutUntil)
    }

    private fun verifyRequest(session: IHTTPSession, postData: String = ""): Boolean {
        val activeKey = getSessionKey()
        if (activeKey.isEmpty()) return true
        val headers = session.headers
        val reqSession = headers[com.pocketstream.protocol.AuthProtocol.HEADER_SESSION.lowercase()]
            ?: headers[com.pocketstream.protocol.AuthProtocol.HEADER_SESSION] ?: ""
        val expectedSession = getSessionId()
        if (expectedSession.isNotEmpty() && reqSession.isNotEmpty() && reqSession != expectedSession) {
            return false
        }
        val timestampStr = headers[com.pocketstream.protocol.AuthProtocol.HEADER_TIMESTAMP.lowercase()]
            ?: headers[com.pocketstream.protocol.AuthProtocol.HEADER_TIMESTAMP] ?: ""
        val timestamp = timestampStr.toLongOrNull() ?: return false
        val nonce = headers[com.pocketstream.protocol.AuthProtocol.HEADER_NONCE.lowercase()]
            ?: headers[com.pocketstream.protocol.AuthProtocol.HEADER_NONCE] ?: ""
        if (nonce.isEmpty()) return false
        val sig = headers[com.pocketstream.protocol.AuthProtocol.HEADER_SIGNATURE.lowercase()]
            ?: headers[com.pocketstream.protocol.AuthProtocol.HEADER_SIGNATURE] ?: ""
        if (sig.isEmpty()) return false

        synchronized(seenNonces) {
            if (seenNonces.contains(nonce)) return false
            if (seenNonces.size > 200) {
                val it = seenNonces.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            seenNonces.add(nonce)
        }

        val bodyHash = if (postData.isNotEmpty()) com.pocketstream.protocol.AuthProtocol.sha256Hex(postData) else ""
        val valid = com.pocketstream.protocol.AuthProtocol.verifySignature(
            key = activeKey,
            method = session.method.name,
            path = session.uri,
            timestamp = timestamp,
            nonce = nonce,
            bodyHash = bodyHash,
            candidateSignature = sig
        )
        if (valid) {
            failedAttempts.remove(session.remoteIpAddress)
        }
        return valid
    }

    companion object {
        private const val TAG = "TvReceiverServer"

        fun cleanIp(ip: String?): String {
            if (ip == null) return ""
            var cleaned = ip.trim()
            if (cleaned.startsWith("/")) cleaned = cleaned.substring(1)
            if (cleaned.startsWith("::ffff:")) cleaned = cleaned.removePrefix("::ffff:")
            return cleaned
        }

        fun isSameIp(ip1: String?, ip2: String?): Boolean {
            val c1 = cleanIp(ip1)
            val c2 = cleanIp(ip2)
            if (c1.isEmpty() || c2.isEmpty()) return true
            return c1.equals(c2, ignoreCase = true)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "OPTIONS Not Allowed")
        }

        val clientIp = session.remoteIpAddress
        if (uri != "/pair" && isIpLockedOut(clientIp)) {
            Log.w(TAG, "Rejecting request from $clientIp: IP is temporarily locked out")
            return newFixedLengthResponse(Response.Status.TOO_MANY_REQUESTS, "application/json", "{\"status\":\"locked_out\"}")
        }

        val response = when {
            uri == "/pair" && method == Method.POST -> {
                val map = mutableMapOf<String, String>()
                session.parseBody(map)
                val postData = map["postData"] ?: ""
                try {
                    val json = JSONObject(postData)
                    val phoneIp = json.getString("phoneIp")
                    val phonePort = json.optInt("phonePort", 8080)
                    val deviceName = json.optString("deviceName", "PocketStream Phone")
                    val clientKey = json.optString("sessionKey", "").trim()

                    val activeKey = getSessionKey().trim()
                    Log.i(TAG, "Pair attempt: clientKey='$clientKey' vs activeKey='$activeKey', postData='$postData'")
                    val isAuthed = activeKey.isEmpty() || clientKey == activeKey || verifyRequest(session, postData)
                    if (!isAuthed) {
                        recordFailedAttempt(clientIp)
                        Log.w(TAG, "Rejecting /pair from $clientIp: Invalid or missing session key")
                        return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"status\":\"unauthorized\"}")
                    }

                    // Reset failed attempt count upon successful pairing
                    failedAttempts.remove(clientIp)

                    val currentConnected = getConnectedPhoneIp()
                    if (!currentConnected.isNullOrEmpty() && !isSameIp(currentConnected, phoneIp)) {
                        Log.w(TAG, "Rejecting /pair from $phoneIp: TV is already connected to $currentConnected")
                        val conflictJson = JSONObject().apply {
                            put("status", "busy")
                            put("message", "TV is already connected to another device ($currentConnected)")
                        }
                        return newFixedLengthResponse(Response.Status.CONFLICT, "application/json", conflictJson.toString())
                    }

                    immediatePairedPhoneIp = phoneIp
                    onPairRequested(phoneIp, phonePort, deviceName)

                    val caps = TvCapabilityDetector.getCapabilities()
                    val resJson = JSONObject().apply {
                        put("status", "paired")
                        put("tvName", caps.deviceModel)
                        put("capabilities", caps.toJson())
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", resJson.toString())
                } catch (e: Exception) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid JSON: ${e.message}")
                }
            }

            uri == "/capabilities" && method == Method.GET -> {
                val caps = TvCapabilityDetector.getCapabilities()
                newFixedLengthResponse(Response.Status.OK, "application/json", caps.toJson().toString())
            }

            uri == "/disconnect" && method == Method.POST -> {
                immediatePairedPhoneIp = null
                val currentConnected = getConnectedPhoneIp()
                if (currentConnected.isNullOrEmpty() || isSameIp(currentConnected, clientIp)) {
                    Log.i(TAG, "Received disconnect request from phone ($clientIp)")
                    onDisconnectRequested()
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"disconnected\"}")
                } else {
                    Log.w(TAG, "Ignoring disconnect request from non-connected phone ($clientIp vs $currentConnected)")
                    newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"status\":\"forbidden\"}")
                }
            }

            uri == "/refresh" && method == Method.POST -> {
                val map = mutableMapOf<String, String>()
                session.parseBody(map)
                val postData = map["postData"] ?: ""
                if (!verifyRequest(session, postData)) {
                    recordFailedAttempt(clientIp)
                    newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"status\":\"unauthorized\"}")
                } else {
                    Log.i(TAG, "Received refresh request from phone")
                    onRefreshRequested()
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"refreshed\"}")
                }
            }

            uri == "/play" && method == Method.POST -> {
                val map = mutableMapOf<String, String>()
                session.parseBody(map)
                val postData = map["postData"] ?: ""
                val currentConnected = getConnectedPhoneIp()
                if (!currentConnected.isNullOrEmpty() && !isSameIp(currentConnected, clientIp)) {
                    Log.w(TAG, "Rejecting /play from unauthorized device ($clientIp vs $currentConnected)")
                    val conflictJson = JSONObject().apply {
                        put("status", "busy")
                        put("message", "TV is already connected to another device")
                    }
                    return newFixedLengthResponse(Response.Status.CONFLICT, "application/json", conflictJson.toString())
                }
                if (!verifyRequest(session, postData)) {
                    recordFailedAttempt(clientIp)
                    Log.w(TAG, "Rejecting /play from $clientIp: Invalid authentication signature")
                    return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"status\":\"unauthorized\"}")
                }
                try {
                    val json = JSONObject(postData)
                    val streamUrl = json.getString("streamUrl")
                    val title = json.optString("title", "PocketStream Video")
                    val durationMs = json.optLong("durationMs", 0L)
                    val startPositionMs = json.optLong("startPositionMs", 0L)
                    val audioUrl = json.optString("audioUrl", "").ifEmpty { null }
                    onPlayRequested(streamUrl, title, durationMs, startPositionMs, audioUrl)
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                } catch (e: Exception) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid JSON: ${e.message}")
                }
            }

            uri == "/control" && method == Method.POST -> {
                val map = mutableMapOf<String, String>()
                session.parseBody(map)
                val postData = map["postData"] ?: ""
                val currentConnected = getConnectedPhoneIp()
                if (!currentConnected.isNullOrEmpty() && !isSameIp(currentConnected, clientIp)) {
                    Log.w(TAG, "Rejecting /control from unauthorized device ($clientIp vs $currentConnected)")
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", "{\"status\":\"forbidden\"}")
                }
                if (!verifyRequest(session, postData)) {
                    recordFailedAttempt(clientIp)
                    Log.w(TAG, "Rejecting /control from $clientIp: Invalid authentication signature")
                    return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", "{\"status\":\"unauthorized\"}")
                }
                try {
                    val json = JSONObject(postData)
                    val action = json.getString("action")
                    val pos = json.optLong("positionMs", 0L)
                    onControlRequested(action, pos)
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                } catch (e: Exception) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid JSON: ${e.message}")
                }
            }

            uri == "/status" && method == Method.GET -> {
                val status = getPlaybackStatus()
                val connectedPhone = immediatePairedPhoneIp ?: getConnectedPhoneIp()
                val json = JSONObject().apply {
                    put("isPlaying", status.isPlaying)
                    put("positionMs", status.positionMs)
                    put("durationMs", status.durationMs)
                    put("title", status.title)
                    put("streamUrl", status.streamUrl)
                    put("connectedPhoneIp", connectedPhone ?: "")
                    put("isPhoneConnected", !connectedPhone.isNullOrEmpty())
                    put("sessionId", getSessionId())
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            }

            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: $uri")
            }
        }
        return response
    }
}