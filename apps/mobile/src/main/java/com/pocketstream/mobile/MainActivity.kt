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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import okhttp3.RequestBody
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
    var activeStreamingMediaId: String? = null
    var activeStreamingMediaTitle: String = ""
    private var activeStreamingDurationMs: Long = 0L
    private var statusSyncJob: Job? = null
    private var preparingDialog: AlertDialog? = null
    private var preparingCountdownJob: Job? = null

    private var connectedTvIp: String = ""
    private var connectedTvPort: Int = 8085
    private var connectedTvName: String = "Android TV"
    private var connectedTvSessionId: String = ""
    private var connectedTvSessionKey: String = ""
    private var tvCapabilities: TvCapabilities = TvCapabilities.DEFAULT_1080P
    private var discoveryManager: MobileDiscoveryManager? = null
    private var isTvPaired: Boolean = false

    private val prefs by lazy { getSharedPreferences("pocketstream_mobile_prefs", MODE_PRIVATE) }
    fun resolvedTvIp(): String = connectedTvIp.ifEmpty { prefs.getString("last_tv_ip", "") ?: "" }
    private fun resolvedTvSessionKey(): String = connectedTvSessionKey.ifEmpty { prefs.getString("last_tv_key", "") ?: "" }
    private fun resolvedTvSessionId(): String = connectedTvSessionId.ifEmpty { prefs.getString("last_tv_sid", "") ?: "" }

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

    private var statusBarHeightPx: Int = 0
    private var navBarHeightPx: Int = 0
    private var periodicTvPollJob: Job? = null

    private fun getStatusBarHeight(): Int {
        if (statusBarHeightPx > 0) return statusBarHeightPx
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        return if (result > 0) result else (38 * resources.displayMetrics.density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            var changed = false
            if (sysBars.top > 0 && sysBars.top != statusBarHeightPx) {
                statusBarHeightPx = sysBars.top
                changed = true
            }
            if (sysBars.bottom != navBarHeightPx) {
                navBarHeightPx = sysBars.bottom
                changed = true
            }
            if (changed) {
                updateLayoutForOrientation(resources.configuration.orientation)
            }
            insets
        }

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

        val hasSavedKey = resolvedTvSessionKey().isNotEmpty()
        if (!savedTvIp.isNullOrEmpty() && hasSavedKey) {
            connectedTvIp = savedTvIp
            connectedTvPort = savedTvPort
            connectedTvName = savedTvName
            updateTvTargetUI(connectedTvIp, connectedTvPort, connectedTvName, isPaired = true)
            fetchTvCapabilities(connectedTvIp, connectedTvPort)
            sendPairHandshake(connectedTvIp, connectedTvPort)
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

        val statusBarTop = getStatusBarHeight()
        val extraTopMargin = dp(8)
        val navBarBottom = navBarHeightPx

        if (isLandscape) {
            val bottomPad = navBarBottom + dp(8)
            binding.rootContainer.setPadding(dp(12), dp(6), dp(12), bottomPad)
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
                lp.bottomMargin = dp(4)
                binding.cardNowPlaying.layoutParams = lp
            }
        } else {
            val bottomPad = navBarBottom + dp(18)
            binding.rootContainer.setPadding(dp(16), statusBarTop + extraTopMargin, dp(16), bottomPad)
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
                lp.bottomMargin = dp(8)
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
                    val statusTitle = json.optString("title", "")
                    val streamUrl = json.optString("streamUrl", "")
                    val isPhoneConnected = json.optBoolean("isPhoneConnected", true)
                    val connectedPhoneIp = json.optString("connectedPhoneIp", "")
                    val localIp = NetworkUtils.getLocalWifiIpAddress()

                    val tvSessionId = json.optString("sessionId", "")
                    if (connectedTvSessionId.isNotEmpty() && tvSessionId.isNotEmpty() && tvSessionId != connectedTvSessionId) {
                        android.util.Log.w("MainActivity", "TV session ID changed ($connectedTvSessionId -> $tvSessionId), TV disconnected")
                        withContext(Dispatchers.Main) {
                            onTvExplicitlyDisconnected()
                        }
                        return@launch
                    }

                    if (connectedPhoneIp.isNotEmpty() && !isSameIp(connectedPhoneIp, localIp)) {
                        android.util.Log.w("MainActivity", "Another phone ($connectedPhoneIp) is connected to TV, disconnecting")
                        withContext(Dispatchers.Main) {
                            onTvExplicitlyDisconnected()
                        }
                        return@launch
                    }
                    if (isPlaying || (pos > 0L && streamUrl.isNotEmpty() && statusTitle.isNotEmpty())) {
                        withContext(Dispatchers.Main) {
                            tvIsPlaying = isPlaying
                            tvCurrentPosMs = pos
                            if (len > 0L) tvTotalDurationMs = len
                            val displayTitle = if (statusTitle.isNotEmpty()) statusTitle else activeStreamingMediaTitle.ifEmpty { "TV Stream" }
                            if (activeStreamingMediaId == null) {
                                val match = MediaRepository.getAllMediaItems().firstOrNull {
                                    it.title == displayTitle || (streamUrl.isNotEmpty() && streamUrl.contains(it.id))
                                }
                                if (match != null) {
                                    activeStreamingMediaId = match.id
                                    adapter.setActiveStreamingId(match.id)
                                }
                            }
                            showNowPlaying(displayTitle, len)
                            if (!isUserTrackingScrubber && tvTotalDurationMs > 0) {
                                val progress = ((pos.toDouble() / tvTotalDurationMs.toDouble()) * 1000).toInt()
                                binding.sbRemoteScrubber.progress = progress
                                val posStr = formatTime(pos)
                                val lenStr = formatTime(tvTotalDurationMs)
                                binding.tvNowPlayingTime.text = "$posStr / $lenStr"
                            }
                        }
                    } else if (activeStreamingMediaId != null) {
                        withContext(Dispatchers.Main) {
                            hideNowPlaying()
                        }
                    }
                }
                res.close()
            } catch (_: Exception) {}
        }
    }

    private fun startPeriodicTvPoll() {
        periodicTvPollJob?.cancel()
        periodicTvPollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (resolvedTvIp().isNotEmpty() && activeStreamingMediaId == null) {
                    syncTvPlaybackStatus()
                }
                kotlinx.coroutines.delay(2500)
            }
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
                "pair_qr" -> {
                    val qr = intent.getStringExtra("qr")
                    if (!qr.isNullOrEmpty()) {
                        handleScannedQr(qr)
                    }
                }
                "stop_server" -> {
                    if (ServerService.isServerRunning) {
                        toggleServerWithSpinner(start = false)
                    }
                }
                "start_server" -> {
                    if (!ServerService.isServerRunning) {
                        toggleServerWithSpinner(start = true)
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
            val options = ScanOptions().apply {
                setPrompt("Scan the QR code displayed on your TV")
                setBeepEnabled(true)
                setOrientationLocked(false)
                setBarcodeImageEnabled(false)
            }
            qrScannerLauncher.launch(options)
        }

        binding.btnToggleServer.setOnClickListener {
            if (!isTvPaired && !ServerService.isServerRunning) {
                Toast.makeText(this, "Please pair with TV first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
        binding.btnRemotePlayPause.setTextColor(android.graphics.Color.BLACK)
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
                    val port = ServerService.serverPort
                    try {
                        val testReq = Request.Builder()
                            .url("http://127.0.0.1:$port/api/v1/library")
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
                if (targetIp.isNotEmpty() && resolvedTvSessionKey().isNotEmpty()) {
                    sendPairHandshake(targetIp, connectedTvPort)
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
                try {
                    val targetIp = resolvedTvIp()
                    if (targetIp.isNotEmpty()) {
                        val stopJson = JSONObject().apply {
                            put("action", "STOP")
                            put("positionMs", 0L)
                        }
                        val stopBodyStr = stopJson.toString()
                        val stopReq = buildAuthenticatedTvRequest("http://$targetIp:$connectedTvPort/control", "POST", stopBodyStr.toRequestBody("application/json".toMediaType()), stopBodyStr)
                        httpClient.newCall(stopReq).execute().close()
                    }
                } catch (_: Exception) {}
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
        val url = "http://$localIp:${ServerService.serverPort}"

        if (isRunning) {
            binding.tvStatusBadge.text = "RUNNING"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnToggleServer.isEnabled = true
            binding.btnToggleServer.alpha = 1.0f
            binding.btnToggleServer.text = "Stop"
            binding.tvServerAddress.text = url
        } else {
            binding.tvStatusBadge.text = "STOPPED"
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.btnToggleServer.text = "Start"
            if (isTvPaired) {
                binding.btnToggleServer.isEnabled = true
                binding.btnToggleServer.alpha = 1.0f
                binding.tvServerAddress.text = "Server offline"
            } else {
                binding.btnToggleServer.isEnabled = false
                binding.btnToggleServer.alpha = 0.5f
                binding.tvServerAddress.text = "Pair with TV to start server"
            }
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
            val transcodedUrl = "http://$localIp:${ServerService.serverPort}/stream/transcoded/${localMedia.id}"
            sendStreamToTv(transcodedUrl, "[Converted] ${localMedia.title}", localMedia.id, localMedia.durationMs)
            return
        }

        // Technical analysis - NO filename matching
        val is10Bit = localMedia.is10Bit

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
            val transcodedUrl = "http://$localIp:${ServerService.serverPort}/stream/transcoded/${media.id}"
            sendStreamToTv(transcodedUrl, "[Converted] ${media.title}", media.id, media.durationMs)
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_playback_compatibility, null)
        val tvFilename = dialogView.findViewById<TextView>(R.id.tvCompatFilename)
        val tvWarning = dialogView.findViewById<TextView>(R.id.tvCompatWarning)
        val tvHdr = dialogView.findViewById<TextView>(R.id.tvCompatHdr)
        val tvChip = dialogView.findViewById<TextView>(R.id.tvCompatChip)
        val btnPrimary = dialogView.findViewById<Button>(R.id.btnCompatPrimary)
        val btnSecondary = dialogView.findViewById<Button>(R.id.btnCompatSecondary)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCompatCancel)

        tvFilename.text = media.title
        val tvName = if (connectedTvName.isNotEmpty()) connectedTvName else tvCapabilities.deviceModel
        val targetTv = if (tvName.isNotEmpty()) tvName else "TCL Android TV"
        tvWarning.text = "This video is not supported directly by your $targetTv."

        if (reasons.isNotEmpty()) {
            tvHdr.text = reasons.first()
        } else {
            tvHdr.text = "10-bit color / HDR detected (TV hardware decoder only supports 8-bit)"
        }
        tvChip.text = "Your phone can real-time hardware-encode this video using its Snapdragon processor for smooth, stutter-free playback on your TV."

        btnPrimary.text = "▶   Direct Play (Recommended)"
        btnSecondary.text = "▷   Encode & Stream"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnPrimary.setOnClickListener {
            dialog.dismiss()
            val directUrl = "http://$localIp:${ServerService.serverPort}/stream/${media.id}"
            sendStreamToTv(directUrl, media.title, media.id, media.durationMs)
        }

        btnSecondary.setOnClickListener {
            dialog.dismiss()
            startLiveStreamingAndTranscoding(media, localIp)
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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
                val transcodedUrl = "http://$localIp:${ServerService.serverPort}/stream/transcoded/${media.id}"
                sendStreamToTv(transcodedUrl, "[Converted] ${media.title}", media.id, duration)
                return
            }
        }

        // If seeking to a position, notify TV immediately so TV can pause and show preparing countdown
        if (startPositionMs > 0L) {
            sendTvControl("PREPARING_SEEK", startPositionMs)
        }

        val liveUrl = "http://$localIp:${ServerService.serverPort}/stream/live/${media.id}?t=${System.currentTimeMillis()}"

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
                    val transcodedUrl = "http://$localIp:${ServerService.serverPort}/stream/transcoded/${media.id}"
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

        // 15 seconds buffer period (counts down 15s -> 0s)
        preparingCountdownJob?.cancel()
        preparingCountdownJob = lifecycleScope.launch(Dispatchers.Main) {
            val totalSeconds = 15
            for (secRemaining in totalSeconds downTo 1) {
                tvCountdown.text = "Starting stream in ${secRemaining}s..."
                pbCountdown.progress = totalSeconds - secRemaining
                tvProgress.text = "Hardware Transcode: $currentProgressPercent%"
                delay(1000)
            }

            // 15 seconds buffer elapsed! Hardware encoder has buffered sufficient video!
            if (!isStreamDispatched && !isFinishing && !isDestroyed) {
                isStreamDispatched = true
                dialog.dismiss()
                val readyFile = if (startPositionMs == 0L) TranscoderManager.getTranscodedFile(this@MainActivity, media.id) else null
                val urlToSend = if (readyFile != null) {
                    "http://$localIp:${ServerService.serverPort}/stream/transcoded/${media.id}"
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
        val streamUrl = "http://$localIp:${ServerService.serverPort}${item.streamPath}"
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
                val bodyStr = json.toString()
                val body = bodyStr.toRequestBody("application/json".toMediaType())
                val request = buildAuthenticatedTvRequest(tvApiUrl, "POST", body, bodyStr)
                val response = httpClient.newCall(request).execute()

                val resCode = response.code
                val isSuccess = response.isSuccessful
                withContext(Dispatchers.Main) {
                    if (isSuccess) {
                        Toast.makeText(this@MainActivity, "Playing on TV!", Toast.LENGTH_SHORT).show()
                        activeStreamingMediaId = id
                        adapter.setActiveStreamingId(id)
                        showNowPlaying(title, durationMs)
                    } else if (resCode == 401) {
                        handleTvSessionExpired()
                    } else {
                        Toast.makeText(this@MainActivity, "TV replied: $resCode", Toast.LENGTH_SHORT).show()
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
            var currentTvIp = resolvedTvIp()
            var retry = 0
            while (currentTvIp.isEmpty() && retry < 15 && isActive) {
                kotlinx.coroutines.delay(200)
                currentTvIp = resolvedTvIp()
                retry++
            }
            if (currentTvIp.isEmpty()) return@launch

            val tvPort = connectedTvPort
            val statusUrl = "http://$currentTvIp:$tvPort/status"

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
                        val statusTitle = json.optString("title", "")
                        val streamUrl = json.optString("streamUrl", "")
                        val isPhoneConnected = json.optBoolean("isPhoneConnected", true)
                        val connectedPhoneIp = json.optString("connectedPhoneIp", "")
                        val localIp = NetworkUtils.getLocalWifiIpAddress()

                        val tvSessionId = json.optString("sessionId", "")
                        if (connectedTvSessionId.isNotEmpty() && tvSessionId.isNotEmpty() && tvSessionId != connectedTvSessionId) {
                            android.util.Log.w("MainActivity", "TV session ID changed ($connectedTvSessionId -> $tvSessionId), TV disconnected")
                            withContext(Dispatchers.Main) {
                                onTvExplicitlyDisconnected()
                            }
                            return@launch
                        }

                        if (connectedPhoneIp.isNotEmpty() && !isSameIp(connectedPhoneIp, localIp)) {
                            android.util.Log.w("MainActivity", "Another phone ($connectedPhoneIp) is connected to TV, disconnecting")
                            withContext(Dispatchers.Main) {
                                onTvExplicitlyDisconnected()
                            }
                            return@launch
                        }

                        withContext(Dispatchers.Main) {
                            tvIsPlaying = isPlaying
                            tvCurrentPosMs = pos
                            if (len > 0) {
                                tvTotalDurationMs = len
                            }

                            if (activeStreamingMediaTitle.isEmpty() && statusTitle.isNotEmpty()) {
                                activeStreamingMediaTitle = statusTitle
                                binding.tvNowPlayingTitle.text = statusTitle
                            }
                            if (activeStreamingMediaId == null) {
                                val targetTitle = if (statusTitle.isNotEmpty()) statusTitle else activeStreamingMediaTitle
                                val match = MediaRepository.getAllMediaItems().firstOrNull {
                                    it.title == targetTitle || (streamUrl.isNotEmpty() && streamUrl.contains(it.id))
                                }
                                if (match != null) {
                                    activeStreamingMediaId = match.id
                                    adapter.setActiveStreamingId(match.id)
                                }
                            }

                            if (!isPlaying && (pos == 0L || streamUrl.isEmpty() || statusTitle.isEmpty()) && activeStreamingMediaId != null) {
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
                    response.close()
                } catch (ignored: Exception) {}
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun buildAuthenticatedTvRequest(url: String, method: String, body: RequestBody?, bodyString: String = ""): Request {
        val builder = Request.Builder().url(url)
        val key = resolvedTvSessionKey()
        val sid = resolvedTvSessionId()
        val effectiveBodyString = if (bodyString.isEmpty() && method == "POST" && body == null) "{}" else bodyString
        if (key.isNotEmpty()) {
            val timestamp = System.currentTimeMillis() / 1000L
            val nonce = com.pocketstream.protocol.AuthProtocol.generateNonce()
            val path = try { java.net.URI(url).path } catch (_: Exception) { "" }
            val bodyHash = if (effectiveBodyString.isNotEmpty()) com.pocketstream.protocol.AuthProtocol.sha256Hex(effectiveBodyString) else ""
            val signature = com.pocketstream.protocol.AuthProtocol.computeSignature(
                key = key,
                method = method,
                path = path,
                timestamp = timestamp,
                nonce = nonce,
                bodyHash = bodyHash
            )
            if (sid.isNotEmpty()) {
                builder.addHeader(com.pocketstream.protocol.AuthProtocol.HEADER_SESSION, sid)
            }
            builder.addHeader(com.pocketstream.protocol.AuthProtocol.HEADER_TIMESTAMP, timestamp.toString())
            builder.addHeader(com.pocketstream.protocol.AuthProtocol.HEADER_NONCE, nonce)
            builder.addHeader(com.pocketstream.protocol.AuthProtocol.HEADER_SIGNATURE, signature)
        }
        if (body != null) {
            builder.method(method, body)
        } else if (method == "POST") {
            builder.post(effectiveBodyString.toRequestBody("application/json".toMediaType()))
        }
        return builder.build()
    }

    private fun handleTvSessionExpired() {
        connectedTvSessionKey = ""
        connectedTvSessionId = ""
        prefs.edit()
            .remove("last_tv_key")
            .remove("last_tv_sid")
            .apply()
        updateTvTargetUI(connectedTvIp, connectedTvPort, connectedTvName, isPaired = false)
        Toast.makeText(this@MainActivity, "TV session expired or key mismatch. Please scan TV QR to re-pair.", Toast.LENGTH_LONG).show()
    }

    private fun sendTvControl(action: String, positionMs: Long = 0L) {
        val tvIp = resolvedTvIp()
        if (tvIp.isEmpty()) return

        val tvPort = connectedTvPort
        val controlUrl = "http://$tvIp:$tvPort/control"

        // Optimistic UI update on phone
        if (action.equals("PAUSE", ignoreCase = true)) {
            tvIsPlaying = false
            binding.btnRemotePlayPause.text = "Play"
            binding.tvNowPlayingBadge.text = "PAUSED"
            binding.tvNowPlayingBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        } else if (action.equals("PLAY", ignoreCase = true) || action.equals("RESUME", ignoreCase = true)) {
            tvIsPlaying = true
            binding.btnRemotePlayPause.text = "Pause"
            binding.tvNowPlayingBadge.text = "TV PLAYING"
            binding.tvNowPlayingBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("action", action)
                    put("positionMs", positionMs)
                }
                val bodyStr = json.toString()
                val body = bodyStr.toRequestBody("application/json".toMediaType())
                val request = buildAuthenticatedTvRequest(controlUrl, "POST", body, bodyStr)
                val response = httpClient.newCall(request).execute()
                if (response.code == 401) {
                    withContext(Dispatchers.Main) {
                        handleTvSessionExpired()
                    }
                } else if (response.isSuccessful) {
                    kotlinx.coroutines.delay(200)
                    syncTvPlaybackStatus()
                }
                response.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleScannedQr(contents: String) {
        val tvInfo = TvInfo.fromQrString(contents)
        if (tvInfo != null) {
            connectedTvSessionId = tvInfo.sessionId
            connectedTvSessionKey = tvInfo.sessionKey
            connectedTvIp = tvInfo.ip
            connectedTvPort = tvInfo.port
            connectedTvName = tvInfo.name
            prefs.edit()
                .putString("last_tv_sid", tvInfo.sessionId)
                .putString("last_tv_key", tvInfo.sessionKey)
                .putString("last_tv_ip", tvInfo.ip)
                .putInt("last_tv_port", tvInfo.port)
                .putString("last_tv_name", tvInfo.name)
                .apply()
            sendPairHandshake(tvInfo.ip, tvInfo.port)
        } else {
            Toast.makeText(this, "Unrecognized QR code", Toast.LENGTH_LONG).show()
        }
    }

    private fun isSameIp(ip1: String?, ip2: String?): Boolean {
        if (ip1.isNullOrEmpty() || ip2.isNullOrEmpty()) return false
        val c1 = ip1.trim().removePrefix("/").removePrefix("::ffff:")
        val c2 = ip2.trim().removePrefix("/").removePrefix("::ffff:")
        return c1 == c2 || c1 == "127.0.0.1" || c2 == "127.0.0.1"
    }

    private fun onTvDiscovered(tvIp: String, tvPort: Int, tvName: String) {
        val hasKey = resolvedTvSessionKey().isNotEmpty()
        val savedIp = prefs.getString("last_tv_ip", null)
        val isAlreadyPairedWithThisTv = hasKey && !savedIp.isNullOrEmpty() && isSameIp(savedIp, tvIp)

        if (!isAlreadyPairedWithThisTv) {
            return
        }

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
        isTvPaired = isPaired && tvIp.isNotEmpty()
        if (isTvPaired) {
            binding.tvTvTargetName.text = tvName
            val formatNotice = if (tvCapabilities.supports4K) "4K" else "1080p Max"
            binding.tvTvTargetStatus.text = "Connected ($tvIp:$tvPort) • $formatNotice"
            binding.tvTvTargetStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnScanTvQr.isEnabled = false
            binding.btnScanTvQr.alpha = 0.5f
        } else {
            binding.tvTvTargetName.text = "Target TV"
            binding.tvTvTargetStatus.text = "Scan to pair TV"
            binding.tvTvTargetStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnScanTvQr.isEnabled = true
            binding.btnScanTvQr.alpha = 1.0f
        }
        updateServerUI()
    }

    private fun sendPairHandshake(tvIp: String, tvPort: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localIp = NetworkUtils.getLocalWifiIpAddress()
                val phoneName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "PocketStream Phone" }
                val key = resolvedTvSessionKey()
                val json = JSONObject().apply {
                    put("phoneIp", localIp)
                    put("phonePort", ServerService.serverPort)
                    put("deviceName", "$phoneName / PocketStream")
                    if (key.isNotEmpty()) {
                        put("sessionKey", key)
                    }
                }
                val bodyStr = json.toString()
                val body = bodyStr.toRequestBody("application/json".toMediaType())
                val req = buildAuthenticatedTvRequest("http://$tvIp:$tvPort/pair", "POST", body, bodyStr)
                val res = httpClient.newCall(req).execute()
                val resCode = res.code
                val isSuccess = res.isSuccessful
                val resBody = res.body?.string() ?: ""
                res.close()
                if (isSuccess && resBody.isNotEmpty()) {
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
                            Toast.makeText(this@MainActivity, "Paired with $connectedTvName!", Toast.LENGTH_SHORT).show()
                            if (!ServerService.isServerRunning) {
                                toggleServerWithSpinner(start = true)
                            } else {
                                notifyTvLibraryUpdated()
                            }
                        }
                    }
                } else if (resCode == 401) {
                    withContext(Dispatchers.Main) {
                        handleTvSessionExpired()
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
                        val currentlyPaired = resolvedTvSessionKey().isNotEmpty() && connectedTvIp == tvIp
                        updateTvTargetUI(tvIp, tvPort, connectedTvName, isPaired = currentlyPaired)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun notifyTvDisconnected() {
        val targetIp = resolvedTvIp()
        val targetPort = connectedTvPort
        if (targetIp.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val req = buildAuthenticatedTvRequest("http://$targetIp:$targetPort/disconnect", "POST", null)
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {}
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
                    val req = buildAuthenticatedTvRequest("http://$targetIp:$targetPort/refresh", "POST", null)
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setupDiscovery() {
        discoveryManager?.stop()
        discoveryManager = MobileDiscoveryManager(
            context = this,
            phonePortProvider = { ServerService.serverPort },
            onTvDiscovered = { tvIp, tvPort, tvName ->
                runOnUiThread {
                    onTvDiscovered(tvIp, tvPort, tvName)
                }
            },
            onTvDisconnected = {
                runOnUiThread {
                    onTvExplicitlyDisconnected()
                }
            }
        )
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
        startPeriodicTvPoll()
    }

    override fun onPause() {
        super.onPause()
        periodicTvPollJob?.cancel()
    }

    fun onTvDiscoveredFromHttp(
        tvIp: String,
        tvPort: Int = 8085,
        tvName: String = "PocketStream TV",
        sessionId: String = "",
        sessionKey: String = ""
    ) {
        if (tvIp.isEmpty()) return
        runOnUiThread {
            connectedTvIp = tvIp
            connectedTvPort = tvPort
            if (tvName.isNotEmpty()) connectedTvName = tvName
            if (sessionId.isNotEmpty()) connectedTvSessionId = sessionId
            if (sessionKey.isNotEmpty()) connectedTvSessionKey = sessionKey
            isTvPaired = true
            prefs.edit()
                .putString("last_tv_ip", connectedTvIp)
                .putInt("last_tv_port", connectedTvPort)
                .putString("last_tv_name", connectedTvName)
                .apply()
            if (connectedTvSessionKey.isNotEmpty()) {
                prefs.edit()
                    .putString("last_tv_sid", connectedTvSessionId)
                    .putString("last_tv_key", connectedTvSessionKey)
                    .apply()
            }
            updateTvTargetUI(connectedTvIp, connectedTvPort, connectedTvName, isPaired = true)
            updateServerUI()
        }
    }

    fun onTvPlaybackStarted(
        mediaId: String,
        title: String,
        durationMs: Long,
        tvIp: String = "",
        tvPort: Int = 8085,
        tvName: String = "",
        sessionId: String = "",
        sessionKey: String = ""
    ) {
        runOnUiThread {
            if (tvIp.isNotEmpty()) {
                connectedTvIp = tvIp
                connectedTvPort = tvPort
                if (tvName.isNotEmpty()) connectedTvName = tvName
                if (sessionId.isNotEmpty()) connectedTvSessionId = sessionId
                if (sessionKey.isNotEmpty()) connectedTvSessionKey = sessionKey
                isTvPaired = true
                prefs.edit()
                    .putString("last_tv_ip", connectedTvIp)
                    .putInt("last_tv_port", connectedTvPort)
                    .putString("last_tv_name", connectedTvName)
                    .apply()
                if (connectedTvSessionKey.isNotEmpty()) {
                    prefs.edit()
                        .putString("last_tv_sid", connectedTvSessionId)
                        .putString("last_tv_key", connectedTvSessionKey)
                        .apply()
                }
                updateTvTargetUI(connectedTvIp, connectedTvPort, connectedTvName, isPaired = true)
                updateServerUI()
            }
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

    fun onTvExplicitlyDisconnected() {
        runOnUiThread {
            connectedTvIp = ""
            connectedTvPort = 8085
            connectedTvName = ""
            connectedTvSessionId = ""
            connectedTvSessionKey = ""
            isTvPaired = false
            prefs.edit()
                .remove("last_tv_ip")
                .remove("last_tv_port")
                .remove("last_tv_name")
                .remove("last_tv_sid")
                .remove("last_tv_key")
                .remove("last_tv_caps")
                .apply()
            updateTvTargetUI("", 8085, "", isPaired = false)
            hideNowPlaying()
            if (ServerService.isServerRunning) {
                toggleServerWithSpinner(start = false)
            } else {
                updateServerUI()
            }
            Toast.makeText(this@MainActivity, "TV disconnected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        discoveryManager?.stop()
        statusSyncJob?.cancel()
        periodicTvPollJob?.cancel()
        preparingCountdownJob?.cancel()
        preparingDialog?.dismiss()
        super.onDestroy()
    }
}