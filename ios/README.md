# PocketStream iOS Client

Native iOS client for PocketStream built with Swift and SwiftUI, matching 100% of the functionality of the Android mobile app.

---

## 1. Project Overview

- **Source Code:** `ios/PocketStream/`
- **Xcode Project:** `ios/PocketStream.xcodeproj`
- **Sideloading Package (.ipa):** `release/PocketStream.ipa` (and `ios/PocketStream.ipa`)
- **Compatibility:** iOS 15.0+ (iPhone and iPad)

---

## 2. Features Implemented (1:1 with Android Mobile)

1. **Embedded HTTP Media Server (`PocketHttpServer.swift`):**
   - Serves local video on port `8080`.
   - Supports `GET /api/v1/status`, `GET /api/v1/library`, `GET /api/v1/transcode/status`.
   - Handles `POST /api/v1/play`, `POST /api/v1/stop`, `POST /api/v1/transcode/seek`.
   - Full HTTP 206 Partial Content `Range` header streaming for direct playback.
   - Chunked live fragmented streaming for real-time hardware transcoding.

2. **Automatic TV Discovery & Pairing (`MobileDiscoveryManager.swift`):**
   - **Bonjour / mDNS**: Discovers `_pocketstream-tv._tcp.` and publishes `_pocketstream-phone._tcp.`.
   - **UDP Broadcast**: Broadcasts `DISCOVER_TV` on port `8088` and listens for `TV_ANNOUNCE`.
   - **QR Code Pairing**: Built-in AVFoundation camera scanner parses `pocketstream://connect?...`.
   - **Capabilities Exchange**: Evaluates TV hardware profile (`1080p`, `4K`, `10-bit HEVC`, `HDR`).

3. **Hardware Transcoder Engine (`TranscoderManager.swift`):**
   - Hardware-accelerated H.264 High Profile Level 4.2 / AAC video transcoding via AVFoundation.
   - **30-Second Buffer Countdown**: Displays modal preparing dialog with countdown ("Starting stream in 30s..."), progress bar, transcode percent, and cancel button.
   - **Live Transcode Seeking**: Dynamically restarts hardware transcoding clipped from the seek position with synchronized TV buffer countdown.

4. **Synchronized Remote Control (`ContentView.swift`):**
   - Bottom Now Playing bar when TV is streaming.
   - Interactive timeline scrubber with live preview.
   - Transport controls: Restart, Rewind 10s, Play/Pause, Fast Forward 10s, Stop.
   - Real-time TV playback status polling (`GET http://<tvIp>:8085/status`).

5. **Media Library & Multi-Select (`MediaRepository.swift`):**
   - Security-scoped document and folder picker with persistent bookmark storage.
   - Metadata probing (resolution, duration, codecs, 10-bit/HDR detection).
   - Multi-select batch removal.

---

## 3. How to Sideload `PocketStream.ipa`

### Method A: Sideloadly (Windows & macOS) — Recommended
1. Download and open [Sideloadly](https://sideloadly.io/).
2. Connect your iPhone via USB or local Wi-Fi.
3. Drag and drop `PocketStream.ipa` into Sideloadly.
4. Enter your Apple ID and click **Start**.
5. On your iPhone: Go to **Settings > General > VPN & Device Management**, tap your Apple ID, and tap **Trust**.
6. Open **PocketStream** on your iPhone!

### Method B: AltStore / SideStore (iOS On-Device)
1. Share `PocketStream.ipa` to your iPhone (via AirDrop, iCloud Drive, or local storage).
2. Open AltStore or SideStore, tap the **+** (Add) button in **My Apps**, and select `PocketStream.ipa`.

### Method C: TrollStore (iOS 14.0 - 16.6.1)
1. Transfer `PocketStream.ipa` to device and tap **Install with TrollStore** for permanent unsigned sideloading.

### Method D: Xcode (macOS)
1. Open `ios/PocketStream.xcodeproj` in Xcode.
2. Select your connected iPhone as target device.
3. Press `Cmd + R` (Run) to build and install directly.
