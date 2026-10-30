package com.pocketstream.protocol

import org.json.JSONObject

enum class ControlAction {
    PLAY, PAUSE, SEEK, STOP, RESUME
}

data class PlaybackControl(
    val action: ControlAction,
    val positionMs: Long = 0L,
    val mediaId: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("action", action.name)
        put("positionMs", positionMs)
        mediaId?.let { put("mediaId", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): PlaybackControl = PlaybackControl(
            action = ControlAction.valueOf(json.getString("action")),
            positionMs = json.optLong("positionMs", 0L),
            mediaId = if (json.has("mediaId")) json.getString("mediaId") else null
        )
    }
}