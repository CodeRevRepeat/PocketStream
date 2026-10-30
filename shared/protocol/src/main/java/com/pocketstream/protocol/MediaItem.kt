package com.pocketstream.protocol

import org.json.JSONObject

data class MediaItem(
    val id: String,
    val title: String,
    val sizeBytes: Long,
    val mimeType: String,
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val streamPath: String = "/stream/$id"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("sizeBytes", sizeBytes)
        put("mimeType", mimeType)
        put("durationMs", durationMs)
        put("width", width)
        put("height", height)
        put("videoCodec", videoCodec)
        put("audioCodec", audioCodec)
        put("streamPath", streamPath)
    }

    companion object {
        fun fromJson(json: JSONObject): MediaItem {
            val mediaId = json.getString("id")
            return MediaItem(
                id = mediaId,
                title = json.optString("title", "Untitled"),
                sizeBytes = json.optLong("sizeBytes", 0L),
                mimeType = json.optString("mimeType", "video/mp4"),
                durationMs = json.optLong("durationMs", 0L),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                videoCodec = json.optString("videoCodec", ""),
                audioCodec = json.optString("audioCodec", ""),
                streamPath = json.optString("streamPath", "/stream/$mediaId")
            )
        }
    }
}