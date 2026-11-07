# PocketStream Canonical Requirements Specification

## 1. Document Overview & Classification Standards
This document serves as the authoritative, verified specification of requirements for the PocketStream ecosystem (Mobile, TV, and iOS applications).

Every requirement in this document is strictly classified into one of four statuses based on verified source code evidence:
- **[CONFIRMED & IMPLEMENTED]**: Code exists, is functional, and has been verified directly in the codebase.
- **[APPROVED BUT NOT IMPLEMENTED]**: Officially approved in design or architecture decisions, but pending implementation.
- **[PROPOSED / UNAPPROVED]**: Under consideration or discussion; not yet approved for implementation.
- **[UNKNOWN / NEEDS CLARIFICATION]**: Ambiguous requirements where code and specifications diverge, or requirements require stakeholder decision.

---

## 2. System Overview & Core Capabilities

### 2.1 Decentralized Local Network Media Streaming
- **REQ-SYS-001 [CONFIRMED & IMPLEMENTED]**: The system operates entirely within the local Wi-Fi local area network (LAN) without requiring any cloud backend, external relay, internet connection, or user authentication accounts.
- **REQ-SYS-002 [CONFIRMED & IMPLEMENTED]**: The phone client (Android or iOS) embeds an HTTP 1.1 media server operating on port 8080 to serve local video files and metadata directly to TV receivers.
- **REQ-SYS-003 [CONFIRMED & IMPLEMENTED]**: The Android TV receiver embeds an HTTP 1.1 control server operating on port 8085 to receive playback instructions, status inquiries, and pairing handshakes.

---

## 3. Device Discovery & Pairing Requirements

### 3.1 Network Discovery
- **REQ-DISC-001 [CONFIRMED & IMPLEMENTED]**: The Android TV app advertises its presence via mDNS (Network Service Discovery / Bonjour) under service type `_pocketstream-tv._tcp.` on port 8085.
- **REQ-DISC-002 [CONFIRMED & IMPLEMENTED]**: The Mobile app advertises its presence via mDNS under service type `_pocketstream-phone._tcp.` on port 8080.
- **REQ-DISC-003 [CONFIRMED & IMPLEMENTED]**: UDP Broadcast discovery is implemented on port 8088. Mobile broadcasts `DISCOVER_TV` and `PHONE_ANNOUNCE`. TV listens and responds with `TV_ANNOUNCE` containing its IP, port, and device name.
- **REQ-DISC-004 [CONFIRMED & IMPLEMENTED]**: When a TV is currently paired and busy with an active client, it responds to UDP broadcast probes with `TV_BUSY`.
- **REQ-DISC-005 [CONFIRMED & IMPLEMENTED]**: When the mobile server shuts down, it broadcasts `PHONE_DISCONNECT` on UDP 8088 to immediately notify the TV receiver.

### 3.2 QR Code Pairing
- **REQ-PAIR-001 [CONFIRMED & IMPLEMENTED]**: The TV displays a dynamic QR code containing the URI format:
  `pocketstream://connect?ip=<tv_ip>&port=8085&name=<url_encoded_name>`
- **REQ-PAIR-002 [CONFIRMED & IMPLEMENTED]**: The Mobile app integrates a camera QR scanner (ZXing on Android, AVFoundation/CoreImage on iOS) that parses the QR payload and initiates a connection.
- **REQ-PAIR-003 [CONFIRMED & IMPLEMENTED]**: TV displays a "Disconnect Phone" button when paired, replacing the "Pair Phone (QR Code)" button.

### 3.3 Connection Exclusivity & Lifecycle
- **REQ-PAIR-004 [CONFIRMED & IMPLEMENTED]**: The TV enforces strict 1:1 connection exclusivity. While a connection is alive with Phone A, any `POST /pair` request from an IP address differing from Phone A is rejected with HTTP 409 Conflict (`{"status":"busy"}`).
- **REQ-PAIR-005 [CONFIRMED & IMPLEMENTED]**: TV receiver keeps the connection alive until explicitly disconnected by the user or until the phone server is stopped. Mid-stream spurious "Paired with phone" toast notifications are suppressed when the connection is already established.
- **REQ-PAIR-006 [CONFIRMED & IMPLEMENTED]**: Stopping the media server on the phone triggers an automatic `POST http://<tv_ip>:8085/disconnect` call, resetting the TV receiver state to disconnected.

---

## 4. Media Storage & Ingestion Requirements

### 4.1 Storage Access Framework (SAF) & Permissions
- **REQ-STOR-001 [CONFIRMED & IMPLEMENTED]**: On Android, media ingestion relies on Android Storage Access Framework (`OpenDocumentTree` and `OpenMultipleDocuments`). No broad external storage permissions (`READ_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`) are used.
- **REQ-STOR-002 [CONFIRMED & IMPLEMENTED]**: Persistent URI permissions (`FLAG_GRANT_READ_URI_PERMISSION`) are retained via `takePersistableUriPermission` across app launches.
- **REQ-STOR-003 [CONFIRMED & IMPLEMENTED]**: On iOS, media ingestion uses SwiftUI `fileImporter` with security-scoped URL bookmarked access.

### 4.2 Technical Analysis & Metadata Extraction
- **REQ-META-001 [CONFIRMED & IMPLEMENTED]**: 10-bit color / HDR detection is performed via rigorous technical media analysis (`MediaExtractor` track format, `MediaCodecInfo` profile levels for HEVC Main 10, AVC High 10, VP9 Profile 2/3, AV1 Main 10, and `MediaFormat.KEY_COLOR_TRANSFER` / `KEY_COLOR_STANDARD`).
- **REQ-META-002 [CONFIRMED & IMPLEMENTED]**: File name string matching is NOT used for determining 10-bit color or HDR status.
- **REQ-META-003 [CONFIRMED & IMPLEMENTED]**: Video thumbnails are extracted at exactly the 7% timeline position (falling back to 50% if duration is under 6 seconds) to prevent black intro frames.
- **REQ-META-004 [CONFIRMED & IMPLEMENTED]**: Thumbnail cache invalidation versioning (`thumb_percent_ver = 7`) purges legacy 10% thumbnails automatically.

---

## 5. Media Streaming & Playback Requirements

### 5.1 Direct Play Preference
- **REQ-PLAY-001 [CONFIRMED & IMPLEMENTED]**: Direct Play is preferred in all scenarios across the application.
- **REQ-PLAY-002 [CONFIRMED & IMPLEMENTED]**: When selecting a video from the TV UI grid, playback immediately triggers Direct Play (`http://<phone_ip>:8080/stream/<id>`), regardless of container or codec.
- **REQ-PLAY-003 [CONFIRMED & IMPLEMENTED]**: When a video is selected on the Mobile app and identified as potentially incompatible with the TV hardware decoder:
  - An informational compatibility dialog is displayed.
  - The primary action button is labeled `▶ Direct Play (Recommended)`.
  - The secondary action button is labeled `▷ Encode & Stream`.
  - The user can proceed with Direct Play immediately.

### 5.2 Hardware Transcoding & Buffering
- **REQ-XCODE-001 [CONFIRMED & IMPLEMENTED]**: When "Encode & Stream" is explicitly selected, the mobile app initiates hardware transcoding using Media3 Transformer (Android) or AVAssetExportSession / AVAssetReader/Writer (iOS).
- **REQ-XCODE-002 [CONFIRMED & IMPLEMENTED]**: Target transcoding format is H.264 High Profile Level 4.2, AAC stereo audio, packaged as fragmented MP4 (`InAppMuxer` with 1-second fragment intervals).
- **REQ-XCODE-003 [CONFIRMED & IMPLEMENTED]**: Hardware encoder uses `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC` to convert 10-bit HDR content to 8-bit BT.709 SDR.
- **REQ-XCODE-004 [CONFIRMED & IMPLEMENTED]**: During live transcoding, media is streamed via `GrowingFileInputStream` over chunked HTTP (`/stream/live/<id>`).
- **REQ-XCODE-005 [CONFIRMED & IMPLEMENTED]**: When live transcoding completes, the output file is remuxed to standard progressive MP4 with moov atom at the front, and served via `/stream/transcoded/<id>` supporting full HTTP 206 Partial Content range seeking.
- **REQ-XCODE-006 [CONFIRMED & IMPLEMENTED]**: A 30-second preparation countdown dialog is displayed on the mobile device while the initial chunks are encoded before playback begins.

### 5.3 Remote Control & Synchronization
- **REQ-CTRL-001 [CONFIRMED & IMPLEMENTED]**: The phone displays a bottom Now Playing card during active playback.
- **REQ-CTRL-002 [CONFIRMED & IMPLEMENTED]**: Mobile app polls `GET http://<tv_ip>:8085/status` every 1 second to update scrubber position, duration, and playing state.
- **REQ-CTRL-003 [CONFIRMED & IMPLEMENTED]**: Mobile app sends playback controls via `POST http://<tv_ip>:8085/control`: `PLAY`, `PAUSE`, `RW` (-10s), `FF` (+10s), `RESTART` (0s), `STOP`, `SEEK`, and `PREPARING_SEEK`.
- **REQ-CTRL-004 [CONFIRMED & IMPLEMENTED]**: TV UI provides full D-Pad navigation, on-screen display (OSD) with auto-hide timer (5 seconds), audio track selection, and subtitle toggle.

---

## 6. User Interface Requirements

### 6.1 Theme & Styling
- **REQ-UI-001 [CONFIRMED & IMPLEMENTED]**: Theme uses deep dark slate/black backgrounds (`#0B0F19` / `#121212`) with vibrant lime/green accents (`#D4FF00` / `#A3E635` / `#22C55E`) and dark outline pill buttons.
- **REQ-UI-002 [CONFIRMED & IMPLEMENTED]**: TV App displays a 4-column thumbnail grid (`GridLayoutManager(this, 4)`) adjusted to fit seamlessly without horizontal overflow.
- **REQ-UI-003 [CONFIRMED & IMPLEMENTED]**: "Glass UI" title banner and "Play Sample Video" button have been removed from the TV interface.
- **REQ-UI-004 [CONFIRMED & IMPLEMENTED]**: Phone status bar padding and header positioning are adjusted to avoid clipping under system cutouts/notches.

---

## 7. Requirement Status Summary Matrix

| ID | Category | Description | Status |
|---|---|---|---|
| REQ-SYS-001 | System | Offline LAN peer-to-peer operation | Confirmed & Implemented |
| REQ-SYS-002 | System | Mobile HTTP server on port 8080 | Confirmed & Implemented |
| REQ-SYS-003 | System | TV HTTP receiver on port 8085 | Confirmed & Implemented |
| REQ-DISC-001 | Discovery | TV mDNS `_pocketstream-tv._tcp.` | Confirmed & Implemented |
| REQ-DISC-002 | Discovery | Mobile mDNS `_pocketstream-phone._tcp.` | Confirmed & Implemented |
| REQ-DISC-003 | Discovery | UDP Broadcast on port 8088 | Confirmed & Implemented |
| REQ-PAIR-001 | Pairing | QR code pairing handshake | Confirmed & Implemented |
| REQ-PAIR-004 | Pairing | 1:1 Connection exclusivity (HTTP 409) | Confirmed & Implemented |
| REQ-PAIR-006 | Pairing | Server stop drops TV connection | Confirmed & Implemented |
| REQ-STOR-001 | Storage | SAF storage access (no broad storage perm) | Confirmed & Implemented |
| REQ-META-001 | Metadata | Technical 10-bit analysis without filename parsing | Confirmed & Implemented |
| REQ-META-003 | Metadata | 7% timeline position thumbnails | Confirmed & Implemented |
| REQ-PLAY-001 | Playback | Direct Play preferred in all scenarios | Confirmed & Implemented |
| REQ-XCODE-001 | Transcode | Media3 hardware fMP4 transcoding pipeline | Confirmed & Implemented |
| REQ-UI-002 | UI | 4-column TV grid | Confirmed & Implemented |
| REQ-SEC-001 | Security | Optical PSK + HMAC-SHA256 request signing & 1:1 bond | Confirmed & Implemented |
| REQ-SEC-002 | Security | Removal of wildcard CORS headers across all apps | Confirmed & Implemented |
| REQ-TEST-001 | Testing | Automated HMAC, nonce, and QR verification suite | Confirmed & Implemented |
| REQ-IOS-001 | iOS Parity | RFC 206 chunk-pumped HTTP streaming (256KB buffer) | Confirmed & Implemented |
| REQ-IOS-002 | iOS Parity | AVAsset audio track FourCC codec extraction | Confirmed & Implemented |
