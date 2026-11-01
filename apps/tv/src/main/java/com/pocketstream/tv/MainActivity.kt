package com.pocketstream.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.pocketstream.protocol.MediaItem
import com.pocketstream.tv.databinding.ActivityMainBinding
import com.pocketstream.tv.player.ExoPlayerManager
import com.pocketstream.tv.player.AudioTrackInfo
import com.pocketstream.tv.receiver.TvReceiverServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import android.app.AlertDialog
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.pocketstream.protocol.TvInfo
import com.pocketstream.tv.discovery.TvDiscoveryManager
import com.pocketstream.tv.util.QrUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TvMediaAdapter
    private var playerManager: ExoPlayerManager? = null
    private var receiverServer: TvReceiverServer? = null
    private var discoveryManager: TvDiscoveryManager? = null
    private var qrDialog: AlertDialog? = null
    private var healthCheckJob: kotlinx.coroutines.Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable { hideOsd() }
    private var phoneBaseUrl = ""
    private var connectedPhoneName = ""

    private var currentStreamUrl: String = ""
    private var currentTitle: String = ""
    @Volatile private var currentPositionMs = 0L
    @Volatile private var totalDurationMs = 0L
    @Volatile private var isPlaying = false
    private var currentSeekOffsetMs = 0L
    private var tvPreparingCountdownJob: kotlinx.coroutines.Job? = null
    private var availableAudioTracks: List<AudioTrackInfo> = emptyList()
    private var isUserScrubbing = false
    private var scrubTargetMs = -1L
    private var seekDebounceJob: kotlinx.coroutines.Job? = null

    private fun getCurrentEffectiveTime(): Long {
        val raw = playerManager?.getCurrentTime() ?: currentPositionMs
        return if (currentStreamUrl.contains("/stream/live/")) currentSeekOffsetMs + raw else raw
    }

    private fun showTvPreparing(targetPositionMs: Long) {
        binding.layoutTvPreparing.visibility = View.VISIBLE
        binding.layoutTvPreparing.bringToFront()
        binding.tvTvPreparingTitle.text = "Seeking to ${formatDuration(targetPositionMs)}"
        binding.tvTvPreparingSubtitle.text = "Hardware-encoding video stream to ensure smooth, stutter-free playback."
        binding.tvTvPreparingCountdown.text = "Starting stream in 15s..."
        binding.pbTvPreparingCountdown.progress = 0
        binding.btnTvCancelPreparing.setOnClickListener {
            dismissTvPreparing()
            stopPlayback()
        }

        tvPreparingCountdownJob?.cancel()
        tvPreparingCountdownJob = CoroutineScope(Dispatchers.Main).launch {
            val totalSeconds = 15
            for (secRemaining in totalSeconds downTo 1) {
                binding.tvTvPreparingCountdown.text = "Starting stream in ${secRemaining}s..."
                binding.pbTvPreparingCountdown.progress = totalSeconds - secRemaining
                kotlinx.coroutines.delay(1000)
            }
            binding.tvTvPreparingCountdown.text = "Buffering stream..."
        }
    }

    private fun dismissTvPreparing() {
        tvPreparingCountdownJob?.cancel()
        tvPreparingCountdownJob = null
        binding.layoutTvPreparing.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore last known phone connection
        val prefs = getSharedPreferences("pocketstream_tv_prefs", MODE_PRIVATE)
        val savedIp = prefs.getString("last_phone_ip", null)
        val savedPort = prefs.getInt("last_phone_port", 8080)
        val savedName = prefs.getString("last_phone_name", "PocketStream Phone") ?: "PocketStream Phone"

        if (!savedIp.isNullOrEmpty()) {
            phoneBaseUrl = "http://$savedIp:$savedPort"
            connectedPhoneName = savedName
            updatePhoneConnectionUi(true, "Connected to $savedName ($savedIp:$savedPort)")
        } else {
            updatePhoneSearchingUi("Searching for phone or scan QR...")
        }

        setupRecyclerView()
        setupPlayer()
        setupReceiverServer()
        setupListeners()
        setupDiscovery()
        startHealthCheck()

        if (phoneBaseUrl.isNotEmpty()) {
            fetchLibrary()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun setupRecyclerView() {
        adapter = TvMediaAdapter(emptyList()) { item ->
            requestPlaybackFromPhone(item)
        }
        binding.rvTvMediaList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvTvMediaList.adapter = adapter
    }

    private fun requestPlaybackFromPhone(item: MediaItem) {
        if (phoneBaseUrl.isEmpty()) {
            Toast.makeText(this, "Phone not connected", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("mediaId", item.id)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("$phoneBaseUrl/api/v1/play")
                    .post(body)
                    .build()

                val res = httpClient.newCall(req).execute()
                if (res.isSuccessful) {
                    val resStr = res.body?.string() ?: "{}"
                    res.close()
                    val resJson = JSONObject(resStr)
                    val streamUrl = resJson.getString("streamUrl")
                    val title = resJson.optString("title", item.title)
                    val durationMs = resJson.optLong("durationMs", item.durationMs)
                    val message = resJson.optString("message", "")

                    withContext(Dispatchers.Main) {
                        if (message.isNotEmpty()) {
                            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        }
                        startPlayback(streamUrl, title, durationMs)
                    }
                } else {
                    res.close()
                    withContext(Dispatchers.Main) {
                        val streamUrl = "$phoneBaseUrl${item.streamPath}"
                        Toast.makeText(this@MainActivity, "Direct stream started.", Toast.LENGTH_SHORT).show()
                        startPlayback(streamUrl, item.title, item.durationMs)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val streamUrl = "$phoneBaseUrl${item.streamPath}"
                    Toast.makeText(this@MainActivity, "Direct stream started.", Toast.LENGTH_SHORT).show()
                    startPlayback(streamUrl, item.title, item.durationMs)
                }
            }
        }
    }

    private fun setupPlayer() {
        playerManager = ExoPlayerManager(
            context = this,
            playerView = binding.exoPlayerView,
            onPlaybackStateChange = { playing, timeMs, lengthMs ->
                runOnUiThread {
                    isPlaying = playing
                    val effectivePos = if (currentStreamUrl.contains("/stream/live/")) currentSeekOffsetMs + timeMs else timeMs
                    currentPositionMs = effectivePos
                    if (lengthMs > 0 && totalDurationMs <= 0) {
                        totalDurationMs = lengthMs
                    }
                    updatePlaybackUi(playing, effectivePos, totalDurationMs)
                }
            },
            onError = { err ->
                runOnUiThread {
                    Toast.makeText(this, "Playback Error: $err", Toast.LENGTH_LONG).show()
                }
            }
        ).apply {
            onAudioTracksChanged = { tracks ->
                runOnUiThread {
                    updateAudioTracksUi(tracks)
                }
            }
            onSubtitlesChanged = { hasSubtitles, areSubtitlesEnabled ->
                runOnUiThread {
                    updateSubtitlesUi(hasSubtitles, areSubtitlesEnabled)
                }
            }
        }
    }

    private fun setupReceiverServer() {
        try {
            receiverServer = TvReceiverServer(
                port = 8085,
                onPlayRequested = { streamUrl, title, durationMs, startPositionMs ->
                    runOnUiThread {
                        val bringToFront = Intent(this@MainActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(bringToFront)
                        startPlayback(streamUrl, title, durationMs, startPositionMs)
                    }
                },
                onControlRequested = { action, pos ->
                    runOnUiThread {
                        when (action.uppercase()) {
                            "PLAY" -> if (!isPlaying) playerManager?.togglePlayPause()
                            "PAUSE" -> if (isPlaying) playerManager?.togglePlayPause()
                            "PREPARING_SEEK" -> {
                                playerManager?.pause()
                                showTvPreparing(pos)
                                currentPositionMs = pos
                                updatePlaybackUi(playing = false, timeMs = pos, lengthMs = totalDurationMs)
                            }
                            "SEEK" -> {
                                seekToSafe(pos)
                                showOsd()
                            }
                            "STOP" -> stopPlayback()
                            "RW" -> {
                                val cur = getCurrentEffectiveTime()
                                val target = (cur - 10000L).coerceAtLeast(0L)
                                seekToSafe(target)
                                showOsd()
                            }
                            "FF" -> {
                                val cur = getCurrentEffectiveTime()
                                val maxDur = if (totalDurationMs > 0) totalDurationMs else Long.MAX_VALUE
                                val target = (cur + 10000L).coerceAtMost(maxDur)
                                seekToSafe(target)
                                showOsd()
                            }
                            "RESTART" -> {
                                seekToSafe(0L)
                                showOsd()
                            }
                        }
                    }
                },
                getPlaybackStatus = {
                    Triple(isPlaying, currentPositionMs, totalDurationMs)
                },
                onPairRequested = { phoneIp, phonePort, deviceName ->
                    runOnUiThread {
                        onPhoneConnected(phoneIp, phonePort, deviceName)
                        Toast.makeText(this@MainActivity, "Paired with $deviceName ($phoneIp:$phonePort)!", Toast.LENGTH_SHORT).show()
                    }
                },
                onDisconnectRequested = {
                    runOnUiThread {
                        onPhoneDisconnected()
                    }
                },
                onRefreshRequested = {
                    runOnUiThread {
                        fetchLibrary()
                    }
                }
            )
            receiverServer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupListeners() {
        binding.btnRefreshLibrary.setOnClickListener {
            fetchLibrary()
        }

        binding.btnPairPhone.setOnClickListener {
            showQrPairDialog()
        }

        binding.btnTestStream.setOnClickListener {
            val testUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
            startPlayback(testUrl, "Big Buck Bunny (Sample)")
        }

        // Restart from beginning
        binding.btnPlayerRestart.setOnClickListener {
            seekToSafe(0L)
            currentPositionMs = 0L
            resetOsdTimer()
            Toast.makeText(this, "Restarting from beginning", Toast.LENGTH_SHORT).show()
        }

        binding.btnPlayerPlayPause.setOnClickListener {
            playerManager?.togglePlayPause()
            resetOsdTimer()
        }

        binding.btnPlayerRw.setOnClickListener {
            val cur = getCurrentEffectiveTime()
            val target = (cur - 10000L).coerceAtLeast(0L)
            seekToSafe(target)
            resetOsdTimer()
        }

        binding.btnPlayerFf.setOnClickListener {
            val cur = getCurrentEffectiveTime()
            val maxDur = if (totalDurationMs > 0) totalDurationMs else Long.MAX_VALUE
            val target = (cur + 10000L).coerceAtMost(maxDur)
            seekToSafe(target)
            resetOsdTimer()
        }

        binding.sbPlayerProgress.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.sbPlayerProgress.animate().scaleY(1.3f).setDuration(150).start()
                resetOsdTimer()
            } else {
                binding.sbPlayerProgress.animate().scaleY(1.0f).setDuration(150).start()
                if (isUserScrubbing) {
                    seekDebounceJob?.cancel()
                    commitScrubSeek()
                }
            }
        }

        binding.sbPlayerProgress.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val effectiveLen = if (totalDurationMs > 0) totalDurationMs else (playerManager?.getDuration() ?: 0L)
                if (effectiveLen <= 0) return@setOnKeyListener false

                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        isUserScrubbing = true
                        resetOsdTimer()
                        if (scrubTargetMs < 0) {
                            scrubTargetMs = getCurrentEffectiveTime()
                        }
                        val step = if (event.repeatCount > 4) 30000L else 10000L
                        scrubTargetMs = (scrubTargetMs - step).coerceAtLeast(0L)
                        val prog = ((scrubTargetMs.toDouble() / effectiveLen.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
                        binding.sbPlayerProgress.progress = prog
                        binding.tvPlayerTime.text = "${formatDuration(scrubTargetMs)} / ${formatDuration(effectiveLen)}"

                        seekDebounceJob?.cancel()
                        seekDebounceJob = CoroutineScope(Dispatchers.Main).launch {
                            kotlinx.coroutines.delay(800)
                            commitScrubSeek()
                        }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        isUserScrubbing = true
                        resetOsdTimer()
                        if (scrubTargetMs < 0) {
                            scrubTargetMs = getCurrentEffectiveTime()
                        }
                        val step = if (event.repeatCount > 4) 30000L else 10000L
                        scrubTargetMs = (scrubTargetMs + step).coerceAtMost(effectiveLen)
                        val prog = ((scrubTargetMs.toDouble() / effectiveLen.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
                        binding.sbPlayerProgress.progress = prog
                        binding.tvPlayerTime.text = "${formatDuration(scrubTargetMs)} / ${formatDuration(effectiveLen)}"

                        seekDebounceJob?.cancel()
                        seekDebounceJob = CoroutineScope(Dispatchers.Main).launch {
                            kotlinx.coroutines.delay(800)
                            commitScrubSeek()
                        }
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (isUserScrubbing && scrubTargetMs >= 0) {
                            seekDebounceJob?.cancel()
                            commitScrubSeek()
                            return@setOnKeyListener true
                        }
                    }
                }
            }
            false
        }

        binding.sbPlayerProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val effectiveLen = if (totalDurationMs > 0) totalDurationMs else (playerManager?.getDuration() ?: 0L)
                    if (effectiveLen > 0) {
                        val seekTo = (progress.toDouble() / 1000.0 * effectiveLen).toLong()
                        binding.tvPlayerTime.text = "${formatDuration(seekTo)} / ${formatDuration(effectiveLen)}"
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                isUserScrubbing = true
                seekDebounceJob?.cancel()
                osdHandler.removeCallbacks(hideOsdRunnable)
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val effectiveLen = if (totalDurationMs > 0) totalDurationMs else (playerManager?.getDuration() ?: 0L)
                if (effectiveLen > 0) {
                    val seekTo = (progress.toDouble() / 1000.0 * effectiveLen).toLong()
                    isUserScrubbing = false
                    scrubTargetMs = -1L
                    seekToSafe(seekTo)
                }
                resetOsdTimer()
            }
        })

        // Drop-up Audio Track selector
        binding.btnPlayerAudioTrack.setOnClickListener {
            toggleAudioTrackDropUp()
            resetOsdTimer()
        }

        // Subtitle Toggle
        binding.btnPlayerSubtitle.setOnClickListener {
            val currentEnabled = playerManager?.areSubtitlesEnabled() ?: true
            val newEnabled = !currentEnabled
            playerManager?.setSubtitlesEnabled(newEnabled)
            binding.btnPlayerSubtitle.text = if (newEnabled) "Subtitles: ON" else "Subtitles: OFF"
            val msg = if (newEnabled) "Subtitles turned ON" else "Subtitles turned OFF"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            resetOsdTimer()
        }

        binding.btnPlayerStop.setOnClickListener {
            stopPlayback()
        }
    }

    private fun commitScrubSeek() {
        if (scrubTargetMs >= 0) {
            val target = scrubTargetMs
            scrubTargetMs = -1L
            isUserScrubbing = false
            seekToSafe(target)
            resetOsdTimer()
        }
    }

    private fun updateAudioTracksUi(tracks: List<AudioTrackInfo>) {
        availableAudioTracks = tracks
        if (tracks.size > 1) {
            binding.btnPlayerAudioTrack.text = "Audio Track"
            binding.btnPlayerAudioTrack.visibility = View.VISIBLE
            populateAudioTrackDropUp(tracks)
        } else {
            binding.btnPlayerAudioTrack.visibility = View.GONE
            binding.layoutAudioTrackDropUp.visibility = View.GONE
        }
    }

    private fun updateSubtitlesUi(hasSubtitles: Boolean, areSubtitlesEnabled: Boolean) {
        if (hasSubtitles) {
            binding.btnPlayerSubtitle.text = if (areSubtitlesEnabled) "Subtitles: ON" else "Subtitles: OFF"
            binding.btnPlayerSubtitle.visibility = View.VISIBLE
        } else {
            binding.btnPlayerSubtitle.visibility = View.GONE
        }
    }

    private fun populateAudioTrackDropUp(tracks: List<AudioTrackInfo>) {
        binding.llAudioTrackList.removeAllViews()
        val trackViews = mutableListOf<View>()
        for (track in tracks) {
            val itemView = layoutInflater.inflate(R.layout.item_tv_audio_track, binding.llAudioTrackList, false)
            itemView.id = View.generateViewId()
            val tvCheck = itemView.findViewById<TextView>(R.id.tvAudioTrackCheck)
            val tvTitle = itemView.findViewById<TextView>(R.id.tvAudioTrackTitle)

            tvCheck.visibility = if (track.isSelected) View.VISIBLE else View.INVISIBLE
            tvTitle.text = track.label
            if (track.isSelected) {
                tvTitle.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.tv_accent_cyan))
            } else {
                tvTitle.setTextColor(android.graphics.Color.WHITE)
            }

            itemView.setOnClickListener {
                playerManager?.selectAudioTrack(track)
                binding.layoutAudioTrackDropUp.visibility = View.GONE
                binding.btnPlayerAudioTrack.requestFocus()
                resetOsdTimer()
                Toast.makeText(this, "Audio: ${track.label}", Toast.LENGTH_SHORT).show()
            }

            trackViews.add(itemView)
            binding.llAudioTrackList.addView(itemView)
        }

        for ((index, itemView) in trackViews.withIndex()) {
            if (index > 0) {
                itemView.nextFocusUpId = trackViews[index - 1].id
            }
            if (index < trackViews.size - 1) {
                itemView.nextFocusDownId = trackViews[index + 1].id
            } else {
                itemView.nextFocusDownId = R.id.btnPlayerAudioTrack
            }
        }
    }

    private fun toggleAudioTrackDropUp() {
        val dropUp = binding.layoutAudioTrackDropUp
        if (dropUp.visibility == View.VISIBLE) {
            dropUp.visibility = View.GONE
            binding.btnPlayerAudioTrack.requestFocus()
        } else {
            if (availableAudioTracks.size > 1) {
                populateAudioTrackDropUp(availableAudioTracks)
                dropUp.visibility = View.VISIBLE
                dropUp.bringToFront()
                val selectedIdx = availableAudioTracks.indexOfFirst { it.isSelected }.coerceAtLeast(0)
                if (selectedIdx < binding.llAudioTrackList.childCount) {
                    binding.llAudioTrackList.getChildAt(selectedIdx).requestFocus()
                }
            }
        }
    }

    private fun updatePhoneConnectionUi(connected: Boolean, text: String) {
        binding.tvTvPhoneStatus.text = text
        if (connected) {
            binding.tvTvPhoneStatus.setTextColor(android.graphics.Color.parseColor("#38BDF8"))
            binding.vTvStatusDot.setBackgroundResource(R.drawable.bg_tv_status_dot_online)
        } else {
            binding.tvTvPhoneStatus.setTextColor(android.graphics.Color.parseColor("#FF5252"))
            binding.vTvStatusDot.setBackgroundResource(R.drawable.bg_tv_status_dot_offline)
        }
    }

    private fun updatePhoneSearchingUi(text: String) {
        binding.tvTvPhoneStatus.text = text
        binding.tvTvPhoneStatus.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        binding.vTvStatusDot.setBackgroundResource(R.drawable.bg_tv_status_dot_searching)
    }

    private fun fetchLibrary() {
        if (phoneBaseUrl.isEmpty()) {
            updatePhoneConnectionUi(false, "Phone disconnected")
            binding.tvTvEmptyNotice.text = "Phone disconnected. Tap 'Pair Phone (QR Code)' above or start server on phone."
            binding.tvTvEmptyNotice.visibility = View.VISIBLE
            binding.rvTvMediaList.visibility = View.GONE
            adapter.updateList(emptyList())
            return
        }
        updatePhoneSearchingUi("Fetching library from $connectedPhoneName...")
        CoroutineScope(Dispatchers.IO).launch {
            var items: List<MediaItem>? = null
            var lastError: Exception? = null

            // Retry up to 4 times with progressive backoff (handles momentary startup latency)
            for (attempt in 1..4) {
                try {
                    val req = Request.Builder().url("$phoneBaseUrl/api/v1/library").build()
                    val res = httpClient.newCall(req).execute()
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: "[]"
                        val jsonArr = JSONArray(body)
                        val parsed = mutableListOf<MediaItem>()
                        for (i in 0 until jsonArr.length()) {
                            parsed.add(MediaItem.fromJson(jsonArr.getJSONObject(i)))
                        }
                        items = parsed
                        res.close()
                        break
                    } else {
                        lastError = java.io.IOException("HTTP ${res.code}")
                        res.close()
                    }
                } catch (e: Exception) {
                    lastError = e
                }
                if (attempt < 4) {
                    kotlinx.coroutines.delay(500L * attempt)
                }
            }

            if (items != null) {
                withContext(Dispatchers.Main) {
                    val displayName = if (connectedPhoneName.isNotEmpty()) connectedPhoneName else "Phone"
                    updatePhoneConnectionUi(true, "Connected to $displayName ($phoneBaseUrl)")
                    adapter.updateList(items)
                    if (items.isEmpty()) {
                        binding.tvTvEmptyNotice.text = "No videos found in phone library. Add videos in PocketStream Mobile."
                        binding.tvTvEmptyNotice.visibility = View.VISIBLE
                        binding.rvTvMediaList.visibility = View.GONE
                    } else {
                        binding.tvTvEmptyNotice.visibility = View.GONE
                        binding.rvTvMediaList.visibility = View.VISIBLE
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    android.util.Log.w("MainActivity", "fetchLibrary failed after 4 retries: ${lastError?.message}")
                    updatePhoneConnectionUi(false, "Phone disconnected")
                    binding.tvTvEmptyNotice.text = "Phone disconnected. Please start the media server on your phone."
                    binding.tvTvEmptyNotice.visibility = View.VISIBLE
                    binding.rvTvMediaList.visibility = View.GONE
                    adapter.updateList(emptyList())
                }
            }
        }
    }

    private fun showQrPairDialog() {
        val localIp = discoveryManager?.getLocalIpAddress() ?: "127.0.0.1"
        val tvDeviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "Android TV" }
        val tvInfo = TvInfo(ip = localIp, port = 8085, name = tvDeviceName)
        val qrContent = tvInfo.toQrString()
        val qrBitmap = QrUtils.generateQrBitmap(qrContent, 400)

        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_pair, null)
        val ivQr = dialogView.findViewById<ImageView>(R.id.ivTvQrCode)
        val tvAddress = dialogView.findViewById<TextView>(R.id.tvTvQrAddress)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvTvQrStatus)
        val btnClose = dialogView.findViewById<Button>(R.id.btnDismissQr)

        if (qrBitmap != null) {
            ivQr.setImageBitmap(qrBitmap)
        }
        val caps = com.pocketstream.tv.util.TvCapabilityDetector.getCapabilities()
        val capsSummary = "TV Display: ${caps.maxWidth}x${caps.maxHeight} | 8-bit HW Decoder"
        tvAddress.text = "TV IP: $localIp:8085"
        tvStatus.text = if (phoneBaseUrl.isNotEmpty()) "Connected to $connectedPhoneName\n$capsSummary" else "Waiting for phone to scan or discover...\n$capsSummary"

        qrDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        qrDialog = dialog
        dialog.show()
        btnClose.requestFocus()
    }

    private fun onPhoneConnected(phoneIp: String, phonePort: Int, deviceName: String) {
        val newUrl = "http://$phoneIp:$phonePort"
        phoneBaseUrl = newUrl
        connectedPhoneName = deviceName
        updatePhoneConnectionUi(true, "Connected to $deviceName ($phoneIp:$phonePort)")

        getSharedPreferences("pocketstream_tv_prefs", MODE_PRIVATE).edit()
            .putString("last_phone_ip", phoneIp)
            .putInt("last_phone_port", phonePort)
            .putString("last_phone_name", deviceName)
            .apply()

        qrDialog?.dismiss()
        qrDialog = null

        // Immediately refresh the video library on TV
        fetchLibrary()
    }

    private fun onPhoneDisconnected() {
        if (isPlaying || binding.layoutPlayer.visibility == View.VISIBLE) {
            return
        }
        phoneBaseUrl = ""
        connectedPhoneName = ""
        updatePhoneConnectionUi(false, "Phone disconnected")
        binding.tvTvEmptyNotice.text = "Phone disconnected. Please start the media server on your phone."
        binding.tvTvEmptyNotice.visibility = View.VISIBLE
        binding.rvTvMediaList.visibility = View.GONE
        adapter.updateList(emptyList())

        if (binding.layoutPlayer.visibility == View.VISIBLE) {
            stopPlayback()
        }
        Toast.makeText(this@MainActivity, "Phone disconnected", Toast.LENGTH_SHORT).show()
    }

    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = CoroutineScope(Dispatchers.IO).launch {
            var consecutiveFailures = 0
            while (isActive) {
                kotlinx.coroutines.delay(3500)
                if (isPlaying || binding.layoutPlayer.visibility == View.VISIBLE) {
                    consecutiveFailures = 0
                    continue
                }
                if (phoneBaseUrl.isNotEmpty()) {
                    try {
                        val pingReq = Request.Builder()
                            .url("$phoneBaseUrl/api/v1/library")
                            .get()
                            .build()
                        val res = httpClient.newCall(pingReq).execute()
                        if (res.isSuccessful) {
                            consecutiveFailures = 0
                        } else {
                            consecutiveFailures++
                        }
                        res.close()
                    } catch (e: Exception) {
                        consecutiveFailures++
                    }

                    if (consecutiveFailures >= 4) {
                        consecutiveFailures = 0
                        withContext(Dispatchers.Main) {
                            if (phoneBaseUrl.isNotEmpty() && !isPlaying) {
                                onPhoneDisconnected()
                            }
                        }
                    }
                } else {
                    consecutiveFailures = 0
                }
            }
        }
    }

    private fun setupDiscovery() {
        discoveryManager?.stop()
        discoveryManager = TvDiscoveryManager(
            context = this,
            tvPort = 8085,
            onPhoneDiscovered = { phoneIp, phonePort, name ->
                runOnUiThread {
                    onPhoneConnected(phoneIp, phonePort, name)
                }
            },
            onPhoneDisconnected = {
                runOnUiThread {
                    onPhoneDisconnected()
                }
            }
        )
        discoveryManager?.start()
    }

    private fun startPlayback(
        streamUrl: String,
        title: String,
        durationMs: Long = 0L,
        startPositionMs: Long = 0L
    ) {
        dismissTvPreparing()
        currentStreamUrl = streamUrl
        currentTitle = title
        currentSeekOffsetMs = if (streamUrl.contains("/stream/live/")) startPositionMs else 0L
        if (durationMs > 0) {
            totalDurationMs = durationMs
        }
        currentPositionMs = startPositionMs

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        binding.layoutHome.visibility = View.GONE
        binding.layoutPlayer.visibility = View.VISIBLE
        binding.layoutPlayer.bringToFront()
        binding.exoPlayerView.visibility = View.VISIBLE
        binding.exoPlayerView.bringToFront()
        binding.layoutOsd.bringToFront()
        binding.tvPlayerTitle.text = title

        // Instantly display known movie duration and initial start time on the OSD
        val initialPos = if (startPositionMs > 0L) startPositionMs else 0L
        updatePlaybackUi(playing = true, timeMs = initialPos, lengthMs = totalDurationMs)

        binding.btnPlayerAudioTrack.visibility = View.GONE
        binding.btnPlayerSubtitle.visibility = View.GONE
        binding.layoutAudioTrackDropUp.visibility = View.GONE
        availableAudioTracks = emptyList()

        playerManager?.play(streamUrl, startPositionMs)
        showOsd()
        binding.btnPlayerPlayPause.requestFocus()
    }

    private fun stopPlayback() {
        dismissTvPreparing()
        seekDebounceJob?.cancel()
        isUserScrubbing = false
        scrubTargetMs = -1L
        currentSeekOffsetMs = 0L
        osdHandler.removeCallbacks(hideOsdRunnable)
        playerManager?.stop()
        binding.layoutAudioTrackDropUp.visibility = View.GONE
        binding.btnPlayerAudioTrack.visibility = View.GONE
        binding.btnPlayerSubtitle.visibility = View.GONE
        availableAudioTracks = emptyList()
        window.setBackgroundDrawableResource(R.color.bg_tv)
        binding.layoutPlayer.visibility = View.GONE
        binding.layoutHome.visibility = View.VISIBLE
        binding.layoutHome.bringToFront()
        binding.btnRefreshLibrary.requestFocus()

        if (phoneBaseUrl.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val body = "{}".toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("$phoneBaseUrl/api/v1/stop")
                        .post(body)
                        .build()
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun updatePlaybackUi(playing: Boolean, timeMs: Long, lengthMs: Long) {
        binding.btnPlayerPlayPause.text = if (playing) "Pause" else "Play"

        if (lengthMs > 0 && totalDurationMs <= 0) {
            totalDurationMs = lengthMs
        }
        val effectiveLen = if (totalDurationMs > 0) totalDurationMs else (if (lengthMs > 0) lengthMs else 0L)

        val timeStr = formatDuration(timeMs)
        val lenStr = if (effectiveLen > 0) formatDuration(effectiveLen) else "Live"
        binding.tvPlayerTime.text = "$timeStr / $lenStr"

        if (effectiveLen > 0 && !isUserScrubbing && !binding.sbPlayerProgress.hasFocus() && !binding.sbPlayerProgress.isPressed) {
            val progress = ((timeMs.toDouble() / effectiveLen.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
            binding.sbPlayerProgress.progress = progress
        }
    }

    private fun showOsd() {
        binding.layoutOsd.visibility = View.VISIBLE
        binding.layoutOsd.bringToFront()
        if (binding.layoutAudioTrackDropUp.visibility == View.VISIBLE) {
            binding.layoutAudioTrackDropUp.bringToFront()
        }
        resetOsdTimer()
    }

    private fun hideOsd() {
        if (isPlaying && binding.layoutPlayer.visibility == View.VISIBLE) {
            binding.layoutAudioTrackDropUp.visibility = View.GONE
            binding.layoutOsd.visibility = View.GONE
        }
    }

    private fun resetOsdTimer() {
        osdHandler.removeCallbacks(hideOsdRunnable)
        osdHandler.postDelayed(hideOsdRunnable, 5000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.layoutPlayer.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            // If drop-up is open and user presses BACK, close the drop-up first
            if (binding.layoutAudioTrackDropUp.visibility == View.VISIBLE) {
                if (event.keyCode in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE)) {
                    binding.layoutAudioTrackDropUp.visibility = View.GONE
                    binding.btnPlayerAudioTrack.requestFocus()
                    return true
                }
            }

            // Up arrow from Audio Track button navigates into the open drop-up menu
            if (binding.layoutAudioTrackDropUp.visibility == View.VISIBLE && binding.btnPlayerAudioTrack.hasFocus() && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                val childCount = binding.llAudioTrackList.childCount
                if (childCount > 0) {
                    binding.llAudioTrackList.getChildAt(childCount - 1).requestFocus()
                    return true
                }
            }

            if (binding.layoutOsd.visibility != View.VISIBLE) {
                // Any key wakes the OSD controls and shows progress
                showOsd()
                binding.btnPlayerPlayPause.requestFocus()
                // If it was just an arrow key waking the OSD, consume it so it doesn't jump controls
                if (event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                    return true
                }
            } else {
                // If OSD is already visible, keep it alive for another 5 seconds
                resetOsdTimer()
            }

            // Quick hardware remote buttons (support all Android TV & Google TV remote layouts)
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    playerManager?.togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    if (playerManager?.isPlaying() == false) {
                        playerManager?.togglePlayPause()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (playerManager?.isPlaying() == true) {
                        playerManager?.togglePlayPause()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    stopPlayback()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                    val cur = getCurrentEffectiveTime()
                    val maxDur = if (totalDurationMs > 0) totalDurationMs else Long.MAX_VALUE
                    val target = (cur + 10000L).coerceAtMost(maxDur)
                    seekToSafe(target)
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                    val cur = getCurrentEffectiveTime()
                    val target = (cur - 10000L).coerceAtLeast(0L)
                    seekToSafe(target)
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> {
                    stopPlayback()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekToSafe(targetMs: Long) {
        // Direct play stream: ExoPlayer supports instant Range seeking!
        if (!currentStreamUrl.contains("/stream/live/")) {
            playerManager?.seekTo(targetMs)
            return
        }

        // Live hardware-encoded stream:
        val mediaId = extractMediaId(currentStreamUrl)
        if (mediaId.isNotEmpty() && phoneBaseUrl.isNotEmpty()) {
            playerManager?.pause()
            showTvPreparing(targetMs)
            currentPositionMs = targetMs
            updatePlaybackUi(playing = false, timeMs = targetMs, lengthMs = totalDurationMs)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val json = org.json.JSONObject().apply {
                        put("mediaId", mediaId)
                        put("positionMs", targetMs)
                    }
                    val body = json.toString().toRequestBody("application/json".toMediaType())
                    val req = Request.Builder()
                        .url("$phoneBaseUrl/api/v1/transcode/seek")
                        .post(body)
                        .build()
                    httpClient.newCall(req).execute().close()
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to send transcode seek: ${e.message}")
                }
            }
        }
    }

    private fun extractMediaId(url: String): String {
        return try {
            when {
                url.contains("/stream/live/") -> url.substringAfter("/stream/live/").substringBefore("?").substringBefore("/")
                url.contains("/stream/transcoded/") -> url.substringAfter("/stream/transcoded/").substringBefore("?").substringBefore("/")
                url.contains("/stream/") -> url.substringAfter("/stream/").substringBefore("?").substringBefore("/")
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val hrs = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    override fun onDestroy() {
        dismissTvPreparing()
        seekDebounceJob?.cancel()
        healthCheckJob?.cancel()
        discoveryManager?.stop()
        qrDialog?.dismiss()
        osdHandler.removeCallbacks(hideOsdRunnable)
        receiverServer?.stop()
        playerManager?.release()
        super.onDestroy()
    }
}
