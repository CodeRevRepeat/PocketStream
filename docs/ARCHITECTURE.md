# PocketStream System Architecture

## 1. Executive Summary & Top-Level Architecture
PocketStream is a peer-to-peer, local-network media streaming and remote control system designed for high-bitrate video transmission from mobile devices (Android and iOS) to smart TV receivers (Android TV / Google TV).

The architecture eliminates all cloud dependencies, accounts, and external servers. All discovery, pairing, metadata ingestion, video transcoding, and streaming occur directly over local Wi-Fi.

```
       +---------------------------------------------------------+
       |                     Local Wi-Fi (LAN)                   |
       +---------------------------------------------------------+
                     |                                 |
         mDNS (5353) / UDP (8088)           mDNS (5353) / UDP (8088)
         QR Scheme Pairing                  QR Display
                     v                                 v
        +-------------------------+       +-------------------------+
        |   Mobile Client (8080)  |       |   TV Receiver (8085)    |
        |  (Android / iOS)        |       |      (Android TV)       |
        |                         |       |                         |
        | +---------------------+ |       | +---------------------+ |
        | | PocketHttpServer    | |<======| | ExoPlayer Engine    | |
        | | (NanoHTTPD / NW)    | | HTTP  | | (Media3 1.3.1)      | |
        | +---------------------+ | 8080  | +---------------------+ |
        |                         |       |                         |
        | +---------------------+ |       | +---------------------+ |
        | | TranscoderManager   | |       | | TvReceiverServer    | |
        | | (Media3 Transformer)| |======>| | (NanoHTTPD 8085)    | |
        | +---------------------+ | HTTP  | +---------------------+ |
        |                         | 8085  |                         |
        | +---------------------+ |       | +---------------------+ |
        | | MediaRepository     | |       | | TvMediaAdapter Grid | |
        | | (SAF & Exclude Set) | |       | | (4-Column Layout)   | |
        | +---------------------+ |       | +---------------------+ |
        +-------------------------+       +-------------------------+
```

---

## 2. Repository & Workspace Topology

The repository contains three client applications and a shared protocol library:

```
PocketStream/
├── apps/
│   ├── mobile/                  # Android Mobile Application (Kotlin)
│   │   ├── build.gradle         # Namespace: com.pocketstream.mobile
│   │   └── src/main/...
│   └── tv/                      # Android TV Application (Kotlin)
│       ├── build.gradle         # Namespace: com.pocketstream.tv
│       └── src/main/...
├── ios/
│   ├── PocketStream/            # iOS Application (Swift 5.9, SwiftUI)
│   │   ├── App/                 # App lifecycle (PocketStreamApp.swift)
│   │   ├── Server/              # Embedded HTTP server (Network.framework)
│   │   ├── Storage/             # Media bookmarking & metadata
│   │   ├── Transcoder/          # AVFoundation transcode pipeline
│   │   └── Views/               # SwiftUI interfaces
│   └── PocketStream.xcodeproj   # Xcode project definition
├── shared/
│   └── protocol/                # Shared JVM/Kotlin data contracts
│       ├── build.gradle         # Namespace: com.pocketstream.protocol
│       └── src/main/...         # MediaItem, TvCapabilities, etc.
└── docs/                        # Canonical system documentation
```

---

## 3. Subsystem Breakdown

### 3.1 Android Mobile Application (`apps/mobile`)
- **Package**: `com.pocketstream.mobile`
- **Minimum SDK**: 26 (Android 8.0 Oreo); **Target/Compile SDK**: 34 (Android 14)
- **Primary Modules**:
  - `ServerService`: Foreground service managing `PocketHttpServer`, displaying persistent notification with playback controls, and acquiring Wi-Fi and wake locks.
  - `PocketHttpServer`: Custom embedded HTTP server based on NanoHTTPD 2.3.1. Handles byte-range streaming, live chunk streaming via `GrowingFileInputStream`, and REST endpoints.
  - `MediaRepository`: Manages user-selected folder trees and files via Android Storage Access Framework (`DocumentFile`, `ContentResolver`). Probes 10-bit status using `MediaExtractor` and generates thumbnails at 7% timeline position.
  - `TranscoderManager`: Wraps AndroidX Media3 Transformer to convert high-profile/HDR video into fragmented MP4 chunks with H.264 High Profile and AAC stereo.
  - `MobileDiscoveryManager`: Handles mDNS registration/discovery and UDP broadcast announcements on port 8088.

### 3.2 Android TV Application (`apps/tv`)
- **Package**: `com.pocketstream.tv`
- **Minimum SDK**: 26; **Target/Compile SDK**: 34
- **Primary Modules**:
  - `TvReceiverServer`: Embedded NanoHTTPD server on port 8085 handling `/pair`, `/capabilities`, `/disconnect`, `/refresh`, `/play`, `/control`, and `/status`. Enforces single-phone connection exclusivity.
  - `ExoPlayerManager`: Wraps Media3 ExoPlayer for video rendering, supporting H.264, HEVC, VP9, AV1, audio track switching, subtitle rendering, and D-Pad timeline scrubbing.
  - `TvMediaAdapter`: 4-column `GridLayoutManager` recycler view adapter with on-demand image loading via OkHttp and an in-memory LruCache.
  - `TvCapabilityDetector`: Queries Android `MediaCodecList` to report display dimensions, frame rates, and supported video MIME types to mobile clients.

### 3.3 iOS Client (`ios/PocketStream`)
- **Language**: Swift 5.9; **Deployment Target**: iOS 16.0+
- **Architecture**: SwiftUI MVVM with concurrency (`async/await`)
- **Primary Modules**:
  - `PocketHttpServer.swift`: Custom asynchronous HTTP 1.1 server implemented directly on Apple's `Network.framework` (`NWListener` and `NWConnection`).
  - `MediaRepository.swift`: Ingests video items via SwiftUI `fileImporter`, persisting access using security-scoped URL bookmarks. Extracts metadata and thumbnails via `AVAsset` / `AVAssetImageGenerator`.
  - `TranscoderManager.swift`: Handles on-device transcoding via `AVAssetExportSession` / `AVAssetReader` & `AVAssetWriter`.
  - `MobileDiscoveryManager.swift`: Performs mDNS service registration (`NWListener`) and UDP discovery.

### 3.4 Shared Protocol Module (`shared/protocol`)
- **Package**: `com.pocketstream.protocol`
- **Artifact**: Java/Kotlin library consumed by both `apps/mobile` and `apps/tv`.
- **Contracts**:
  - `MediaItem`: Represents media metadata (id, title, size, MIME type, duration, width, height, videoCodec, audioCodec, streamPath, thumbnailPath).
  - `TvCapabilities`: Represents TV hardware decoder limits (maxWidth, maxHeight, maxFps, supports10BitHevc, supportsHdr, supports4K, supportedVideoMimes, hardwareDecoders).
  - `PlaybackControl`: Defines control payloads (`PLAY`, `PAUSE`, `RW`, `FF`, `RESTART`, `STOP`, `SEEK`, `PREPARING_SEEK`).
  - `ServerStatus`: Operational health and IP address metadata.
  - `DiscoveryMessage`: JSON formats for UDP broadcasts (`DISCOVER_TV`, `TV_ANNOUNCE`, `PHONE_ANNOUNCE`, `TV_BUSY`, `PHONE_DISCONNECT`).

---

## 4. End-to-End User Journey Flows

### 4.1 Discovery and Exclusive Pairing Flow (Option C Optical PSK)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Phone as Mobile App (:8080)
    participant TV as Android TV (:8085)

    User->>TV: Launch PocketStream TV
    TV->>TV: Initialize TvReceiverServer on 8085
    TV->>TV: Generate ephemeral 256-bit sessionKey & sessionId
    TV->>TV: Render QR code (pocketstream://connect?...&sid=...&key=...)
    User->>Phone: Tap "Scan TV QR"
    Phone->>Phone: Camera scan (extracts sid and sessionKey)
    Phone->>Phone: Compute HMAC-SHA256 signature for /pair
    Phone->>TV: POST http://<tv_ip>:8085/pair (Headers: X-PS-Session, X-PS-Timestamp, X-PS-Nonce, X-PS-Signature)
    alt TV is already connected to another IP or invalid signature
        TV-->>Phone: HTTP 409 Conflict {"status": "busy"} or HTTP 401 Unauthorized
        Phone->>User: Toast "TV is busy or signature invalid"
    else TV matches session key and unbonded/same IP
        TV->>TV: Verify HMAC, record nonce, bind connectedPhoneIp & sessionKey
        TV-->>Phone: HTTP 200 OK {status: "paired", capabilities: {...}}
        Phone->>Phone: Store tvCapabilities and authenticated session state
        Phone->>TV: POST http://<tv_ip>:8085/refresh (Signed with HMAC)
        TV->>Phone: GET http://<phone_ip>:8080/api/v1/library
        Phone-->>TV: Return [MediaItem] list
        TV->>TV: Render 4-Column Media Grid
    end
```

---

### 4.2 Direct Playback Initiation Flow (Preferred in All Cases)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Phone as Mobile App (:8080)
    participant TV as Android TV (:8085)

    alt Selection from TV Grid
        User->>TV: Select video via D-Pad
        TV->>TV: Start ExoPlayer with direct URL: http://<phone>:8080/stream/<id>
        TV->>Phone: POST http://<phone>:8080/api/v1/play {mediaId}
        Phone->>Phone: Show Now Playing remote card
    else Selection from Mobile App
        User->>Phone: Tap "Stream to TV"
        alt Codec Supported natively
            Phone->>TV: POST http://<tv>:8085/play {streamUrl, title, durationMs}
            TV->>TV: Launch playback
        else Incompatible format detected (e.g. 10-bit HDR)
            Phone->>User: Show Compatibility Dialog
            User->>Phone: Tap "▶ Direct Play (Recommended)"
            Phone->>TV: POST http://<tv>:8085/play {streamUrl, title, durationMs}
            TV->>TV: Launch playback with direct stream
        end
    end
```

---

### 4.3 Hardware Transcoding Flow (Fallback when Requested)

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Phone as Mobile App (:8080)
    participant Transcoder as Media3 Transformer
    participant TV as Android TV (:8085)

    User->>Phone: In Compatibility Dialog, tap "▷ Encode & Stream"
    Phone->>Transcoder: Start live transcode (output: transcoded_<id>.mp4)
    Phone->>User: Display 30s Buffer Countdown Dialog
    Transcoder->>Transcoder: Hardware tone-map HDR to SDR & write fMP4 chunks
    Note over Transcoder: Accumulate >= 4 MB initial buffer
    Phone->>TV: POST http://<tv>:8085/play {streamUrl: "/stream/live/<id>"}
    TV->>Phone: GET http://<phone>:8080/stream/live/<id>
    Phone->>TV: Serve live chunks via GrowingFileInputStream
    TV->>TV: Render smooth 60 FPS H.264 stream
    opt Transcode Finishes
        Transcoder->>Phone: onCompleted
        Phone->>Phone: Remux fMP4 to progressive MP4 and touch .done marker
        Note over Phone: Subsequent plays use fast /stream/transcoded/<id>
    end
```

---

## 5. Transcoding & Streaming Engine Architecture

### 5.1 Fragmented MP4 vs Progressive MP4
To achieve zero-lag live playback while the hardware encoder is actively transcoding:
1. **Live Phase (fMP4)**: Media3 `InAppMuxer` is initialized with `.setFragmentedMp4Enabled(true)`. The `ftyp` and `moov` header boxes are written immediately at file creation, and subsequent video samples are packaged into 1-second `moof` + `mdat` fragments.
2. **Growing File Streaming**: `GrowingFileInputStream` monitors file length and holds the read thread with 25ms polling intervals when waiting for the hardware encoder to output fresh fragments.
3. **Completion & Progressive Conversion**: Upon completion, the file is remuxed into a standard progressive MP4 with a single contiguous `moov` atom at the start of the file. This allows subsequent sessions to enjoy full HTTP 206 Partial Content byte-range seeking.

### 5.2 10-bit Technical Analysis Architecture
Rather than brittle file extension or filename string searching, the architecture analyzes the track container:
- Probes `MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER` (6 = ST2084 / HDR10, 7 = HLG).
- Probes `MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD` (6 = BT.2020).
- Probes `MediaExtractor` track format for `KEY_PROFILE` (HEVC Profile Main 10, AVC High 10, VP9 Profile 2/3, AV1 Main 10).
- Probes track integer attributes `bits-per-sample` and `bit-depth`.
