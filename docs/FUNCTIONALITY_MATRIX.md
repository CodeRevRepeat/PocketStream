# PocketStream Cross-Platform Functionality Matrix

## 1. Scope & Legend
This matrix tracks the functional parity and verified implementation state across all three client applications in the PocketStream ecosystem:
- **Android Phone** (`apps/mobile`)
- **Android TV** (`apps/tv`)
- **iOS Client** (`ios/PocketStream`)

### Status Legend
- **YES**: Fully implemented, verified in codebase.
- **PARTIAL**: Partially implemented; key capabilities or corner cases missing.
- **NO**: Not implemented in this target.
- **N/A**: Not applicable to this platform's role.

---

## 2. Core Functional Matrix

| Functional Area | Feature / Capability | Android Phone (`apps/mobile`) | Android TV (`apps/tv`) | iOS Client (`ios/PocketStream`) | Notes & Discrepancies |
|---|---|---|---|---|---|
| **Networking & HTTP** | Embedded HTTP Server (Port 8080) | **YES** (NanoHTTPD 2.3.1) | N/A | **YES** (Network.framework) | Android uses NanoHTTPD; iOS uses native NWListener. |
| | HTTP Receiver Server (Port 8085) | N/A | **YES** (NanoHTTPD 2.3.1) | N/A | TV handles playback and pairing requests. |
| | Byte-Range Seeking (HTTP 206) | **YES** | N/A | **YES** | iOS overhauled with 256KB chunk-pumped pipeline, full RFC 206 & 200 support. |
| | Live fMP4 Chunked Stream (`/stream/live`) | **YES** (`GrowingFileInputStream`) | N/A | **PARTIAL** | iOS serves from active file; Android uses GrowingFileInputStream. |
| | Static Transcoded Stream (`/stream/transcoded`) | **YES** | N/A | **YES** | Served when transcode is complete. |
| | Dynamic Thumbnail Endpoint (`/api/v1/thumbnail`) | **YES** | N/A | **YES** | Android generates at 7% mark; iOS generates via AVAssetImageGenerator. |
| | Mutual Authentication & HMAC Request Signing | **YES** (HMAC-SHA256) | **YES** (HMAC-SHA256) | **YES** (CryptoKit HMAC) | Option C: Optical PSK via QR, 30s sliding nonce cache, 5-attempt rate lockout. |
| | CORS Policy | **REMOVED** (0 headers) | **REMOVED** (0 headers) | **REMOVED** (0 headers) | P0 fix: Wildcard CORS eliminated across all 3 platforms; OPTIONS returns 405. |
| **Discovery & Pairing** | mDNS Bonjour Registration | **YES** (`_pocketstream-phone._tcp.`) | **YES** (`_pocketstream-tv._tcp.`) | **YES** (`_pocketstream-phone._tcp.`) | Verified in discovery managers. |
| | UDP Broadcast Discovery (Port 8088) | **YES** (`DISCOVER_TV`) | **YES** (`TV_ANNOUNCE`, `TV_BUSY`) | **YES** (`DISCOVER_TV`) | Broadcasts JSON packets across LAN. |
| | QR Code Generation | N/A | **YES** (ZXing Core) | N/A | Displays `pocketstream://connect?...&sid=...&key=...`. |
| | QR Code Scanner | **YES** (ZXing Scanner) | N/A | **YES** (AVFoundation camera) | Extracts session ID and optical session key. |
| | 1:1 Connection Exclusivity (HTTP 409) | N/A | **YES** | N/A | TV rejects other phones when connected via IP and HMAC session key check. |
| | Graceful Disconnect on Server Stop | **YES** | **YES** | **YES** | Android & iOS dispatch authenticated `/disconnect` and UDP `PHONE_DISCONNECT`. |
| **Storage & Ingestion** | Folder Tree Ingestion (SAF / Bookmarks) | **YES** (`OpenDocumentTree`) | N/A | **YES** (`fileImporter` folder) | Android uses persistable URI permissions; iOS uses security-scoped bookmarks. |
| | Multi-File Selection | **YES** (`OpenMultipleDocuments`) | N/A | **YES** (`fileImporter` files) | Direct file addition. |
| | Batch Selection & Removal | **YES** (Select / Select All / Remove) | N/A | **YES** (Edit mode) | Modifies app library without deleting source files on disk. |
| | Excluded Items Persistence | **YES** (SharedPreferences) | N/A | **NO** | Android persists removed items so re-scans don't re-add them. |
| **Media Probing & Analysis** | 10-bit Color / HDR Detection | **YES** (MediaExtractor profile & color keys) | N/A | **YES** (AVAssetTrack format descriptions) | Pure technical analysis without filename parsing. |
| | Thumbnail Extraction at 7% | **YES** (`thumb_percent_ver = 7`) | N/A | **PARTIAL** | Android extracts at 7% timeline; iOS defaults to 1.0s or 10%. |
| | Video Codec & Dimensions Extraction | **YES** | N/A | **YES** | Width, height, duration, and MIME types. |
| | Audio Codec Extraction | **YES** (MediaExtractor mime) | N/A | **YES** (AVAsset FourCC format descriptions) | Extracted directly from audio format descriptions (`mp4a`, `ac-3`, `ec-3`, `dts `). |
| **Playback & Remote Control** | Direct Play Preference Default | **YES** | **YES** | **YES** | Direct Play preferred across all platforms; transcoding requires explicit request. |
| | Compatibility Warning Dialog | **YES** ("Direct Play (Recommended)") | N/A | **YES** | Guides user before starting stream. |
| | Interactive Remote Scrubber (Now Playing) | **YES** | N/A | **YES** | Real-time timeline sync with TV. |
| | Remote Control Actions (Play/Pause/RW/FF/Restart/Stop) | **YES** | **YES** | **YES** | Verified via `POST /control` with HMAC authentication. |
| | 30s Buffer Preparation Countdown Dialog | **YES** | **YES** (TV Countdown Overlay) | **YES** (PreparingStreamView) | Displayed during live hardware transcode. |
| **TV Playback Engine** | ExoPlayer Video Playback | N/A | **YES** (Media3 1.3.1) | N/A | Supports hardware decoding for AVC, HEVC, VP9, AV1. |
| | TV D-Pad Navigation & Focus | N/A | **YES** | N/A | Remote control focus rings and D-Pad key handling. |
| | 4-Column Media Grid | N/A | **YES** (`GridLayoutManager(4)`) | N/A | Optimized layout to avoid horizontal overflow. |
| | Audio Track Selection UI | N/A | **YES** | N/A | In-player audio track switcher dialog. |
| | Subtitle Toggle UI | N/A | **YES** | N/A | In-player subtitle toggle. |
| | OSD Auto-Hide Timer (5s) | N/A | **YES** | N/A | Auto-hides on inactivity. |
| **Automated Testing** | Unit Tests | **YES** (AuthProtocolVerification) | **YES** (Shared suite) | **PARTIAL** | Automated cryptographic verification suite passes 100%. |
| | Integration / Instrumentation Tests | **NO** | **NO** | **NO** | No automated UI or end-to-end tests exist. |

---

## 3. Key Observations & Cross-Platform Parity Gaps

1. **iOS HTTP Server Non-Range Behavior**:
   In `PocketHttpServer.swift`, when a non-range request arrives for `/stream/<id>`, the server sets `Content-Length: \(fileSize)` but only reads the first 2MB (`min(2 * 1024 * 1024, fileSize)`). This causes clients that do not immediately send Range headers to encounter truncated file transfers.
2. **iOS Live Chunk Streaming**:
   Android features a dedicated `GrowingFileInputStream` with inter-thread synchronization and 25ms polling to stream actively transcoding fMP4 files. iOS returns an active file handle directly without growing-file synchronization.
3. **Excluded Media Persistence**:
   Android maintains an `excluded_media_ids` preference so that when folders are re-indexed, previously removed items remain excluded. iOS re-indexes all files within bookmarked directories on launch.
4. **Direct Play Route Parity**:
   On Android, calling `POST /api/v1/play` defaults to Direct Play mode even for 10-bit files (per the user requirement). On iOS, `PocketHttpServer.swift` line 236 still initiates transcode mode if `media.is10Bit && !isAlreadyTranscoded`.
