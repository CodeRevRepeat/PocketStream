package com.pocketstream.mobile.server

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.pocketstream.mobile.MainActivity
import com.pocketstream.mobile.service.ServerService
import com.pocketstream.mobile.storage.MediaRepository
import com.pocketstream.mobile.transcoder.TranscoderManager
import com.pocketstream.protocol.ServerStatus
import com.pocketstream.protocol.TvCapabilities
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.min

class PocketHttpServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "PocketHttpServer"
        private const val STREAM_BUFFER_SIZE = 64 * 1024 // 64 KB buffer for high-bitrate video streaming
        private val SAFE_MEDIA_ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")
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

        Log.d(TAG, "HTTP $method $uri (Range: ${session.headers["range"]})")

        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            return addCorsHeaders(res)
        }

        val response = when {
            uri == "/api/v1/status" && method == Method.GET -> {
                val ip = NetworkUtils.getLocalWifiIpAddress()
                val phoneName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "PocketStream Phone" }
                val status = ServerStatus(
                    isRunning = true,
                    host = ip,
                    port = listeningPort,
                    deviceName = "$phoneName / PocketStream",
                    version = "1.2.0"
                )
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    status.toJson().toString()
                )
            }

            uri == "/api/v1/library" && method == Method.GET -> {
                val items = MediaRepository.getAllMediaItems()
                val jsonArr = JSONArray()
                for (item in items) {
                    jsonArr.put(item.toJson())
                }
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    jsonArr.toString()
                )
            }

            uri.startsWith("/stream/live/") -> {
                val mediaId = uri.removePrefix("/stream/live/").substringBefore("/")
                if (!mediaId.matches(SAFE_MEDIA_ID_REGEX)) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid media ID format")
                } else {
                    handleLiveOrTranscodedStream(mediaId, session)
                }
            }

            uri.startsWith("/stream/transcoded/") -> {
                val mediaId = uri.removePrefix("/stream/transcoded/").substringBefore("/")
                if (!mediaId.matches(SAFE_MEDIA_ID_REGEX)) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid media ID format")
                } else {
                    handleLiveOrTranscodedStream(mediaId, session)
                }
            }

            uri.startsWith("/stream/") -> {
                val mediaId = uri.removePrefix("/stream/").substringBefore("/")
                if (!mediaId.matches(SAFE_MEDIA_ID_REGEX)) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid media ID format")
                } else {
                    handleStream(mediaId, session)
                }
            }

            uri == "/api/v1/transcode/status" && method == Method.GET -> {
                val mediaId = session.parms["id"] ?: ""
                val isTranscoding = TranscoderManager.isTranscoding(mediaId)
                val progress = TranscoderManager.getProgress(mediaId)
                val ready = TranscoderManager.getTranscodedFile(context, mediaId) != null
                val json = JSONObject().apply {
                    put("id", mediaId)
                    put("isTranscoding", isTranscoding)
                    put("progress", progress)
                    put("isReady", ready)
                }
                newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
            }

            uri == "/api/v1/play" && method == Method.POST -> {
                handlePlayRequest(session)
            }

            uri == "/api/v1/stop" && method == Method.POST -> {
                handleStopRequest(session)
            }

            else -> {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "text/plain",
                    "Not Found: $uri"
                )
            }
        }
        return addCorsHeaders(response)
    }

    private fun handlePlayRequest(session: IHTTPSession): Response {
        val map = mutableMapOf<String, String>()
        session.parseBody(map)
        val postData = map["postData"] ?: ""
        return try {
            val json = JSONObject(postData)
            val mediaId = json.getString("mediaId")
            val media = MediaRepository.getMediaById(mediaId)
                ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Media not found: $mediaId")

            val ip = NetworkUtils.getLocalWifiIpAddress()
            val prefs = context.getSharedPreferences("pocketstream_mobile_prefs", Context.MODE_PRIVATE)
            val savedCaps = prefs.getString("last_tv_caps", null)
            val tvCaps = if (!savedCaps.isNullOrEmpty()) {
                try {
                    TvCapabilities.fromJson(JSONObject(savedCaps))
                } catch (_: Exception) {
                    TvCapabilities.DEFAULT_1080P
                }
            } else {
                TvCapabilities.DEFAULT_1080P
            }

            // Probe duration if missing
            if (media.durationMs <= 0L) {
                try {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(context, media.uri)
                    val probedDuration = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (probedDuration > 0L) {
                        media.durationMs = probedDuration
                    }
                    mmr.release()
                } catch (_: Exception) {}
            }

            val is10Bit = media.is10Bit ||
                    media.title.contains("10bit", ignoreCase = true) ||
                    media.title.contains("10-bit", ignoreCase = true) ||
                    media.title.contains("hdr", ignoreCase = true)

            val codecToCheck = media.videoCodec.ifEmpty { media.mimeType }
            val compatibility = tvCaps.checkCompatibility(
                videoMime = codecToCheck,
                width = media.width,
                height = media.height,
                is10Bit = is10Bit
            )

            val isAlreadyTranscoded = TranscoderManager.getTranscodedFile(context, media.id) != null

            val resJson = JSONObject()
            if (!compatibility.isCompatible) {
                // UNSUPPORTED FORMAT: Auto-encode and stream to TV
                val streamUrl = if (isAlreadyTranscoded) {
                    "http://$ip:$listeningPort/stream/transcoded/${media.id}"
                } else {
                    // Start hardware transcode immediately on phone
                    TranscoderManager.startLiveTranscode(
                        context = context,
                        media = media,
                        onReadyToStream = {
                            Log.i(TAG, "Live auto-transcode ready for TV stream: ${media.title}")
                        },
                        onCompleted = {
                            Log.i(TAG, "Hardware auto-transcode completed for ${media.title}")
                        }
                    )
                    "http://$ip:$listeningPort/stream/live/${media.id}"
                }

                val notifMsg = "Encoding using phone and started the stream"
                ServerService.updateNotification(
                    "PocketStream Streaming",
                    notifMsg
                )

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, notifMsg, android.widget.Toast.LENGTH_LONG).show()
                }

                MainActivity.instance?.onTvPlaybackStarted(media.id, "[Converted] ${media.title}", media.durationMs)

                resJson.put("status", "ok")
                resJson.put("mode", "transcode")
                resJson.put("streamUrl", streamUrl)
                resJson.put("title", "[Converted] ${media.title}")
                resJson.put("durationMs", media.durationMs)
                resJson.put("message", notifMsg)

            } else {
                // SUPPORTED FORMAT: Automatically direct play
                val streamUrl = "http://$ip:$listeningPort/stream/${media.id}"
                val notifMsg = "Direct stream started."

                ServerService.updateNotification(
                    "PocketStream Streaming",
                    notifMsg
                )

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, notifMsg, android.widget.Toast.LENGTH_SHORT).show()
                }

                MainActivity.instance?.onTvPlaybackStarted(media.id, media.title, media.durationMs)

                resJson.put("status", "ok")
                resJson.put("mode", "direct")
                resJson.put("streamUrl", streamUrl)
                resJson.put("title", media.title)
                resJson.put("durationMs", media.durationMs)
                resJson.put("message", notifMsg)
            }

            newFixedLengthResponse(Response.Status.OK, "application/json", resJson.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error handling play request: ${e.message}", e)
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Error: ${e.message}")
        }
    }

    private fun handleStopRequest(session: IHTTPSession): Response {
        ServerService.updateNotification(
            "PocketStream Server Running",
            "Streaming idle"
        )
        MainActivity.instance?.onTvPlaybackStopped()
        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"stopped\"}")
    }

    private fun handleLiveOrTranscodedStream(mediaId: String, session: IHTTPSession): Response {
        // 1. If file has already finished transcoding, serve as standard static file with full Range seeking
        val completed = TranscoderManager.getTranscodedFile(context, mediaId)
        if (completed != null) {
            return serveLocalFile(completed, "video/mp4", session)
        }

        // 2. If actively transcoding or has data, stream live chunks
        val active = TranscoderManager.getActiveOrCompletedFile(context, mediaId)
        if (active != null) {
            var attempts = 0
            while (active.length() < 256 * 1024 && attempts < 40 && TranscoderManager.isTranscoding(mediaId)) {
                try {
                    Thread.sleep(50)
                } catch (ignored: Exception) {}
                attempts++
            }

            if (active.length() > 0) {
                Log.i(TAG, "Streaming live fragmented MP4 chunks to TV for $mediaId (${active.length()} bytes)")
                val stream = GrowingFileInputStream(active, mediaId)
                val res = newChunkedResponse(Response.Status.OK, "video/mp4", stream)
                res.addHeader("Accept-Ranges", "none")
                return res
            }
        }

        return newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE,
            "text/plain",
            "Stream is initializing, please retry shortly"
        )
    }

    private fun serveLocalFile(file: File, mimeType: String, session: IHTTPSession): Response {
        val totalSize = file.length()

        if (session.method == Method.HEAD) {
            val res = newFixedLengthResponse(Response.Status.OK, mimeType, "")
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Content-Length", totalSize.toString())
            return res
        }

        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.removePrefix("bytes=").trim()
            val parts = rangeVal.split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                min(parts[1].toLongOrNull() ?: (totalSize - 1), totalSize - 1)
            } else {
                totalSize - 1
            }

            if (start > end || start >= totalSize) {
                val res = newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    "text/plain",
                    "Range Not Satisfiable"
                )
                res.addHeader("Content-Range", "bytes */$totalSize")
                return res
            }

            val length = end - start + 1
            val fis = FileInputStream(file)
            fis.channel.position(start)
            val bufferedStream = BufferedInputStream(fis, STREAM_BUFFER_SIZE)
            val boundedStream = BoundedInputStream(bufferedStream, length, null)
            val res = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                boundedStream,
                length
            )
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Content-Range", "bytes $start-$end/$totalSize")
            res.addHeader("Content-Length", length.toString())
            return res
        }

        val fis = FileInputStream(file)
        val bufferedStream = BufferedInputStream(fis, STREAM_BUFFER_SIZE)
        val boundedStream = BoundedInputStream(bufferedStream, totalSize, null)
        val res = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            boundedStream,
            totalSize
        )
        res.addHeader("Accept-Ranges", "bytes")
        res.addHeader("Content-Length", totalSize.toString())
        return res
    }

    private fun handleStream(mediaId: String, session: IHTTPSession): Response {
        // If a progressive transcoded version exists, serve that directly for 100% smooth seekable playback!
        val completed = TranscoderManager.getTranscodedFile(context, mediaId)
        if (completed != null) {
            Log.i(TAG, "handleStream: Found completed progressive file for $mediaId, serving progressive MP4")
            return serveLocalFile(completed, "video/mp4", session)
        }

        val media = MediaRepository.getMediaById(mediaId) ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain",
            "Media not found: $mediaId"
        )

        // If 10-bit and actively transcoding, route to live stream so TV doesn't crash on raw 10-bit HEVC
        if (media.is10Bit && TranscoderManager.isTranscoding(mediaId)) {
            Log.i(TAG, "handleStream: 10-bit file $mediaId is actively transcoding, routing to live stream")
            return handleLiveOrTranscodedStream(mediaId, session)
        }

        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(media.uri, "r")
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Cannot open media stream"
                )
        } catch (e: Exception) {
            Log.e(TAG, "Error opening media $mediaId: ${e.message}")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/plain",
                "Permission revoked or media unavailable"
            )
        }

        val totalSize = if (media.sizeBytes > 0) media.sizeBytes else pfd.statSize
        val mimeType = media.mimeType.ifEmpty { "video/mp4" }

        if (session.method == Method.HEAD) {
            try { pfd.close() } catch (_: Exception) {}
            val res = newFixedLengthResponse(Response.Status.OK, mimeType, "")
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Content-Length", totalSize.toString())
            return res
        }

        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.removePrefix("bytes=").trim()
            val parts = rangeVal.split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                min(parts[1].toLongOrNull() ?: (totalSize - 1), totalSize - 1)
            } else {
                totalSize - 1
            }

            if (start > end || start >= totalSize) {
                try { pfd.close() } catch (_: Exception) {}
                val res = newFixedLengthResponse(
                    Response.Status.RANGE_NOT_SATISFIABLE,
                    "text/plain",
                    "Range Not Satisfiable"
                )
                res.addHeader("Content-Range", "bytes */$totalSize")
                return res
            }

            val length = end - start + 1
            val fis = FileInputStream(pfd.fileDescriptor)
            fis.channel.position(start)
            val bufferedStream = BufferedInputStream(fis, STREAM_BUFFER_SIZE)
            val boundedStream = BoundedInputStream(bufferedStream, length, pfd)
            val res = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                boundedStream,
                length
            )
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Content-Range", "bytes $start-$end/$totalSize")
            res.addHeader("Content-Length", length.toString())
            return res
        }

        val fis = FileInputStream(pfd.fileDescriptor)
        val bufferedStream = BufferedInputStream(fis, STREAM_BUFFER_SIZE)
        val boundedStream = BoundedInputStream(bufferedStream, totalSize, pfd)
        val res = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            boundedStream,
            totalSize
        )
        res.addHeader("Accept-Ranges", "bytes")
        res.addHeader("Content-Length", totalSize.toString())
        return res
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long,
        private val pfd: ParcelFileDescriptor?
    ) : InputStream() {

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = delegate.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = min(len.toLong(), remaining).toInt()
            val read = delegate.read(b, off, toRead)
            if (read != -1) {
                remaining -= read
            }
            return read
        }

        override fun close() {
            try {
                delegate.close()
            } finally {
                try {
                    pfd?.close()
                } catch (_: Exception) {}
            }
        }
    }
}