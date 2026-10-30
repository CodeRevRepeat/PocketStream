package com.pocketstream.protocol

import org.json.JSONArray
import org.json.JSONObject

data class CompatibilityResult(
    val isCompatible: Boolean,
    val unsupportedReasons: List<String>
)

data class TvCapabilities(
    val deviceModel: String = "Unknown TV",
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1088,
    val maxFps: Int = 60,
    val supports10BitHevc: Boolean = false,
    val supportsHdr: Boolean = false,
    val supports4K: Boolean = false,
    val supportedVideoMimes: List<String> = listOf("video/avc", "video/hevc", "video/mp4v-es", "video/x-vnd.on2.vp9"),
    val hardwareDecoders: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceModel", deviceModel)
        put("maxWidth", maxWidth)
        put("maxHeight", maxHeight)
        put("maxFps", maxFps)
        put("supports10BitHevc", supports10BitHevc)
        put("supportsHdr", supportsHdr)
        put("supports4K", supports4K)
        val arr = JSONArray()
        supportedVideoMimes.forEach { arr.put(it) }
        put("supportedVideoMimes", arr)
        val hwArr = JSONArray()
        hardwareDecoders.forEach { hwArr.put(it) }
        put("hardwareDecoders", hwArr)
    }

    /**
     * Evaluates video specifications against TV capabilities.
     * Returns a CompatibilityResult with reasons if unsupported.
     */
    fun checkCompatibility(
        videoMime: String?,
        width: Int,
        height: Int,
        is10Bit: Boolean
    ): CompatibilityResult {
        val reasons = mutableListOf<String>()

        // 1. 10-bit / HDR color check
        if (is10Bit && !supports10BitHevc) {
            reasons.add("10-bit color / HDR detected (TV hardware decoder only supports 8-bit)")
        }

        // 2. Resolution check
        val effW = if (width > 0) width else 0
        val effH = if (height > 0) height else 0
        if (effW > maxWidth || effH > maxHeight) {
            val resLabel = if (effW >= 3840 || effH >= 2160) "4K UHD (${effW}x${effH})" else "${effW}x${effH}"
            reasons.add("Resolution $resLabel exceeds TV hardware limit (${maxWidth}x${maxHeight})")
        }

        // 3. Codec MIME check if provided
        if (!videoMime.isNullOrEmpty()) {
            val normalizedMime = videoMime.lowercase().trim()
            val isKnown = supportedVideoMimes.any { it.equals(normalizedMime, ignoreCase = true) }
            // Some file types like mkv contain hevc or avc inside
            val isHevcOrAvc = normalizedMime.contains("hevc") || normalizedMime.contains("h265") ||
                    normalizedMime.contains("avc") || normalizedMime.contains("h264") ||
                    normalizedMime.contains("mp4") || normalizedMime.contains("matroska")
            if (!isKnown && !isHevcOrAvc && normalizedMime.startsWith("video/")) {
                reasons.add("Video codec '$videoMime' is not natively supported by TV hardware")
            }
        }

        return CompatibilityResult(
            isCompatible = reasons.isEmpty(),
            unsupportedReasons = reasons
        )
    }

    companion object {
        fun fromJson(json: JSONObject): TvCapabilities {
            val mimes = mutableListOf<String>()
            val arr = json.optJSONArray("supportedVideoMimes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    mimes.add(arr.getString(i))
                }
            }
            val hw = mutableListOf<String>()
            val hwArr = json.optJSONArray("hardwareDecoders")
            if (hwArr != null) {
                for (i in 0 until hwArr.length()) {
                    hw.add(hwArr.getString(i))
                }
            }
            return TvCapabilities(
                deviceModel = json.optString("deviceModel", "Android TV"),
                maxWidth = json.optInt("maxWidth", 1920),
                maxHeight = json.optInt("maxHeight", 1088),
                maxFps = json.optInt("maxFps", 60),
                supports10BitHevc = json.optBoolean("supports10BitHevc", false),
                supportsHdr = json.optBoolean("supportsHdr", false),
                supports4K = json.optBoolean("supports4K", false),
                supportedVideoMimes = if (mimes.isNotEmpty()) mimes else listOf("video/avc", "video/hevc", "video/mp4v-es", "video/x-vnd.on2.vp9"),
                hardwareDecoders = hw
            )
        }

        val DEFAULT_1080P = TvCapabilities(
            deviceModel = "Android TV",
            maxWidth = 1920,
            maxHeight = 1088,
            maxFps = 60,
            supports10BitHevc = false,
            supportsHdr = false,
            supports4K = false,
            supportedVideoMimes = listOf("video/avc", "video/hevc", "video/mp4v-es", "video/x-vnd.on2.vp9"),
            hardwareDecoders = emptyList()
        )
    }
}
