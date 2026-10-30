package com.pocketstream.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerView

class ExoPlayerManager(
    private val context: Context,
    private val playerView: PlayerView,
    private val onPlaybackStateChange: (Boolean, Long, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "ExoPlayerManager"
    }

    private var exoPlayer: ExoPlayer? = null
    private var currentUrl: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer ?: return
            if (player.isPlaying) {
                val current = player.currentPosition.coerceAtLeast(0L)
                val duration = if (player.duration > 0L) player.duration else 0L
                onPlaybackStateChange(true, current, duration)
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    init {
        initPlayer()
    }

    private fun initPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500,  // minBufferMs
                25000, // maxBufferMs
                500,   // bufferForPlaybackMs
                1000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()

        player.addAnalyticsListener(EventLogger())
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val isPlaying = player.isPlaying
                val current = player.currentPosition.coerceAtLeast(0L)
                val duration = if (player.duration > 0L) player.duration else 0L

                when (playbackState) {
                    Player.STATE_READY -> {
                        Log.i(TAG, "ExoPlayer STATE_READY. Duration: $duration ms, Position: $current ms, isPlaying: $isPlaying")
                        onPlaybackStateChange(isPlaying, current, duration)
                    }
                    Player.STATE_ENDED -> {
                        Log.i(TAG, "ExoPlayer STATE_ENDED")
                        onPlaybackStateChange(false, duration, duration)
                    }
                    Player.STATE_BUFFERING -> {}
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.i(TAG, "ExoPlayer onIsPlayingChanged: $isPlaying")
                val current = player.currentPosition.coerceAtLeast(0L)
                val duration = if (player.duration > 0L) player.duration else 0L
                onPlaybackStateChange(isPlaying, current, duration)

                if (isPlaying) {
                    progressHandler.removeCallbacks(progressRunnable)
                    progressHandler.post(progressRunnable)
                } else {
                    progressHandler.removeCallbacks(progressRunnable)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "ExoPlayer error: [${error.errorCode}] ${error.message}", error)
                onError(error.message ?: "Playback error (${error.errorCode})")
            }
        })

        playerView.player = player
        exoPlayer = player
    }

    fun play(streamUrl: String, startPositionMs: Long = 0L) {
        currentUrl = streamUrl
        val player = exoPlayer ?: return
        Log.i(TAG, "play: $streamUrl, startPosition: $startPositionMs")

        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        player.setMediaItem(mediaItem)
        if (startPositionMs > 0L && !streamUrl.contains("/stream/live/")) {
            player.seekTo(startPositionMs)
        }
        player.prepare()
        player.playWhenReady = true
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun seekRelative(deltaMs: Long) {
        val player = exoPlayer ?: return
        if (!player.isCurrentMediaItemSeekable) {
            Log.w(TAG, "seekRelative ignored: stream is currently non-seekable. Blocking reset to 00:00!")
            return
        }
        val current = player.currentPosition.coerceAtLeast(0L)
        val total = if (player.duration > 0L) player.duration else Long.MAX_VALUE
        val target = (current + deltaMs).coerceIn(0L, total)
        Log.i(TAG, "seekRelative: delta=$deltaMs ms, cur=$current ms -> target=$target ms (isSeekable=${player.isCurrentMediaItemSeekable})")
        player.seekTo(target)
    }

    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        val safePos = positionMs.coerceAtLeast(0L)
        Log.i(TAG, "seekTo: requested=$positionMs -> safe=$safePos ms, currentPos=${player.currentPosition} ms, duration=${player.duration} ms, isSeekable=${player.isCurrentMediaItemSeekable}")
        if (!player.isCurrentMediaItemSeekable) {
            Log.w(TAG, "seekTo ignored: stream is currently non-seekable. Blocking reset to 00:00!")
            return
        }
        player.seekTo(safePos)
    }

    fun isSeekable(): Boolean = exoPlayer?.isCurrentMediaItemSeekable == true

    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true

    fun getCurrentTime(): Long = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun getDuration(): Long = if ((exoPlayer?.duration ?: 0L) > 0L) exoPlayer!!.duration else 0L

    fun stop() {
        progressHandler.removeCallbacks(progressRunnable)
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
    }

    fun release() {
        progressHandler.removeCallbacks(progressRunnable)
        playerView.player = null
        exoPlayer?.release()
        exoPlayer = null
    }
}
