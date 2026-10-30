package com.pocketstream.mobile

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pocketstream.mobile.databinding.ActivityMainBinding
import com.pocketstream.mobile.server.NetworkUtils
import com.pocketstream.mobile.service.ServerService
import com.pocketstream.mobile.storage.LocalMedia
import com.pocketstream.mobile.storage.MediaRepository
import com.pocketstream.mobile.transcoder.TranscoderManager
import com.pocketstream.protocol.MediaItem
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pocketstream.mobile.discovery.MobileDiscoveryManager
import com.pocketstream.protocol.TvCapabilities
import com.pocketstream.protocol.TvInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
            private set
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: MediaAdapter
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var tvIsPlaying = false
    private var tvCurrentPosMs = 0L
    private var tvTotalDurationMs = 0L
    private var isUserTrackingScrubber = false
    private var activeStreamingMediaId: String? = null
    private var activeStreamingMediaTitle: String = ""
    private var activeStreamingDurationMs: Long = 0L
    private var statusSyncJob: Job? = null
    private var preparingDialog: AlertDialog? = null
    private var preparingCountdownJob: Job? = null

    private var connectedTvIp: String = ""
    private var connectedTvPort: Int = 8085
    private var connectedTvName: String = "Android TV"
    private var tvCapabilities: TvCapabilities = TvCapabilities.DEFAULT_1080P
    private var discoveryManager: MobileDiscoveryManager? = null

    private val prefs by lazy { getSharedPreferences("pocketstream_mobile_prefs", MODE_PRIVATE) }
    private fun resolvedTvIp(): String = connectedTvIp.ifEmpty { prefs.getString("last_tv_ip", "") ?: "" }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScannedQr(result.contents)
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let { uri ->
            binding.layoutScanProgress.visibility = View.VISIBLE
            binding.tvScanProgress.text = "Scanning selected folder..."
            lifecycleScope.launch(Dispatchers.IO) {
                MediaRepository.addFolderTree(applicationContext, uri)
                withContext(Dispatchers.Main) {
                    binding.layoutScanProgress.visibility = View.GONE
                    refreshLibrary()
                    Toast.makeText(this@MainActivity, "Folder added to library", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.let { list ->
            binding.layoutScanProgress.visibility = View.VISIBLE
            binding.tvScanProgress.text = "Adding ${list.size} video(s)..."
            lifecycleScope.launch(Dispatchers.IO) {
                list.forEach { uri ->
                    MediaRepository.addSingleFile(applicationContext, uri)
                }
                withContext(Dispatchers.Main) {
                    binding.layoutScanProgress.visibility = View.GONE
                    refreshLibrary()
                    Toast.makeText(this@MainActivity, "Videos added and saved", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateLayoutForOrientation(resources.configuration.orientation)

        // Restore active playback state if activity was recreated
        if (savedInstanceState != null) {
            val isVisible = savedInstanceState.getBoolean("is_now_playing_visible", false)
            val title = savedInstanceState.getString("active_stream_title", "")
            val id = savedInstanceState.getString("active_stream_id", null)
            val duration = savedInstanceState.getLong("active_stream_duration", 0L)
            if (isVisible && title.isNotEmpty()) {
                activeStreamingMediaId = id
                activeStreamingMediaTitle = title
                activeStreamingDurationMs = duration
                tvTotalDurationMs = savedInstanceState.getLong("tv_total_duration", duration)
                tvCurrentPosMs = savedInstanceState.getLong("tv_current_pos", 0L)
                tvIsPlaying = savedInstanceState.getBoolean("tv_is_playing", false)
                showNowPlaying(title, duration)
            }
        }

        // Restore last known TV connection
        val savedTvIp = prefs.getString("last_tv_ip", null)
        val savedTvPort = prefs.getInt("last_tv_port", 8085)
        val savedTvName = prefs.getString("last_tv_name", "Android TV") ?: "Android TV"

        val savedTvCapsJson = prefs.getString("last_tv_caps", null)
        if (!savedTvCapsJson.isNullOrEmpty()) {
            try {
                tvCapabilities = TvCapabilities.fromJson(JSONObject(savedTvCapsJson))
            } catch (_: Exception) {}
        }

        if (!savedTvIp.isNullOrEmpty()) {
            connectedTvIp = savedTvIp
            connectedTvPort = savedTvPort
            connectedTvName = savedTvName
            updateTvTargetUI(connectedTvIp, connectedTvPort, connectedTvName, isPaired = true)
            fetchTvCapabilities(connectedTvIp, connectedTvPort)
            syncTvPlaybackStatus()
        } else {
            updateTvTargetUI("", 8085, "", isPaired = false)
        }

        setupRecyclerView()
        setupListeners()
        setupDiscovery()
        updateServerUI()
        checkNotificationPermission()

        MediaRepository.onLibraryUpdated = {
            refreshLibrary()
            notifyTvLibraryUpdated()
        }

        // Initialize repository asynchronously so UI starts immediately without ANR
        binding.layoutScanProgress.visibility = View.VISIBLE
        binding.tvScanProgress.text = "Loading media library..."
        lifecycleScope.launch(Dispatchers.IO) {
            MediaRepository.init(applicationContext)
            withContext(Dispatchers.Main) {
                binding.layoutScanProgress.visibility = View.GONE
                refreshLibrary()
            }
        }

        // Auto-start server in background with smooth feedback
        if (!ServerService.isServerRunning) {
            toggleServerWithSpinner(start = true)
        }
        handleDebugIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayoutForOrientation(newConfig.orientation)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("active_stream_id", activeStreamingMediaId)
        outState.putString("active_stream_title", activeStreamingMediaTitle)
        outState.putLong("active_stream_duration", activeStreamingDurationMs)
        outState.putLong("tv_current_pos", tvCurrentPosMs)
        outState.putLong("tv_total_duration", tvTotalDurationMs)
        outState.putBoolean("tv_is_playing", tvIsPlaying)
        outState.putBoolean("is_now_playing_visible", binding.cardNowPlaying.visibility == View.VISIBLE)
    }

    private fun updateLayoutForOrientation(orientation: Int) {
        val isLandscape = (orientation == Configuration.ORIENTATION_LANDSCAPE)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        if (isLandscape) {
            binding.rootContainer.setPadding(dp(12), dp(6), dp(12), dp(6))
            binding.mainContentLayout.orientation = LinearLayout.HORIZONTAL

            val leftParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f).apply {
                marginEnd = dp(6)
            }
            binding.scrollLeftColumn.layoutParams = leftParams

            val rightParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f).apply {
                marginStart = dp(6)
            }
            binding.layoutRightColumn.layoutParams = rightParams

            binding.layoutNowPlayingInner.setPadding(dp(12), dp(6), dp(12), dp(6))
            (binding.cardNowPlaying.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = dp(4)
                lp.bottomMargin = 0
                binding.cardNowPlaying.layoutParams = lp
            }
        } else {
            binding.rootContainer.setPadding(dp(16), dp(12), dp(16), dp(12))
            binding.mainContentLayout.orientation = LinearLayout.VERTICAL

            val leftParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f).apply {
                marginEnd = 0
            }
            binding.scrollLeftColumn.layoutParams = leftParams

            val rightParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f).apply {
                marginStart = 0
            }
            binding.layoutRightColumn.layoutParams = rightParams

            binding.layoutNowPlayingInner.setPadding(dp(14), dp(12), dp(14), dp(12))
            (binding.cardNowPlaying.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.topMargin = dp(6)
                lp.bottomMargin = dp(2)
                binding.cardNowPlaying.layoutParams = lp
            }
        }
    }

    private fun syncTvPlaybackStatus() {
        val tvIp = resolvedTvIp()
        if (tvIp.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("http://$tvIp:$connectedTvPort/status").build()
                val res = httpClient.newCall(req).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val json = JSONObject(body)
                    val isPlaying = json.optBoolean("isPlaying", false)
                    val pos = json.optLong("positionMs", 0L)
                    val len = json.optLong("durationMs", 0L)
                    if (isPlaying || pos > 0L) {
                        withContext(Dispatchers.Main) {
                            tvIsPlaying = isPlaying
                            tvCurrentPosMs = pos
                            if (len > 0L) tvTotalDurationMs = len
                            val title = activeStreamingMediaTitle.ifEmpty { "TV Stream" }
                            showNowPlaying(title, len)
                        }
                    }
                }
                res.close()
            } catch (_: Exception) {}
        }
    }

    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugIntent(intent)
    }

    private fun handleDebugIntent(intent: Intent?) {
        val action = intent?.getStringExtra("action") ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            when (action) {
                "stop_server" -> {
                    if (ServerService.isServerRunning) {
                        toggleServerWithSpinner(start = false)
                    }
                }
                "click_stream" -> {
                    val mediaId = intent.getStringExtra("mediaId")
                    var items = MediaRepository.getAllMediaItems()
                    var attempts = 0
                    while (items.isEmpty() && attempts < 15) {
                        delay(200)
                        items = MediaRepository.getAllMediaItems()
                        attempts++
                    }
                    val target = if (mediaId != null) items.find { it.id == mediaId } else items.firstOrNull()
                    target?.let { handleStreamSelection(it) }
                }
                "show_remote" -> {
                    val items = MediaRepository.getAllMediaItems()
                    val target = items.firstOrNull()
                    val title = target?.title ?: "Demo Video Stream"
                    val dur = if ((target?.durationMs ?: 0L) > 0) target!!.durationMs else 600000L
                    activeStreamingMediaId = target?.id ?: "demo_id"
                    showNowPlaying(title, dur)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = MediaAdapter(
            items = emptyList(),
            onStreamClick = { item ->
                handleStreamSelection(item)
            },
            onStopStreamClick = { _ ->
                sendTvControl("STOP")
                hideNowPlaying()
                activeStreamingMediaId = null
                adapter.setActiveStreamingId(null)
                Toast.makeText(this@MainActivity, "Stopped TV streaming", Toast.LENGTH_SHORT).show()
            },
            onSelectionChanged = { count ->
                binding.btnRemoveSelected.text = "Remove ($count)"
                binding.btnRemoveSelected.isEnabled = count > 0
                val totalCount = MediaRepository.getAllMediaItems().size
                binding.btnSelectAll.text = if (count == totalCount && totalCount > 0) "Deselect All" else "Select All"
            }
        )
        binding.rvMediaList.layoutManager = LinearLayoutManager(this)
        binding.rvMediaList.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnScanTvQr.setOnClickListener {
            if (!ServerService.isServerRunning) {
                AlertDialog.Builder(this)
                    .setTitle("Media Server Required")
                    .setMessage("The Media Server must be running before pairing with your TV.\n\nPlease start the Media Server first.")
                    .setPositiveButton("Start & Scan") { _, _ ->
                        toggleServerWithSpinner(start = true) {
                            val options = ScanOptions().apply {
                                setPrompt("Scan the QR code displayed on your TV")
                                setBeepEnabled(true)
                                setOrientationLocked(false)
                                setBarcodeImageEnabled(false)
                            }
                            qrScannerLauncher.launch(options)
                        }
                    }
                    .setNeutralButton("Start Server Only") { _, _ ->
                        toggleServerWithSpinner(start = true)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@setOnClickListener
            }

            val options = ScanOptions().apply {
                setPrompt("Scan the QR code displayed on your TV")
                setBeepEnabled(true)
                setOrientationLocked(false)
                setBarcodeImageEnabled(false)
            }
            qrScannerLauncher.launch(options)
        }

        binding.btnToggleServer.setOnClickListener {
            val shouldStart = !ServerService.isServerRunning
            toggleServerWithSpinner(start = shouldStart)
        }

        binding.btnPickFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.btnPickFiles.setOnClickListener {
            filePickerLauncher.launch(arrayOf("video/*"))
        }

        // Toggle selection mode
        binding.btnToggleManageMode.setOnClickListener {
            setSelectionModeActive(!adapter.isSelectionMode)
        }

        binding.btnCancelSelection.setOnClickListener {
            setSelectionModeActive(false)
        }

        binding.btnSelectAll.setOnClickListener {
            adapter.selectAll()
        }

        binding.btnRemoveSelected.setOnClickListener {
            val selected = adapter.getSelectedIds()
            if (selected.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("Remove from Library?")
                .setMessage("Remove ${selected.size} video(s) from your streaming library?\n\n(Original files on your phone storage will NOT be deleted).")
                .setPositiveButton("Remove") { _, _ ->
                    MediaRepository.removeMediaItems(applicationContext, selected)
                    setSelectionModeActive(false)
                    refreshLibrary()
                    Toast.makeText(this, "Removed ${selected.size} video(s)", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Phase 3: Now Playing Remote Control listeners
        binding.btnRemotePlayPause.setOnClickListener {
            sendTvControl(if (tvIsPlaying) "PAUSE" else "PLAY")
        }
        binding.btnRemoteRw.setOnClickListener {
            sendTvControl("RW")
        }
        binding.btnRemoteFf.setOnClickListener {
            sendTvControl("FF")
        }
        binding.btnRemoteRestart.setOnClickListener {
            val currentMedia = activeStreamingMediaId?.let { MediaRepository.getMediaById(it) }
            val isLiveTranscoded = currentMedia != null && (
                TranscoderManager.isTranscoding(currentMedia.id) ||
                currentMedia.is10Bit ||
                activeStreamingMediaTitle.contains("[Live", ignoreCase = true) ||
                activeStreamingMediaTitle.contains("[Converted", ignoreCase = true)
            ) && TranscoderManager.getTranscodedFile(this@MainActivity, currentMedia.id) == null

            if (isLiveTranscoded && currentMedia != null) {
                val localIp = NetworkUtils.getLocalWifiIpAddress()
                startLiveStreamingAndTranscoding(currentMedia, localIp, startPositionMs = 0L)
            } else {
                sendTvControl("RESTART")
            }
        }
        binding.btnRemoteStop.setOnClickListener {
            sendTvControl("STOP")
            hideNowPlaying()
            activeStreamingMediaId = null
            adapter.setActiveStreamingId(null)
        }
        binding.sbRemoteScrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && tvTotalDurationMs > 0) {
                    val seekTo = (progress.toDouble() / 1000.0 * tvTotalDurationMs).toLong()
                    val posStr = formatTime(seekTo)
                    val lenStr = formatTime(tvTotalDurationMs)
                    binding.tvNowPlayingTime.text = "$posStr / $lenStr"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingScrubber = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingScrubber = false
                val progress = seekBar?.progress ?: 0
                if (tvTotalDurationMs > 0) {
                    val seekTo = (progress.toDouble() / 1000.0 * tvTotalDurationMs).toLong()
                    val currentMedia = activeStreamingMediaId?.let { MediaRepository.getMediaById(it) }
                    val isLiveTranscoded = currentMedia != null && (
                        TranscoderManager.isTranscoding(currentMedia.id) ||
                        currentMedia.is10Bit ||
                        activeStreamingMediaTitle.contains("[Live", ignoreCase = true) ||
                        activeStreamingMediaTitle.contains("[Converted", ignoreCase = true)
                    ) && TranscoderManager.getTranscodedFile(this@MainActivity, currentMedia.id) == null

                    if (isLiveTranscoded && currentMedia != null) {
                        val localIp = NetworkUtils.getLocalWifiIpAddress()
                        startLiveStreamingAndTranscoding(currentMedia, localIp, startPositionMs = seekTo)
                    } else {
                        sendTvControl("SEEK", seekTo)
                    }
                }
            }
        })
    }

    private fun setSelectionModeActive(active: Boolean) {
        adapter.setSelectionMode(active)
        if (active) {
            binding.layoutSelectionToolbar.visibility = View.VISIBLE
            binding.btnToggleManageMode.text = "Done"
            binding.btnRemoveSelected.isEnabled = false
            binding.btnRemoveSelected.text = "Remove (0)"
            binding.btnSelectAll.text = "Select All"
        } else {
            binding.layoutSelectionToolbar.visibility = View.GONE
            binding.btnToggleManageMode.text = "Select"
        }
    }

    private fun toggleServerWithSpinner(start: Boolean, onComplete: (() -> Unit)? = null) {
        binding.pbServerToggleLoading.visibility = View.VISIBLE
        binding.btnToggleServer.isEnabled = false
        binding.btnToggleServer.text = if (start) "Starting..." else "Stopping..."
        binding.tvStatusBadge.text = if (start) "STARTING..." else "STOPPING..."
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        lifecycleScope.launch(Dispatchers.IO) {
            if (start) {
                ServerService.start(applicationContext)

                // Actively poll until the local server is confirmed listening and responding
                var isReady = false
                var attempts = 0
                while (!isReady && attempts < 25) {
                    try {
                        val testReq = Request.Builder()
                            .url("http://127.0.0.1:8080/api/v1/library")
                            .get()
                            .build()
                        val res = httpClient.newCall(testReq).execute()
                        isReady = res.isSuccessful
                        res.close()
                    } catch (_: Exception) {}
                    if (!isReady) {
                        delay(120)
                        attempts++
                    }
                }
                delay(200)

                discoveryManager?.broadcastDiscoverTv()
                val targetIp = resolvedTvIp()
                if (targetIp.isNotEmpty()) {
                    sendPairHandshake(targetIp, connectedTvPort)
                    delay(400)
                    notifyTvLibraryUpdated()
                }
            } else {
                preparingCountdownJob?.cancel()
                preparingCountdownJob = null
                withContext(Dispatchers.Main) {
                    preparingDialog?.dismiss()
                    preparingDialog = null
                    hideNowPlaying()
                    activeStreamingMediaId = null
                    adapter.setActiveStreamingId(null)
                }
                TranscoderManager.purgeAllCache(applicationContext)
                sendTvControl("STOP")
                notifyTvDisconnected()
                discoveryManager?.broadcastPhoneDisconnect()
                ServerService.stop(applicationContext)
            }
            kotlinx.coroutines.delay(400)
            withContext(Dispatchers.Main) {
                binding.pbServerToggleLoading.visibility = View.GONE
                binding.btnToggleServer.isEnabled = true
                updateServerUI()
                onComplete?.invoke()
            }
        }
    }

    private fun updateServerUI() {
        val isRunning = ServerService.isServerRunning
        val localIp = NetworkUtils.getLocalWifiIpAddress()
        val url = "http://$localIp:8080"

        if (isRunning) {
            binding.tvStatusBadge.text = "RUNNING"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnToggleServer.text = "Stop"
            binding.tvServerAddress.text = url
        } else {
            binding.tvStatusBadge.text = "STOPPED"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.btnToggleServer.text = "Start"
            binding.tvServerAddress.text = "Server offline"
        }
    }

    private fun refreshLibrary() {
        val items = MediaRepository.getAllMediaItems()
        adapter.updateList(items)
        binding.tvVideoCount.text = "${items.size} videos"

        if (items.isEmpty()) {
            binding.tvEmptyLibrary.visibility = View.VISIBLE
            binding.rvMediaList.visibility = View.GONE
            binding.btnToggleManageMode.visibility = View.GONE
        } else {
            binding.tvEmptyLibrary.visibility = View.GONE
            binding.rvMediaList.visibility = View.VISIBLE
            binding.btnToggleManageMode.visibility = View.VISIBLE
        }
    }

    private fun handleStreamSelection(item: MediaItem) {
        if (!ServerService.isServerRunning) {
            Toast.makeText(this, "Starting media server", Toast.LENGTH_SHORT).show()

            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_starting_server, null)
            val startingDialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            startingDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            startingDialog.show()

            toggleServerWithSpinner(start = true) {
                if (!isFinishing && !isDestroyed) {
                    startingDialog.dismiss()
                }
                if (ServerService.isServerRunning) {
                    // Only then goto next part
                    handleStreamSelection(item)
                } else {
                    Toast.makeText(this@MainActivity, "Failed to start media server", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        val localMedia = MediaRepository.getMediaById(item.id) ?: return
        val localIp = NetworkUtils.getLocalWifiIpAddress()

        val isAlreadyTranscoded = TranscoderManager.getTranscodedFile(this, localMedia.id) != null
        if (isAlreadyTranscoded) {
            val transcodedUrl = "http://$localIp:8080/stream/transcoded/${localMedia.id}"
            sendStreamToTv(transcodedUrl, "[Converted] ${localMedia.title}", localMedia.id, localMedia.durationMs)
            return
        }

        // Dynamically evaluate video specs against TV hardware capabilities
        val is10Bit = localMedia.is10Bit ||
                item.title.contains("10bit", ignoreCase = true) ||
                item.title.contains("10-bit", ignoreCase = true) ||
                item.title.contains("hdr", ignoreCase = true)

        val codecToCheck = localMedia.videoCodec.ifEmpty { localMedia.mimeType }
        val compatibility = tvCapabilities.checkCompatibility(
            videoMime = codecToCheck,
            width = localMedia.width,
            height = localMedia.height,
            is10Bit = is10Bit
        )

        if (!compatibility.isCompatible) {
            showIncompatibleFormatDialog(localMedia, localIp, compatibility.unsupportedReasons)
        } else {
            showStandardStreamDialog(item, localIp)
        }
    }

    private fun showIncompatibleFormatDialog(media: LocalMedia, localIp: String, reasons: List<String>) {
        val isAlreadyTranscoded = TranscoderManager.getTranscodedFile(this, media.id) != null
        if (isAlreadyTranscoded) {
            val transcodedUrl = "http://$localIp:8080/stream/transcoded/${media.id}"
            sendStreamToTv(transcodedUrl, "[Converted] ${media.title}", media.id, media.durationMs)
            return
        }

        val bulletPoints = reasons.joinToString("\n") { "• $it" }
        val tvName = if (connectedTvName.isNotEmpty()) connectedTvName else tvCapabilities.deviceModel
        val messageText = "This video is not supported directly by your $tvName:\n\n$bulletPoints\n\nYour phone can real-time hardware-encode this video using its hardware encoder for smooth, stutter-free playback on your TV."

        AlertDialog.Builder(this)
            .setTitle(media.title)
            .setMessage(messageText)
            .setPositiveButton("Encode & Stream (Recommended)") { _, _ ->
                startLiveStreamingAndTranscoding(media, localIp)
            }
            .setNeutralButton("Direct Play Anyway") { _, _ ->
                val directUrl = "http://$localIp:8080/stream/${media.id}"
                sendStreamToTv(directUrl, media.title, media.id, media.durationMs)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun handleSeekAndTranscode(media: LocalMedia, positionMs: Long) {
        val localIp = NetworkUtils.getLocalWifiIpAddress()
        startLiveStreamingAndTranscoding(media, localIp, startPositionMs = positionMs)
    }

    private fun startLiveStreamingAndTranscoding(media: LocalMedia, localIp: String, startPositionMs: Long = 0L) {
        if (media.durationMs <= 0L) {
            try {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(this, media.uri)
                val probedDuration = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (probedDuration > 0L) {
                    media.durationMs = probedDuration
                }
                mmr.release()
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to probe duration on demand: ${e.message}")
            }
        }
        val duration = media.durationMs
        if (startPositionMs == 0L) {
            val existing = TranscoderManager.getTranscodedFile(this, media.id)
            if (existing != null) {
                val transcodedUrl = "http://$localIp:8080/stream/transcoded/${media.id}"
                sendStreamToTv(transcodedUrl, "[Converted] ${media.title}", media.id, duration)
                return
            }
        }

        // If seeking to a position, notify TV immediately so TV can pause and show preparing countdown
        if (startPositionMs > 0L) {
            sendTvControl("PREPARING_SEEK", startPositionMs)
        }

        val liveUrl = "http://$localIp:8080/stream/live/${media.id}?t=${System.currentTimeMillis()}"

        // Custom Preparing Dialog with 30s Countdown and Live Transcoding Progress
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_preparing_stream, null)
        val tvCountdown = dialogView.findViewById<TextView>(R.id.tvPreparingCountdown)
        val pbCountdown = dialogView.findViewById<ProgressBar>(R.id.pbPreparingCountdown)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tvPreparingProgress)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelPreparing)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPreparingTitle)
        if (startPositionMs > 0L && tvTitle != null) {
            tvTitle.text = "Seeking to ${formatTime(startPositionMs)}"
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        preparingDialog?.dismiss()
        preparingDialog = dialog
        dialog.show()

        var currentProgressPercent = 0
        var isStreamDispatched = false

        btnCancel.setOnClickListener {
            preparingCountdownJob?.cancel()
            preparingCountdownJob = null
            dialog.dismiss()
            TranscoderManager.cancelActive(applicationContext)
            Toast.makeText(this, "Streaming cancelled", Toast.LENGTH_SHORT).show()
        }

        // Start hardware transcode immediately via device hardware encoder
        TranscoderManager.startLiveTranscode(
            context = applicationContext,
            media = media,
            startPositionMs = startPositionMs,
            onReadyToStream = {
                // Background chunks buffering
            },
            onProgress = { percent ->
                currentProgressPercent = percent
                tvProgress.text = "Hardware Transcode: $percent%"
            },
            onCompleted = { _ ->
                // If TV is currently streaming this media from 0, switch TV to the progressive seekable MP4!
                if (startPositionMs == 0L && activeStreamingMediaId == media.id) {
                    val currentPos = tvCurrentPosMs
                    val transcodedUrl = "http://$localIp:8080/stream/transcoded/${media.id}"
                    sendStreamToTv(transcodedUrl, "[Converted] ${media.title}", media.id, duration, startPositionMs = currentPos)
                }
            },
            onError = { err ->
                preparingCountdownJob?.cancel()
                preparingCountdownJob = null
                dialog.dismiss()
                Toast.makeText(this, "Transcoder error: $err", Toast.LENGTH_LONG).show()
            }
        )

        // 30 seconds buffer period (counts down 30s -> 0s)
        preparingCountdownJob?.cancel()
        preparingCountdownJob = lifecycleScope.launch(Dispatchers.Main) {
            val totalSeconds = 30
            for (secRemaining in totalSeconds downTo 1) {
                tvCountdown.text = "Starting stream in ${secRemaining}s..."
                pbCountdown.progress = totalSeconds - secRemaining
                tvProgress.text = "Hardware Transcode: $currentProgressPercent%"
                delay(1000)
            }

            // 30 seconds buffer elapsed! Hardware encoder has buffered ~4-5 minutes of video!
            if (!isStreamDispatched && !isFinishing && !isDestroyed) {
                isStreamDispatched = true
                dialog.dismiss()
                val readyFile = if (startPositionMs == 0L) TranscoderManager.getTranscodedFile(this@MainActivity, media.id) else null
                val urlToSend = if (readyFile != null) {
                    "http://$localIp:8080/stream/transcoded/${media.id}"
                } else {
                    liveUrl
                }
                val streamTitle = if (readyFile != null) "[Converted] ${media.title}" else "[Live 8-bit] ${media.title}"
                sendStreamToTv(urlToSend, streamTitle, media.id, duration, startPositionMs = startPositionMs)
            }
        }
    }

    private fun showStandardStreamDialog(item: MediaItem, localIp: String) {
        val localMedia = MediaRepository.getMediaById(item.id)
        val streamUrl = "http://$localIp:8080${item.streamPath}"
        val targetLabel = if (connectedTvIp.isNotEmpty()) "$connectedTvName ($connectedTvIp)" else "TV"
        val options = arrayOf(
            "Direct Play on $targetLabel (Original Quality)",
            "Live Encode & Stream (Hardware Transcoder)",
            "Play Locally (Preview)",
            "Copy Stream URL"
        )

        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> sendStreamToTv(streamUrl, item.title, item.id, item.durationMs)
                    1 -> {
                        if (localMedia != null) {
                            startLiveStreamingAndTranscoding(localMedia, localIp)
                        } else {
                            sendStreamToTv(streamUrl, item.title, item.id, item.durationMs)
                        }
                    }
                    2 -> {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(streamUrl), item.mimeType)
                        }
                        startActivity(intent)
                    }
                    3 -> copyUrl(streamUrl)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyUrl(url: String) {
        val clip = android.content.ClipData.newPlainText("Stream URL", url)
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(clip)
        Toast.makeText(this, "Copied: $url", Toast.LENGTH_LONG).show()
    }

    private fun sendStreamToTv(
        streamUrl: String,
        title: String,
        id: String,
        durationMs: Long = 0L,
        startPositionMs: Long = 0L
    ) {
        val tvIp = resolvedTvIp()
        if (tvIp.isEmpty()) {
            Toast.makeText(this, "Please pair with your TV first using QR code or Auto-Discovery", Toast.LENGTH_LONG).show()
            return
        }
        val tvPort = connectedTvPort
        val tvApiUrl = "http://$tvIp:$tvPort/play"

        Toast.makeText(this, "Sending to TV at $tvIp...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("streamUrl", streamUrl)
                    put("title", title)
                    put("id", id)
                    put("durationMs", durationMs)
                    put("startPositionMs", startPositionMs)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(tvApiUrl).post(body).build()
                val response = httpClient.newCall(request).execute()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Playing on TV!", Toast.LENGTH_SHORT).show()
                        activeStreamingMediaId = id
                        adapter.setActiveStreamingId(id)
                        showNowPlaying(title, durationMs)
                    } else {
                        Toast.makeText(this@MainActivity, "TV replied: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Could not reach TV receiver: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showNowPlaying(title: String, durationMs: Long = 0L) {
        activeStreamingMediaTitle = title
        if (durationMs > 0) {
            tvTotalDurationMs = durationMs
            activeStreamingDurationMs = durationMs
        }
        binding.cardNowPlaying.visibility = View.VISIBLE
        binding.tvNowPlayingTitle.text = title
        startTvStatusSync()
    }

    private fun hideNowPlaying() {
        binding.cardNowPlaying.visibility = View.GONE
        statusSyncJob?.cancel()
        statusSyncJob = null
        activeStreamingMediaId = null
        activeStreamingMediaTitle = ""
        activeStreamingDurationMs = 0L
        adapter.setActiveStreamingId(null)
    }

    private fun startTvStatusSync() {
        statusSyncJob?.cancel()
        statusSyncJob = lifecycleScope.launch(Dispatchers.IO) {
            val tvIp = resolvedTvIp()
            if (tvIp.isEmpty()) return@launch

            val tvPort = connectedTvPort
            val statusUrl = "http://$tvIp:$tvPort/status"

            while (isActive) {
                try {
                    val request = Request.Builder().url(statusUrl).build()
                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        val isPlaying = json.optBoolean("isPlaying", false)
                        val pos = json.optLong("positionMs", 0L)
                        val len = json.optLong("durationMs", 0L)

                        withContext(Dispatchers.Main) {
                            tvIsPlaying = isPlaying
                            tvCurrentPosMs = pos
                            if (len > 0) {
                                tvTotalDurationMs = len
                            }

                            if (!isPlaying && pos == 0L && len == 0L && activeStreamingMediaId != null) {
                                hideNowPlaying()
                            } else {
                                binding.btnRemotePlayPause.text = if (isPlaying) "Pause" else "Play"
                                binding.tvNowPlayingBadge.text = if (isPlaying) "TV PLAYING" else "PAUSED"
                                binding.tvNowPlayingBadge.setTextColor(
                                    ContextCompat.getColor(
                                        this@MainActivity,
                                        if (isPlaying) R.color.accent_green else R.color.text_muted
                                    )
                                )

                                val effectiveLen = if (tvTotalDurationMs > 0) tvTotalDurationMs else len
                                val posStr = formatTime(pos)
                                val lenStr = if (effectiveLen > 0) formatTime(effectiveLen) else "Live"
                                binding.tvNowPlayingTime.text = "$posStr / $lenStr"

                                if (!isUserTrackingScrubber && effectiveLen > 0) {
                                    val progress = ((pos.toDouble() / effectiveLen.toDouble()) * 1000).toInt()
                                    binding.sbRemoteScrubber.progress = progress
                                }
                            }
                        }
                    }
                } catch (ignored: Exception) {}
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun sendTvControl(action: String, positionMs: Long = 0L) {
        val tvIp = resolvedTvIp()
        if (tvIp.isEmpty()) return

        val tvPort = connectedTvPort
        val controlUrl = "http://$tvIp:$tvPort/control"

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("action", action)
                    put("positionMs", positionMs)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(controlUrl).post(body).build()
                httpClient.newCall(request).execute()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleScannedQr(contents: String) {
        val tvInfo = TvInfo.fromQrString(contents)
        if (tvInfo != null) {
            onTvDiscovered(tvInfo.ip, tvInfo.port, tvInfo.name)
            sendPairHandshake(tvInfo.ip, tvInfo.port)
            Toast.makeText(this, "Paired with ${tvInfo.name}!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Unrecognized QR code", Toast.LENGTH_LONG).show()
        }
    }

    private fun onTvDiscovered(tvIp: String, tvPort: Int, tvName: String) {
        connectedTvIp = tvIp
        connectedTvPort = tvPort
        connectedTvName = tvName
        updateTvTargetUI(tvIp, tvPort, tvName, isPaired = true)

        prefs.edit()
            .putString("last_tv_ip", tvIp)
            .putInt("last_tv_port", tvPort)
            .putString("last_tv_name", tvName)
            .apply()

        // When auto-paired with TV, exchange handshake or fetch capabilities
        if (ServerService.isServerRunning) {
            sendPairHandshake(tvIp, tvPort)
        } else {
            fetchTvCapabilities(tvIp, tvPort)
        }
    }

    private fun updateTvTargetUI(tvIp: String, tvPort: Int, tvName: String, isPaired: Boolean) {
        if (isPaired && tvIp.isNotEmpty()) {
            binding.tvTvTargetName.text = tvName
            val formatNotice = if (tvCapabilities.supports4K) "4K" else "1080p Max"
            binding.tvTvTargetStatus.text = "Connected ($tvIp:$tvPort) • $formatNotice"
            binding.tvTvTargetStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            binding.tvTvTargetName.text = "Target TV"
            binding.tvTvTargetStatus.text = "Searching on Wi-Fi or tap Scan..."
            binding.tvTvTargetStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun sendPairHandshake(tvIp: String, tvPort: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localIp = NetworkUtils.getLocalWifiIpAddress()
                val phoneName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "PocketStream Phone" }
                val json = JSONObject().apply {
                    put("phoneIp", localIp)
                    put("phonePort", 8080)
                    put("deviceName", "$phoneName / PocketStream")
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url("http://$tvIp:$tvPort/pair").post(body).build()
                val res = httpClient.newCall(req).execute()
                val resBody = res.body?.string() ?: ""
                res.close()
                if (res.isSuccessful && resBody.isNotEmpty()) {
                    val resJson = JSONObject(resBody)
                    val capsJson = resJson.optJSONObject("capabilities")
                    if (capsJson != null) {
                        val parsed = TvCapabilities.fromJson(capsJson)
                        tvCapabilities = parsed
                        prefs.edit()
                            .putString("last_tv_caps", capsJson.toString())
                            .apply()
                        withContext(Dispatchers.Main) {
                            updateTvTargetUI(tvIp, tvPort, connectedTvName, isPaired = true)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun fetchTvCapabilities(tvIp: String, tvPort: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val req = Request.Builder().url("http://$tvIp:$tvPort/capabilities").build()
                val res = httpClient.newCall(req).execute()
                val resBody = res.body?.string() ?: ""
                res.close()
                if (res.isSuccessful && resBody.isNotEmpty()) {
                    val capsJson = JSONObject(resBody)
                    val parsed = TvCapabilities.fromJson(capsJson)
                    tvCapabilities = parsed
                    prefs.edit()
                        .putString("last_tv_caps", capsJson.toString())
                        .apply()
                    withContext(Dispatchers.Main) {
                        updateTvTargetUI(tvIp, tvPort, connectedTvName, isPaired = true)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun notifyTvDisconnected() {
        val targetIp = resolvedTvIp()
        val targetPort = connectedTvPort
        if (targetIp.isNotEmpty()) {
            try {
                val req = Request.Builder()
                    .url("http://$targetIp:$targetPort/disconnect")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyTvLibraryUpdated() {
        if (!ServerService.isServerRunning) return
        val targetIp = resolvedTvIp()
        val targetPort = connectedTvPort
        if (targetIp.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val req = Request.Builder()
                        .url("http://$targetIp:$targetPort/refresh")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setupDiscovery() {
        discoveryManager?.stop()
        discoveryManager = MobileDiscoveryManager(this, phonePort = 8080) { tvIp, tvPort, tvName ->
            runOnUiThread {
                onTvDiscovered(tvIp, tvPort, tvName)
            }
        }
        discoveryManager?.start()
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val sec = totalSec % 60
        val min = (totalSec / 60) % 60
        val hr = totalSec / 3600
        return if (hr > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hr, min, sec)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", min, sec)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServerUI()
        syncTvPlaybackStatus()
    }

    fun onTvPlaybackStarted(mediaId: String, title: String, durationMs: Long) {
        runOnUiThread {
            activeStreamingMediaId = mediaId
            adapter.setActiveStreamingId(mediaId)
            showNowPlaying(title, durationMs)
            startTvStatusSync()
        }
    }

    fun onTvPlaybackStopped() {
        runOnUiThread {
            activeStreamingMediaId = null
            adapter.setActiveStreamingId(null)
            hideNowPlaying()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        discoveryManager?.stop()
        statusSyncJob?.cancel()
        preparingCountdownJob?.cancel()
        preparingDialog?.dismiss()
        super.onDestroy()
    }
}