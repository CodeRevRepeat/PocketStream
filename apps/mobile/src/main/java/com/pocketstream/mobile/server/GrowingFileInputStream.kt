package com.pocketstream.mobile.server

import android.util.Log
import com.pocketstream.mobile.transcoder.TranscoderManager
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * An InputStream that progressively reads from an actively growing file.
 * Used for live on-the-fly streaming of Fragmented MP4 files while the
 * device hardware encoder is still transcoding.
 */
class GrowingFileInputStream(
    private val file: File,
    private val mediaId: String
) : InputStream() {

    companion object {
        private const val TAG = "GrowingFileStream"
        private const val POLL_INTERVAL_MS = 25L
        private const val MAX_WAIT_WITHOUT_GROWTH_MS = 15_000L
    }

    private val raf: RandomAccessFile = RandomAccessFile(file, "r")
    private var position = 0L
    @Volatile private var isClosed = false
    private val singleByteBuf = ByteArray(1)

    @Synchronized
    override fun read(): Int {
        val readCount = read(singleByteBuf, 0, 1)
        return if (readCount == 1) singleByteBuf[0].toInt() and 0xFF else -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (isClosed || len == 0) return if (isClosed) -1 else 0

        var waitTimeMs = 0L

        while (!isClosed) {
            val currentLen = file.length()
            val available = currentLen - position

            if (available > 0) {
                synchronized(raf) {
                    if (isClosed) return -1
                    raf.seek(position)
                    val toRead = min(len.toLong(), available).toInt()
                    val bytesRead = raf.read(b, off, toRead)
                    if (bytesRead > 0) {
                        position += bytesRead
                        return bytesRead
                    }
                }
            }

            // We've reached the current end of file.
            // If transcoding is still active, wait for encoder to append new chunks.
            if (TranscoderManager.isTranscoding(mediaId)) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                    waitTimeMs += POLL_INTERVAL_MS
                    if (waitTimeMs >= MAX_WAIT_WITHOUT_GROWTH_MS) {
                        Log.w(TAG, "Timed out waiting for new transcoded data for $mediaId")
                        return -1
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            } else {
                // Transcoding has completed. Check if any final bytes remain.
                val finalLen = file.length()
                if (position >= finalLen) {
                    return -1 // True EOF reached
                }
            }
        }
        return -1
    }

    override fun close() {
        isClosed = true
        try {
            synchronized(raf) {
                raf.close()
            }
        } catch (ignored: Exception) {}
    }
}
