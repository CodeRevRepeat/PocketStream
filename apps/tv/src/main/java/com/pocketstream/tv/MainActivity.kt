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
                    currentPositionMs = timeMs
                    if (lengthMs > 0) {
                        totalDurationMs = lengthMs
                    }
                    updatePlaybackUi(playing, timeMs, lengthMs)
                }
            },
            onError = { err ->
                runOnUiThread {
                    Toast.makeText(this, "Playback Error: $err", Toast.LENGTH_LONG).show()
                }
            }
        )
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
                        android.util.Log.i("MainActivity", "Receiver onControlRequested: action=$action, pos=$pos (isPlaying=$isPlaying, curPos=$currentPositionMs)")
                        when (action.uppercase()) {
                            "PLAY" -> if (!isPlaying) playerManager?.togglePlayPause()
                            "PAUSE" -> if (isPlaying) playerManager?.togglePlayPause()
                            "SEEK" -> {
                                android.util.Log.i("MainActivity", "Remote SEEK to $pos ms")
                                seekToSafe(pos)
                                showOsd()
                            }
                            "STOP" -> stopPlayback()
                            "RW" -> {
                                val cur = playerManager?.getCurrentTime() ?: currentPositionMs
                                val target = (cur - 10000L).coerceAtLeast(0L)
                                android.util.Log.i("MainActivity", "Remote RW -10s: cur=$cur -> target=$target ms")
                                seekToSafe(target)
                                showOsd()
                            }
                            "FF" -> {
                                val cur = playerManager?.getCurrentTime() ?: currentPositionMs
                                val maxDur = if (totalDurationMs > 0) totalDurationMs else Long.MAX_VALUE
                                val target = (cur + 10000L).coerceAtMost(maxDur)
                                android.util.Log.i("MainActivity", "Remote FF +10s: cur=$cur -> target=$target ms")
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
            val cur = playerManager?.getCurrentTime() ?: currentPositionMs
            val target = (cur - 10000L).coerceAtLeast(0L)
            android.util.Log.i("MainActivity", "OSD RW -10s: cur=$cur -> target=$target ms")
            seekToSafe(target)
            resetOsdTimer()
        }

        binding.btnPlayerFf.setOnClickListener {
            val cur = playerManager?.getCurrentTime() ?: currentPositionMs
            val maxDur = if (totalDurationMs > 0) totalDurationMs else Long.MAX_VALUE
            val target = (cur + 10000L).coerceAtMost(maxDur)
            android.util.Log.i("MainActivity", "OSD FF +10s: cur=$cur -> target=$target ms")
            seekToSafe(target)
            resetOsdTimer()
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
                osdHandler.removeCallbacks(hideOsdRunnable)
            }

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val progress = seekBar?.progress ?: 0
                val effectiveLen = if (totalDurationMs > 0) totalDurationMs else (playerManager?.getDuration() ?: 0L)
                if (effectiveLen > 0) {
                    val seekTo = (progress.toDouble() / 1000.0 * effectiveLen).toLong()
                    android.util.Log.i("MainActivity", "TV Scrubber seekTo: $seekTo ms")
                    seekToSafe(seekTo)
                }
                resetOsdTimer()
            }
        })

        // Hardware decoder info
        binding.btnPlayerDecoder.setOnClickListener {
            Toast.makeText(this, "ExoPlayer MediaCodec Hardware Acceleration Active (60 FPS)", Toast.LENGTH_SHORT).show()
            resetOsdTimer()
        }

        binding.btnPlayerStop.setOnClickListener {
            stopPlayback()
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
            android.util.Log.i("MainActivity", "onPhoneDisconnected ignored because playback is currently active")
            return
        }
        android.util.Log.i("MainActivity", "onPhoneDisconnected: Updating TV UI immediately")
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
                                android.util.Log.i("MainActivity", "Health check failed: phone server unreachable (4 consecutive failures)")
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
        currentStreamUrl = streamUrl
        currentTitle = title
        if (durationMs > 0) {
            totalDurationMs = durationMs
        }

        binding.btnPlayerDecoder.text = "ExoPlayer HW"

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

        playerManager?.play(streamUrl, startPositionMs)
        showOsd()
        binding.btnPlayerPlayPause.requestFocus()
    }

    private fun stopPlayback() {
        osdHandler.removeCallbacks(hideOsdRunnable)
        playerManager?.stop()
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

        if (effectiveLen > 0 && !binding.sbPlayerProgress.isPressed) {
            val progress = ((timeMs.toDouble() / effectiveLen.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
            binding.sbPlayerProgress.progress = progress
        }
    }

    private fun showOsd() {
        binding.layoutOsd.visibility = View.VISIBLE
        resetOsdTimer()
    }

    private fun hideOsd() {
        if (isPlaying && binding.layoutPlayer.visibility == View.VISIBLE) {
            binding.layoutOsd.visibility = View.GONE
        }
    }

    private fun resetOsdTimer() {
        osdHandler.removeCallbacks(hideOsdRunnable)
        osdHandler.postDelayed(hideOsdRunnable, 5000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.layoutPlayer.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
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
                    val cur = playerManager?.getCurrentTime() ?: currentPositionMs
                    val maxDur = if (totalDurationMs > 0) totalDurationMs else Long.MAX_VALUE
                    val target = (cur + 10000L).coerceAtMost(maxDur)
                    android.util.Log.i("MainActivity", "Hardware FF: cur=$cur -> target=$target")
                    seekToSafe(target)
                    showOsd()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                    val cur = playerManager?.getCurrentTime() ?: currentPositionMs
                    val target = (cur - 10000L).coerceAtLeast(0L)
                    android.util.Log.i("MainActivity", "Hardware RW: cur=$cur -> target=$target")
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
        val isSeekable = playerManager?.isSeekable() ?: false
        if (isSeekable) {
            playerManager?.seekTo(targetMs)
            return
        }

        // Stream is not directly seekable yet (e.g. actively transcoding live stream).
        val mediaId = extractMediaId(currentStreamUrl)
        if (mediaId.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val statusUrl = "$phoneBaseUrl/api/v1/transcode/status?id=$mediaId"
                    val req = Request.Builder().url(statusUrl).build()
                    val res = httpClient.newCall(req).execute()
                    val body = res.body?.string() ?: ""
                    val json = org.json.JSONObject(body)
                    val isReady = json.optBoolean("isReady", false)
                    withContext(Dispatchers.Main) {
                        if (isReady) {
                            val progressiveUrl = "$phoneBaseUrl/stream/transcoded/$mediaId"
                            android.util.Log.i("MainActivity", "Switching to progressive URL on seek: $progressiveUrl at $targetMs ms")
                            startPlayback(progressiveUrl, currentTitle, totalDurationMs, startPositionMs = targetMs)
                        } else {
                            android.util.Log.w("MainActivity", "Seek blocked: Video transcode is still preparing/buffering")
                            Toast.makeText(this@MainActivity, "Buffering stream... Seeking will be ready shortly", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Failed checking transcode status: ${e.message}")
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
        healthCheckJob?.cancel()
        discoveryManager?.stop()
        qrDialog?.dismiss()
        osdHandler.removeCallbacks(hideOsdRunnable)
        receiverServer?.stop()
        playerManager?.release()
        super.onDestroy()
    }
}
