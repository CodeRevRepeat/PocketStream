package com.pocketstream.tv.receiver

import android.util.Log
import com.pocketstream.tv.util.TvCapabilityDetector
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class TvReceiverServer(
    port: Int = 8085,
    private val onPlayRequested: (streamUrl: String, title: String, durationMs: Long, startPositionMs: Long) -> Unit,
    private val onControlRequested: (action: String, positionMs: Long) -> Unit,
    private val getPlaybackStatus: () -> Triple<Boolean, Long, Long>,
    private val onPairRequested: (phoneIp: String, phonePort: Int, deviceName: String) -> Unit = { _, _, _ -> },
    private val onDisconnectRequested: () -> Unit = {},
    private val onRefreshRequested: () -> Unit = {}
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "TvReceiverServer"
    }

    private fun addCorsHeaders(res: Response): Response {
        res.addHeader("Access-Control-Allow-Origin", "*")
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, HEAD, OPTIONS")
        res.addHeader("Access-Control-Allow-Headers", "Range, Content-Type, Accept")
        return res
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            return addCorsHeaders(res)
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
                Log.i(TAG, "Received disconnect request from phone")
                onDisconnectRequested()
                newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"disconnected\"}")
            }

            uri == "/refresh" && method == Method.POST -> {
                Log.i(TAG, "Received refresh request from phone")
                onRefreshRequested()
                newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"refreshed\"}")
            }

            uri == "/play" && method == Method.POST -> {
                val map = mutableMapOf<String, String>()
                session.parseBody(map)
                val postData = map["postData"] ?: ""
                try {
                    val json = JSONObject(postData)
                    val streamUrl = json.getString("streamUrl")
                    val title = json.optString("title", "PocketStream Video")
                    val durationMs = json.optLong("durationMs", 0L)
                    val startPositionMs = json.optLong("startPositionMs", 0L)
                    onPlayRequested(streamUrl, title, durationMs, startPositionMs)
                    newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}")
                } catch (e: Exception) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid JSON: ${e.message}")
                }
            }

            uri == "/control" && method == Method.POST -> {
                val map = mutableMapOf<String, String>()
                session.parseBody(map)
                val postData = map["postData"] ?: ""
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
                val (playing, pos, len) = getPlaybackStatus()
                val json = JSONObject().apply {
                    put("isPlaying", playing)
                    put("positionMs", pos)
                    put("durationMs", len)
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            }

            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found: $uri")
            }
        }
        return addCorsHeaders(response)
    }
}