package com.pocketstream.protocol

import org.json.JSONObject

data class ServerStatus(
    val isRunning: Boolean,
    val host: String,
    val port: Int,
    val deviceName: String = "PocketStream Mobile",
    val version: String = "1.0.0"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("isRunning", isRunning)
        put("host", host)
        put("port", port)
        put("deviceName", deviceName)
        put("version", version)
    }

    companion object {
        fun fromJson(json: JSONObject): ServerStatus = ServerStatus(
            isRunning = json.optBoolean("isRunning", false),
            host = json.optString("host", "127.0.0.1"),
            port = json.optInt("port", 8080),
            deviceName = json.optString("deviceName", "PocketStream Mobile"),
            version = json.optString("version", "1.0.0")
        )
    }
}