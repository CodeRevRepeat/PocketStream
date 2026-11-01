import SwiftUI
import UniformTypeIdentifiers

public struct ContentView: View {
    @StateObject private var server = PocketHttpServer.shared
    @StateObject private var mediaRepo = MediaRepository.shared
    @StateObject private var discovery = MobileDiscoveryManager.shared
    @StateObject private var transcoder = TranscoderManager.shared

    // Selection mode state
    @State private var isSelectionMode = false
    @State private var selectedIds: Set<String> = []

    // Dialog & overlay states
    @State private var showFilePicker = false
    @State private var showFolderPicker = false
    @State private var showQrScanner = false
    @State private var selectedMediaForStreaming: LocalMedia?
    @State private var incompatibleMedia: (LocalMedia, [String])?
    @State private var showActionOptionsForMedia: LocalMedia?

    // 30-second preparing countdown state
    @State private var isPreparing = false
    @State private var preparingTitle = "Preparing Video for TV"
    @State private var preparingCountdownSeconds = 30
    @State private var preparingTranscodePercent = 0
    @State private var preparingTimer: Timer?

    // Now Playing Remote Control state
    @State private var activeStreamTitle = ""
    @State private var activeStreamId: String?
    @State private var isNowPlayingVisible = false
    @State private var tvIsPlaying = false
    @State private var tvCurrentPosMs: Int64 = 0
    @State private var tvTotalDurationMs: Int64 = 0
    @State private var isUserDraggingScrubber = false
    @State private var scrubberProgress: Double = 0.0
    @State private var statusPollingTimer: Timer?

    // Toast alert state
    @State private var toastMessage: String?
    @State private var showServerStartingSpinner = false

    public var body: some View {
        ZStack {
            Color(hex: "#0B0F19")
                .ignoresSafeArea()

            VStack(spacing: 0) {
                // Top Header
                headerView
                    .padding(.horizontal, 16)
                    .padding(.top, 10)

                ScrollView {
                    VStack(spacing: 14) {
                        // TV Target Pairing Card
                        tvTargetCard

                        // Server Control Panel Card
                        serverControlCard

                        // Media Library Header & Tools
                        librarySectionHeader

                        // Media List
                        if mediaRepo.mediaList.isEmpty {
                            emptyLibraryNotice
                        } else {
                            mediaListView
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, isNowPlayingVisible ? 180 : 30)
                }

                Spacer(minLength: 0)
            }

            // Now Playing Floating Remote Control Bar
            if isNowPlayingVisible {
                VStack {
                    Spacer()
                    nowPlayingRemoteCard
                        .padding(.horizontal, 12)
                        .padding(.bottom, 12)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            // Preparing 30s Countdown Overlay
            if isPreparing {
                PreparingStreamView(
                    title: preparingTitle,
                    subtitle: "Rendering and buffering with hardware encoder to ensure smooth, stutter-free playback on your TV.",
                    secondsRemaining: preparingCountdownSeconds,
                    progressPercent: preparingTranscodePercent,
                    onCancel: cancelPreparingStream
                )
            }

            // Incompatible Format Dialog Overlay
            if let (media, reasons) = incompatibleMedia {
                IncompatibleFormatView(
                    mediaTitle: media.title,
                    tvName: discovery.connectedTv?.name ?? "Android TV",
                    reasons: reasons,
                    onEncodeAndStream: {
                        incompatibleMedia = nil
                        startLiveStreamingAndTranscoding(media: media, startPositionMs: 0)
                    },
                    onDirectPlay: {
                        incompatibleMedia = nil
                        let ip = NetworkUtils.getLocalWifiIpAddress()
                        let streamUrl = "http://\(ip):8080/stream/\(media.id)"
                        sendStreamToTv(streamUrl: streamUrl, title: media.title, id: media.id, durationMs: media.durationMs)
                    },
                    onCancel: {
                        incompatibleMedia = nil
                    }
                )
            }

            // Toast message popup
            if let toast = toastMessage {
                VStack {
                    Spacer()
                    Text(toast)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundColor(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(Color(hex: "#1E293B").opacity(0.95))
                        .cornerRadius(20)
                        .shadow(radius: 8)
                        .padding(.bottom, isNowPlayingVisible ? 190 : 40)
                }
                .transition(.opacity)
            }
        }
        .onAppear {
            setupServerCallbacks()
            discovery.startDiscovery()
        }
        .sheet(isPresented: $showQrScanner) {
            QRScannerView(
                onScanned: { qrContent in
                    showQrScanner = false
                    if let tv = TvInfo.from(qrString: qrContent) {
                        discovery.connectToTv(info: tv)
                        showToast("Paired with \(tv.name)!")
                    } else {
                        showToast("Unrecognized QR code")
                    }
                },
                onCancel: {
                    showQrScanner = false
                }
            )
        }
        .fileImporter(
            isPresented: $showFilePicker,
            allowedContentTypes: [UTType.movie, UTType.video],
            allowsMultipleSelection: true
        ) { result in
            if case let .success(urls) = result {
                for url in urls {
                    mediaRepo.addMedia(from: url)
                }
                showToast("Added \(urls.count) video(s)")
            }
        }
        .actionSheet(item: $showActionOptionsForMedia) { media in
            let ip = NetworkUtils.getLocalWifiIpAddress()
            let streamUrl = "http://\(ip):8080/stream/\(media.id)"
            return ActionSheet(
                title: Text(media.title),
                buttons: [
                    .default(Text("Direct Play on TV")) {
                        sendStreamToTv(streamUrl: streamUrl, title: media.title, id: media.id, durationMs: media.durationMs)
                    },
                    .default(Text("Live Encode & Stream (Hardware)")) {
                        startLiveStreamingAndTranscoding(media: media, startPositionMs: 0)
                    },
                    .default(Text("Copy Stream URL")) {
                        UIPasteboard.general.string = streamUrl
                        showToast("Copied stream URL")
                    },
                    .cancel()
                ]
            )
        }
    }

    // MARK: - Subviews

    private var headerView: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(Color(hex: "#38BDF8"))
                        .frame(width: 8, height: 8)
                    Text("PocketStream")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(Color(hex: "#F8FAFC"))
                }
                Text("Local Wi-Fi Streaming & Transcoder")
                    .font(.system(size: 12))
                    .foregroundColor(Color(hex: "#94A3B8"))
            }
            Spacer()
        }
    }

    private var tvTargetCard: some View {
        HStack {
            Image(systemName: "tv")
                .font(.system(size: 24))
                .foregroundColor(Color(hex: "#38BDF8"))
                .frame(width: 44, height: 44)
                .background(Color(hex: "#1E293B"))
                .cornerRadius(10)

            VStack(alignment: .leading, spacing: 3) {
                if let tv = discovery.connectedTv {
                    Text(tv.name)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(Color(hex: "#F8FAFC"))
                    Text("\(tv.ip):\(tv.port) • Connected")
                        .font(.system(size: 12))
                        .foregroundColor(Color(hex: "#38BDF8"))
                } else {
                    Text("No TV Connected")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(Color(hex: "#F8FAFC"))
                    Text("Auto-searching on local Wi-Fi...")
                        .font(.system(size: 12))
                        .foregroundColor(Color(hex: "#94A3B8"))
                }
            }

            Spacer()

            Button(action: { showQrScanner = true }) {
                HStack(spacing: 4) {
                    Image(systemName: "qrcode.viewfinder")
                    Text("Scan TV")
                }
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(Color(hex: "#38BDF8"))
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(Color(hex: "#1E293B"))
                .cornerRadius(8)
            }
        }
        .padding(14)
        .background(Color(hex: "#151D30"))
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color(hex: "#24324D"), lineWidth: 1)
        )
    }

    private var serverControlCard: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(server.isRunning ? Color(hex: "#22C55E") : Color(hex: "#64748B"))
                        .frame(width: 8, height: 8)
                    Text(server.isRunning ? "SERVER ONLINE" : "SERVER STOPPED")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(server.isRunning ? Color(hex: "#22C55E") : Color(hex: "#94A3B8"))
                }
                Text(server.isRunning ? server.serverUrl : "Start media server to stream")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(Color(hex: "#F8FAFC"))
            }

            Spacer()

            Button(action: toggleServer) {
                HStack(spacing: 6) {
                    if showServerStartingSpinner {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle(tint: .white))
                            .scaleEffect(0.8)
                    }
                    Text(server.isRunning ? "Stop" : "Start Server")
                        .font(.system(size: 13, weight: .semibold))
                }
                .foregroundColor(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(server.isRunning ? Color(hex: "#EF4444") : Color(hex: "#38BDF8"))
                .cornerRadius(8)
            }
        }
        .padding(14)
        .background(Color(hex: "#151D30"))
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color(hex: "#24324D"), lineWidth: 1)
        )
    }

    private var librarySectionHeader: some View {
        VStack(spacing: 8) {
            HStack {
                Text("Media Library (\(mediaRepo.mediaList.count))")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(Color(hex: "#F8FAFC"))

                Spacer()

                Button(action: { showFilePicker = true }) {
                    Label("Add Videos", systemImage: "plus")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(Color(hex: "#38BDF8"))
                }

                Button(action: { isSelectionMode.toggle(); selectedIds.removeAll() }) {
                    Text(isSelectionMode ? "Done" : "Select")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(Color(hex: "#94A3B8"))
                        .padding(.leading, 8)
                }
            }

            if isSelectionMode {
                HStack {
                    Button(action: selectAllOrNone) {
                        Text(selectedIds.count == mediaRepo.mediaList.count ? "Deselect All" : "Select All")
                            .font(.system(size: 12))
                            .foregroundColor(Color(hex: "#38BDF8"))
                    }

                    Spacer()

                    Button(action: removeSelected) {
                        Text("Remove (\(selectedIds.count))")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.red)
                    }
                    .disabled(selectedIds.isEmpty)
                }
                .padding(8)
                .background(Color(hex: "#1E293B"))
                .cornerRadius(8)
            }
        }
    }

    private var emptyLibraryNotice: some View {
        VStack(spacing: 12) {
            Image(systemName: "film.stack")
                .font(.system(size: 40))
                .foregroundColor(Color(hex: "#64748B"))
            Text("No videos added yet")
                .font(.system(size: 15, weight: .medium))
                .foregroundColor(Color(hex: "#94A3B8"))
            Text("Tap 'Add Videos' to add media from your iPhone storage.")
                .font(.system(size: 12))
                .foregroundColor(Color(hex: "#64748B"))
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }

    private var mediaListView: some View {
        LazyVStack(spacing: 8) {
            ForEach(mediaRepo.mediaList) { media in
                mediaRow(media: media)
            }
        }
    }

    private func mediaRow(media: LocalMedia) -> some View {
        HStack(spacing: 12) {
            if isSelectionMode {
                Image(systemName: selectedIds.contains(media.id) ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(selectedIds.contains(media.id) ? Color(hex: "#38BDF8") : Color(hex: "#64748B"))
                    .font(.system(size: 20))
                    .onTapGesture {
                        if selectedIds.contains(media.id) {
                            selectedIds.remove(media.id)
                        } else {
                            selectedIds.insert(media.id)
                        }
                    }
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(media.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(Color(hex: "#F8FAFC"))
                    .lineLimit(1)

                HStack(spacing: 6) {
                    badge(text: (media.width >= 1920 || media.height >= 1080) ? "1080p" : "720p", color: Color(hex: "#38BDF8"))
                    badge(text: media.formattedDuration, color: Color(hex: "#818CF8"))
                    badge(text: media.formattedSize, color: Color(hex: "#94A3B8"))
                    if media.is10Bit {
                        badge(text: "10-bit", color: Color(hex: "#F59E0B"))
                    }
                }
            }

            Spacer()

            if activeStreamId == media.id {
                Image(systemName: "waveform")
                    .foregroundColor(Color(hex: "#22C55E"))
            } else {
                Button(action: { handleMediaSelection(media: media) }) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 28))
                        .foregroundColor(Color(hex: "#38BDF8"))
                }
            }
        }
        .padding(12)
        .background(Color(hex: "#151D30"))
        .cornerRadius(10)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(activeStreamId == media.id ? Color(hex: "#38BDF8") : Color(hex: "#24324D"), lineWidth: 1)
        )
    }

    private func badge(text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .semibold))
            .foregroundColor(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15))
            .cornerRadius(4)
    }

    // MARK: - Now Playing Remote Control Card

    private var nowPlayingRemoteCard: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(tvIsPlaying ? Color(hex: "#22C55E") : Color(hex: "#94A3B8"))
                            .frame(width: 6, height: 6)
                        Text(tvIsPlaying ? "TV PLAYING" : "PAUSED")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(tvIsPlaying ? Color(hex: "#22C55E") : Color(hex: "#94A3B8"))
                    }
                    Text(activeStreamTitle)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.white)
                        .lineLimit(1)
                }

                Spacer()

                let curStr = formatTime(ms: tvCurrentPosMs)
                let lenStr = formatTime(ms: tvTotalDurationMs)
                Text("\(curStr) / \(lenStr)")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(Color(hex: "#94A3B8"))
            }

            // Interactive Timeline Scrubber
            Slider(
                value: Binding(
                    get: {
                        if isUserDraggingScrubber {
                            return scrubberProgress
                        } else if tvTotalDurationMs > 0 {
                            return Double(tvCurrentPosMs) / Double(tvTotalDurationMs)
                        } else {
                            return 0.0
                        }
                    },
                    set: { newVal in
                        scrubberProgress = newVal
                    }
                ),
                in: 0.0...1.0,
                onEditingChanged: { editing in
                    isUserDraggingScrubber = editing
                    if !editing && tvTotalDurationMs > 0 {
                        let seekTo = Int64(scrubberProgress * Double(tvTotalDurationMs))
                        handleScrubberSeek(seekTo: seekTo)
                    }
                }
            )
            .accentColor(Color(hex: "#38BDF8"))

            // Transport Control Buttons
            HStack(spacing: 24) {
                Button(action: { handleRestart() }) {
                    Image(systemName: "backward.end.fill")
                        .font(.system(size: 18))
                        .foregroundColor(.white)
                }

                Button(action: { sendTvControl(action: "RW") }) {
                    Image(systemName: "gobackward.10")
                        .font(.system(size: 20))
                        .foregroundColor(.white)
                }

                Button(action: { sendTvControl(action: tvIsPlaying ? "PAUSE" : "PLAY") }) {
                    Image(systemName: tvIsPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 38))
                        .foregroundColor(Color(hex: "#38BDF8"))
                }

                Button(action: { sendTvControl(action: "FF") }) {
                    Image(systemName: "goforward.10")
                        .font(.system(size: 20))
                        .foregroundColor(.white)
                }

                Button(action: { stopStreaming() }) {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 22))
                        .foregroundColor(Color(hex: "#EF4444"))
                }
            }
        }
        .padding(14)
        .background(Color(hex: "#151D30"))
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color(hex: "#38BDF8").opacity(0.4), lineWidth: 1)
        )
        .shadow(color: Color.black.opacity(0.4), radius: 10, y: 4)
    }

    // MARK: - Logic & Actions

    private func toggleServer() {
        if server.isRunning {
            server.stop()
            showToast("Server stopped")
        } else {
            showServerStartingSpinner = true
            server.start()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                showServerStartingSpinner = false
                showToast("Server started at \(server.serverUrl)")
            }
        }
    }

    private func setupServerCallbacks() {
        server.onPlaybackStarted = { id, title, duration in
            activeStreamId = id
            activeStreamTitle = title
            tvTotalDurationMs = duration
            isNowPlayingVisible = true
            startStatusPolling()
        }
        server.onPlaybackStopped = {
            isNowPlayingVisible = false
            activeStreamId = nil
            statusPollingTimer?.invalidate()
        }
        server.onSeekRequested = { media, positionMs in
            startLiveStreamingAndTranscoding(media: media, startPositionMs: positionMs)
        }
    }

    private func handleMediaSelection(media: LocalMedia) {
        if !server.isRunning {
            server.start()
        }

        let isAlreadyTranscoded = TranscoderManager.shared.getTranscodedFile(id: media.id) != nil
        if isAlreadyTranscoded {
            let ip = NetworkUtils.getLocalWifiIpAddress()
            let transcodedUrl = "http://\(ip):8080/stream/transcoded/\(media.id)"
            sendStreamToTv(streamUrl: transcodedUrl, title: "[Converted] \(media.title)", id: media.id, durationMs: media.durationMs)
            return
        }

        let compatibility = discovery.tvCapabilities.checkCompatibility(
            videoMime: media.videoCodec.isEmpty ? media.mimeType : media.videoCodec,
            width: media.width,
            height: media.height,
            is10Bit: media.is10Bit
        )

        if !compatibility.isCompatible {
            incompatibleMedia = (media, compatibility.unsupportedReasons)
        } else {
            showActionOptionsForMedia = media
        }
    }

    private func startLiveStreamingAndTranscoding(media: LocalMedia, startPositionMs: Int64) {
        let ip = NetworkUtils.getLocalWifiIpAddress()
        let liveUrl = "http://\(ip):8080/stream/live/\(media.id)?t=\(Int64(Date().timeIntervalSince1970 * 1000))"

        if startPositionMs > 0 {
            sendTvControl(action: "PREPARING_SEEK", positionMs: startPositionMs)
            preparingTitle = "Seeking to \(formatTime(ms: startPositionMs))..."
        } else {
            preparingTitle = "Preparing Video for TV"
        }

        isPreparing = true
        preparingCountdownSeconds = 30
        preparingTranscodePercent = 0

        TranscoderManager.shared.startLiveTranscode(
            media: media,
            startPositionMs: startPositionMs,
            onReadyToStream: { _ in },
            onProgress: { percent in
                preparingTranscodePercent = percent
            },
            onCompleted: { completedUrl in
                if startPositionMs == 0 && activeStreamId == media.id {
                    let progressiveUrl = "http://\(ip):8080/stream/transcoded/\(media.id)"
                    sendStreamToTv(streamUrl: progressiveUrl, title: "[Converted] \(media.title)", id: media.id, durationMs: media.durationMs, startPositionMs: tvCurrentPosMs)
                }
            },
            onError: { err in
                isPreparing = false
                showToast("Transcoder error: \(err)")
            }
        )

        preparingTimer?.invalidate()
        preparingTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { timer in
            if preparingCountdownSeconds > 1 {
                preparingCountdownSeconds -= 1
            } else {
                timer.invalidate()
                isPreparing = false
                let streamTitle = startPositionMs > 0 ? "[Live 8-bit] \(media.title)" : "[Live 8-bit] \(media.title)"
                sendStreamToTv(streamUrl: liveUrl, title: streamTitle, id: media.id, durationMs: media.durationMs, startPositionMs: startPositionMs)
            }
        }
    }

    private func cancelPreparingStream() {
        preparingTimer?.invalidate()
        preparingTimer = nil
        isPreparing = false
        TranscoderManager.shared.cancelActive()
        showToast("Streaming cancelled")
    }

    private func handleScrubberSeek(seekTo: Int64) {
        guard let currentId = activeStreamId, let media = mediaRepo.getMedia(by: currentId) else {
            sendTvControl(action: "SEEK", positionMs: seekTo)
            return
        }

        let isLiveTranscoded = (TranscoderManager.shared.isTranscoding(id: media.id) || media.is10Bit) &&
                               TranscoderManager.shared.getTranscodedFile(id: media.id) == nil

        if isLiveTranscoded {
            startLiveStreamingAndTranscoding(media: media, startPositionMs: seekTo)
        } else {
            sendTvControl(action: "SEEK", positionMs: seekTo)
        }
    }

    private func handleRestart() {
        guard let currentId = activeStreamId, let media = mediaRepo.getMedia(by: currentId) else {
            sendTvControl(action: "RESTART")
            return
        }
        let isLiveTranscoded = (TranscoderManager.shared.isTranscoding(id: media.id) || media.is10Bit) &&
                               TranscoderManager.shared.getTranscodedFile(id: media.id) == nil
        if isLiveTranscoded {
            startLiveStreamingAndTranscoding(media: media, startPositionMs: 0)
        } else {
            sendTvControl(action: "RESTART")
        }
    }

    private func sendStreamToTv(streamUrl: String, title: String, id: String, durationMs: Int64, startPositionMs: Int64 = 0) {
        guard let tv = discovery.connectedTv else {
            showToast("Please pair with your TV first")
            return
        }

        guard let url = URL(string: "http://\(tv.ip):\(tv.port)/play") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let dict: [String: Any] = [
            "streamUrl": streamUrl,
            "title": title,
            "id": id,
            "durationMs": durationMs,
            "startPositionMs": startPositionMs
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: dict, options: [])

        URLSession.shared.dataTask(with: request) { _, response, error in
            DispatchQueue.main.async {
                if let httpRes = response as? HTTPURLResponse, httpRes.statusCode == 200 {
                    activeStreamId = id
                    activeStreamTitle = title
                    tvTotalDurationMs = durationMs
                    isNowPlayingVisible = true
                    startStatusPolling()
                    showToast("Playing on TV!")
                } else {
                    showToast("TV replied: \((response as? HTTPURLResponse)?.statusCode ?? 500)")
                }
            }
        }.resume()
    }

    private func sendTvControl(action: String, positionMs: Int64 = 0) {
        guard let tv = discovery.connectedTv else { return }
        guard let url = URL(string: "http://\(tv.ip):\(tv.port)/control") else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let dict: [String: Any] = [
            "action": action,
            "positionMs": positionMs
        ]
        request.httpBody = try? JSONSerialization.data(withJSONObject: dict, options: [])
        URLSession.shared.dataTask(with: request).resume()
    }

    private func stopStreaming() {
        sendTvControl(action: "STOP")
        isNowPlayingVisible = false
        activeStreamId = nil
        statusPollingTimer?.invalidate()
        showToast("Stopped TV playback")
    }

    private func startStatusPolling() {
        statusPollingTimer?.invalidate()
        statusPollingTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            guard let tv = discovery.connectedTv else { return }
            guard let url = URL(string: "http://\(tv.ip):\(tv.port)/status") else { return }

            URLSession.shared.dataTask(with: url) { data, response, _ in
                guard let data = data,
                      let json = (try? JSONSerialization.jsonObject(with: data, options: [])) as? [String: Any] else {
                    return
                }
                let isPlaying = json["isPlaying"] as? Bool ?? false
                let pos = (json["positionMs"] as? NSNumber)?.int64Value ?? 0
                let len = (json["durationMs"] as? NSNumber)?.int64Value ?? 0

                DispatchQueue.main.async {
                    tvIsPlaying = isPlaying
                    tvCurrentPosMs = pos
                    if len > 0 {
                        tvTotalDurationMs = len
                    }
                }
            }.resume()
        }
    }

    private func selectAllOrNone() {
        if selectedIds.count == mediaRepo.mediaList.count {
            selectedIds.removeAll()
        } else {
            selectedIds = Set(mediaRepo.mediaList.map { $0.id })
        }
    }

    private func removeSelected() {
        mediaRepo.removeMedia(ids: selectedIds)
        selectedIds.removeAll()
        isSelectionMode = false
        showToast("Removed from library")
    }

    private func showToast(_ msg: String) {
        withAnimation {
            toastMessage = msg
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            withAnimation {
                if toastMessage == msg {
                    toastMessage = nil
                }
            }
        }
    }

    private func formatTime(ms: Int64) -> String {
        let totalSeconds = max(0, ms / 1000)
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60
        if hours > 0 {
            return String(format: "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%02d:%02d", minutes, seconds)
        }
    }
}

// Color Hex Helper Extension
extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (1, 1, 1, 0)
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}
