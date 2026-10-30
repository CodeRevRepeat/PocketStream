package com.pocketstream.protocol

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

object DiscoveryConstants {
    const val UDP_DISCOVERY_PORT = 8088
    const val NSD_SERVICE_TYPE_TV = "_pocketstream-tv._tcp."
    const val NSD_SERVICE_TYPE_PHONE = "_pocketstream-phone._tcp."
    const val QR_SCHEME = "pocketstream://connect"
}

data class TvInfo(
    val ip: String,
    val port: Int = 8085,
    val name: String = "PocketStream TV"
) {
    fun toQrString(): String {
        val encodedName = try {
            URLEncoder.encode(name, "UTF-8")
        } catch (_: Exception) {
            name
        }
        return "${DiscoveryConstants.QR_SCHEME}?ip=$ip&port=$port&name=$encodedName"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "TV_ANNOUNCE")
        put("ip", ip)
        put("port", port)
        put("name", name)
    }

    companion object {
        fun fromJson(json: JSONObject): TvInfo? {
            val ip = json.optString("ip", "")
            if (ip.isEmpty()) return null
            val port = json.optInt("port", 8085)
            val name = json.optString("name", "PocketStream TV")
            return TvInfo(ip, port, name)
        }

        fun fromQrString(qrString: String): TvInfo? {
            return try {
                if (!qrString.startsWith(DiscoveryConstants.QR_SCHEME)) return null
                val query = qrString.substringAfter("?", "")
                if (query.isEmpty()) return null

                var ip = ""
                var port = 8085
                var name = "PocketStream TV"

                val pairs = query.split("&")
                for (pair in pairs) {
                    val parts = pair.split("=")
                    if (parts.size == 2) {
                        val k = parts[0]
                        val v = URLDecoder.decode(parts[1], "UTF-8")
                        when (k) {
                            "ip" -> ip = v
                            "port" -> port = v.toIntOrNull() ?: 8085
                            "name" -> name = v
                        }
                    }
                }
                if (ip.isNotEmpty()) TvInfo(ip, port, name) else null
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class PhoneInfo(
    val ip: String,
    val port: Int = 8080,
    val name: String = "PocketStream Phone"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "PHONE_ANNOUNCE")
        put("ip", ip)
        put("port", port)
        put("name", name)
    }

    companion object {
        fun fromJson(json: JSONObject): PhoneInfo? {
            val ip = json.optString("ip", "")
            if (ip.isEmpty()) return null
            val port = json.optInt("port", 8080)
            val name = json.optString("name", "PocketStream Phone")
            return PhoneInfo(ip, port, name)
        }
    }
}
