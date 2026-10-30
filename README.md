# PocketStream

<p align="center">
  <b>High-Bitrate Direct Wi-Fi Media Streaming Between Android Phone & Android TV</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%20%7C%20Android%20TV%20%7C%20Fire%20TV-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/AndroidX-Media3%201.3.1-4285F4?style=flat-square&logo=google&logoColor=white" alt="Media3" />
  <img src="https://img.shields.io/badge/Release-v1.2.0-blue?style=flat-square" alt="Version" />
  <img src="https://img.shields.io/badge/License-GPLv3-green.svg?style=flat-square" alt="License: GPL v3" />
</p>

---

## Overview

**PocketStream** turns your Android phone into an ultra-fast, local Wi-Fi media server that streams high-bitrate video directly to your Android TV or Amazon Fire TV Stick. 

No cloud, no third-party media servers (Plex/Emby/Jellyfin), no transcoding servers, and zero internet bandwidth consumed. Everything communicates peer-to-peer over your local Wi-Fi or mobile hotspot.

---

## Download Pre-Built Release APKs

Grab the latest production **Release APKs** from the [GitHub Releases](https://github.com/CodeRevRepeat/PocketStream/releases) tab, or from the build output directory:

| App | Target Platform | Min OS | Build Artifact |
| :--- | :--- | :--- | :--- |
| **PocketStream TV** | Android TV, Google TV, Fire TV | Android 8.0+ (API 26+) / Fire OS 7+ | `apps/tv/build/outputs/apk/release/tv-release.apk` |
| **PocketStream Mobile** | Android Phones & Tablets | Android 8.0+ (Optimized for 14+) | `apps/mobile/build/outputs/apk/release/mobile-release.apk` |

---

## Installation & Sideloading

### 1. Android TV & Amazon Fire TV Stick
You can install `tv-release.apk` onto your TV or Fire TV Stick using either method below:

#### Method A: Direct ADB Sideload (Quickest)
1. Enable **Developer Options** and **ADB Debugging** on your TV or Fire TV:
   * **Fire TV:** *Settings → My Fire TV → About* (click device name 7 times) → *Developer Options → Turn ADB Debugging ON*.
   * **Android TV / Google TV:** *Settings → Device Preferences / System → About* (click Build Number 7 times) → *Developer Options → Turn USB/Network Debugging ON*.
2. Connect and install from your terminal:
   ```bash
   adb connect <TV_IP_ADDRESS>:5555
   adb install -r apps/tv/build/outputs/apk/release/tv-release.apk
   ```

#### Method B: Using the "Downloader" App (Fire TV & Android TV)
1. Install the free **Downloader** app from the Amazon Appstore or Google Play Store.
2. In TV settings, allow **Install Unknown Apps** for Downloader.
3. Enter the direct download link from the [GitHub Releases](https://github.com/CodeRevRepeat/PocketStream/releases) page into Downloader to install.

### 2. Android Mobile Phone
1. Transfer or download `mobile-release.apk` to your phone.
2. Open the file in your preferred file manager.
3. When prompted, allow *Install from unknown sources* and tap **Install**.

---

## Repository Structure

The project is organized into modular Gradle packages:

```
PocketStream/
├── apps/
│   ├── mobile/         # Android Phone Application (Media Server & Remote Controller)
│   │   ├── src/main/java/com/pocketstream/mobile/
│   │   │   ├── discovery/    # UDP Broadcast & Network Service Discovery (NSD)
│   │   │   ├── server/       # Embedded NanoHTTPD streaming server & Range request handler
│   │   │   ├── service/      # Android 14 foreground mediaPlayback service
│   │   │   ├── storage/      # Storage Access Framework (SAF) & Media probing
│   │   │   └── transcoder/   # AndroidX Media3 hardware-accelerated transcoding engine
│   │   └── src/main/res/     # Sleek dark-mode UI layouts & styles
│   │
│   └── tv/             # Android TV & Fire TV Application (Receiver & Media Player)
│       ├── src/main/java/com/pocketstream/tv/
│       │   ├── discovery/    # TV presence announcement & discovery listener
│       │   ├── player/       # ExoPlayer hardware-accelerated playback & dynamic fallback
│       │   ├── receiver/     # HTTP control receiver endpoints (/play, /pause, /seek, etc.)
│       │   └── util/         # TV hardware capabilities detector (MediaCodecList probe)
│       └── src/main/res/     # Frosted glassmorphism UI, D-Pad focus selectors & 16:9 banner
│
└── shared/
    └── protocol/       # Common Kotlin library shared between Phone and TV
        └── src/main/java/com/pocketstream/protocol/
            ├── DiscoveryMessage.kt  # UDP discovery packet models
            ├── MediaItem.kt         # Video metadata structures
            ├── PlaybackControl.kt   # Play, pause, seek, restart command contracts
            └── TvCapabilities.kt    # TV codec/resolution compatibility negotiation logic
```

---

## Key Features

### 1. Smart Capability Negotiation & Auto-Transcoding
* **Hardware Capability Probing:** The TV app automatically inspects its local `MediaCodecList` at startup, determining max resolution, max framerate, and 10-bit HEVC/HDR decoding support.
* **Zero-Prompt Auto Streaming:**
  * **Supported Formats (e.g. 8-bit H.264/AVC):** TV automatically begins instant direct hardware playback.
  * **Unsupported Formats (e.g. 10-bit HEVC on 8-bit-only TVs):** Phone automatically initiates real-time hardware encoding via AndroidX Media3 Transformer and delivers an instant progressive stream.

### 2. Glassmorphism TV Interface
* Designed for remote control navigation: 5-way D-Pad focus indicators, glowing neon accents, and smooth magnification scales.
* Live Player On-Screen Display (OSD) with scrub bar, restart, ±10s skip, and remote exit.
* Overscan safe margins for 100% compatibility across CRT/LED TV displays.

### 3. Bi-Directional State Synchronization
* When playback is initiated from either the TV or the Phone, both devices stay in sync.
* Remote scrubber slider and playback controls on the phone update in real-time as the TV plays.

### 4. Zero-Config Pairing
* **Instant QR Code Pairing:** Scan the TV's on-screen QR code using the mobile app to pair immediately.
* **Auto-Discovery:** Background UDP multicast and Android Network Service Discovery (NSD) automatically connects devices on the same Wi-Fi network.

### 5. Wide Device Compatibility
* **Android TV App:** Compatible with Android 8.0 through Android 14+ (API 26+), Google TV, and Amazon Fire TV Stick (Fire OS 7 / Fire OS 8).
* **Android Phone App:** Compatible with Android 8.0 through Android 14+ (API 34+), supporting Qualcomm Snapdragon, MediaTek Dimensity, Samsung Exynos, and Google Tensor processors.

---

## Building from Source

### Prerequisites
* Android Studio Jellyfish (2023.3.1) or newer
* JDK 17
* Android SDK (API 34)

### Clone & Build
```bash
git clone https://github.com/CodeRevRepeat/PocketStream.git
cd PocketStream

# Build Release APKs
./gradlew :apps:tv:assembleRelease
./gradlew :apps:mobile:assembleRelease

# Build Debug APKs (for development)
./gradlew :apps:tv:assembleDebug
./gradlew :apps:mobile:assembleDebug

# Build Production Release Bundles (for Google Play Store)
./gradlew :apps:tv:bundleRelease
./gradlew :apps:mobile:bundleRelease
```

Generated Release APK paths:
* **TV Release APK:** `apps/tv/build/outputs/apk/release/tv-release.apk`
* **Mobile Release APK:** `apps/mobile/build/outputs/apk/release/mobile-release.apk`

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**. See the [LICENSE](LICENSE) file for details.
