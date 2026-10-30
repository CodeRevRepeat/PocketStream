package com.pocketstream.tv.util

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import com.pocketstream.protocol.TvCapabilities

object TvCapabilityDetector {
    private const val TAG = "TvCapabilityDetector"
    @Volatile private var cachedCapabilities: TvCapabilities? = null

    @Synchronized
    fun getCapabilities(context: Context? = null): TvCapabilities {
        cachedCapabilities?.let { return it }

        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val hwList = mutableListOf<String>()
        val supportedMimes = mutableSetOf<String>()
        var maxWidth = 1920
        var maxHeight = 1088
        var maxFps = 60
        var supports10BitHevc = false
        var supportsHdr = false
        var supports4K = false

        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (codecInfo.isEncoder) continue
                val name = codecInfo.name
                val isHw = !name.startsWith("OMX.google.", ignoreCase = true) &&
                        !name.startsWith("c2.android.", ignoreCase = true) &&
                        !name.contains(".sw.", ignoreCase = true) &&
                        !name.contains("software", ignoreCase = true)

                if (isHw) {
                    hwList.add(name)
                }

                for (mime in codecInfo.supportedTypes) {
                    if (!mime.startsWith("video/", ignoreCase = true)) continue
                    supportedMimes.add(mime.lowercase())

                    try {
                        val caps = codecInfo.getCapabilitiesForType(mime)
                        val videoCaps = caps.videoCapabilities
                        if (videoCaps != null && isHw) {
                            val w = videoCaps.supportedWidths?.upper ?: 1920
                            val h = videoCaps.supportedHeights?.upper ?: 1088
                            if (w > maxWidth) maxWidth = w
                            if (h > maxHeight) maxHeight = h
                            val fps = videoCaps.supportedFrameRates?.upper?.toInt() ?: 60
                            if (fps > maxFps) maxFps = fps
                        }

                        // Check 10-bit HEVC in hardware
                        if (mime.equals("video/hevc", ignoreCase = true) && isHw) {
                            caps.profileLevels?.forEach { pl ->
                                if (pl.profile == CodecProfileLevel.HEVCProfileMain10 ||
                                    pl.profile == CodecProfileLevel.HEVCProfileMain10HDR10 ||
                                    pl.profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus) {
                                    supports10BitHevc = true
                                    supportsHdr = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error probing caps for $mime: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed inspecting MediaCodecList: ${e.message}", e)
        }

        // Realtek RTD chipset quirk check (only 8-bit supported on RTD series decoders)
        val isRealtekRtd = hwList.any { it.contains("realtek", ignoreCase = true) || it.contains("rtk", ignoreCase = true) } ||
                Build.HARDWARE.contains("rtk", ignoreCase = true) ||
                Build.HARDWARE.contains("realtek", ignoreCase = true) ||
                Build.BOARD.contains("rtk", ignoreCase = true) ||
                Build.BOARD.contains("realtek", ignoreCase = true)

        if (isRealtekRtd) {
            supports10BitHevc = false
            supportsHdr = false
            maxWidth = 1920.coerceAtMost(maxWidth)
            maxHeight = 1088.coerceAtMost(maxHeight)
        }

        supports4K = maxWidth >= 3840 && maxHeight >= 2160

        val caps = TvCapabilities(
            deviceModel = if (model.isNotEmpty()) model else "Android TV",
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            maxFps = maxFps,
            supports10BitHevc = supports10BitHevc,
            supportsHdr = supportsHdr,
            supports4K = supports4K,
            supportedVideoMimes = supportedMimes.toList(),
            hardwareDecoders = hwList.distinct()
        )

        Log.i(TAG, "Detected TV Capabilities: model=${caps.deviceModel}, maxRes=${caps.maxWidth}x${caps.maxHeight}, 4K=$supports4K, 10BitHEVC=$supports10BitHevc, HW Decoders=$hwList")
        cachedCapabilities = caps
        return caps
    }
}
