# PocketStream Architectural & Engineering Decision Log

## 1. Overview
This log documents key architectural, engineering, and protocol decisions made during the design and evolution of the PocketStream platform. Every decision includes context, alternatives considered, rationale, and consequences.

---

## 2. Decision Records

### ADR-001: Preference for Direct Play Over Automatic Transcoding
- **Date**: 2026-11-04
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: Initially, when a 10-bit or HDR video was detected on a TV supporting only 8-bit, the mobile app forced or strongly recommended live hardware encoding. In many real-world scenarios, TVs or internal decoders can downsample or direct play without crashing, whereas transcoding consumes battery, heats the mobile SoC, and introduces a 30s buffer delay.
- **Decision**: Direct Play is preferred in all scenarios across the application. When a file is flagged as incompatible, the compatibility dialog presents `▶ Direct Play (Recommended)` as the prominent primary action, with `▷ Encode & Stream` as an optional secondary action. Selecting an item directly from the TV grid triggers immediate Direct Play.
- **Consequences**: Faster playback start times, significantly reduced battery/CPU consumption on phones, and empowerment of user choice.

### ADR-002: Rigorous Technical Media Analysis vs. Filename Matching
- **Date**: 2026-11-04
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: Earlier iterations checked for strings like `"10bit"`, `"10-bit"`, or `"hdr"` in filenames. This caused false positives (e.g., standard 8-bit files with "10bit" in release titles) and false negatives (10-bit files named simply `movie.mkv`).
- **Decision**: Eliminate all string-based filename heuristics for bit-depth detection. Extract `MediaFormat` and `MediaCodecInfo` profile levels (HEVC Main 10, AVC High 10, VP9 Profile 2/3, AV1 Main 10) and probe `KEY_COLOR_TRANSFER` / `KEY_COLOR_STANDARD` using `MediaExtractor`.
- **Consequences**: Accurate 10-bit identification based solely on actual stream container data.

### ADR-003: Video Thumbnail Frame Position at 7% Timeline
- **Date**: 2026-11-05
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: Thumbnails extracted at 0% or 1-2% frequently captured black studio logo intro cards. Extraction at 10% sometimes skipped into the middle of introductory credits or missed establishing shots.
- **Decision**: Standardize thumbnail capture at exactly 7% of the total duration (falling back to 50% if duration is under 6 seconds). Invalidate cached thumbnails via `thumb_percent_ver = 7` preference key.
- **Consequences**: Consistently informative, high-quality preview frames in both phone and TV grid views.

### ADR-004: 4-Column Media Grid for Android TV
- **Date**: 2026-11-05
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: The TV interface previously rendered oversized thumbnail cards that clipped horizontally beyond the right edge of standard 1080p and 4K displays.
- **Decision**: Configure `GridLayoutManager(this, 4)` with dynamically calculated margins and item card dimensions, ensuring 4 items fill the row seamlessly and wrap cleanly.
- **Consequences**: Balanced, responsive 10-foot TV experience.

### ADR-005: 1:1 Connection Exclusivity & Session Keep-Alive
- **Date**: 2026-11-04
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: When multiple phones were on the same Wi-Fi network, spurious discovery broadcasts triggered mid-playback "Paired with phone" toast alerts on TV, and rival devices could overwrite the active session.
- **Decision**: TV locks onto the first paired phone IP. Subsequent `/pair` or `/play` requests from different IPs receive HTTP 409 Conflict (`{"status": "busy"}`). The connection remains persistent until explicitly disconnected by the user or until the phone server is stopped.
- **Consequences**: Uninterrupted playback, zero mid-movie disconnection toasts, and deterministic remote control.

### ADR-006: Android Storage Access Framework (SAF) Ingestion
- **Date**: 2026-10-28
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: Requesting `MANAGE_EXTERNAL_STORAGE` or legacy `READ_EXTERNAL_STORAGE` requires elevated permissions and faces rejection under Google Play policies.
- **Decision**: Rely entirely on `ActivityResultContracts.OpenDocumentTree` and `OpenMultipleDocuments`. Persist permissions using `takePersistableUriPermission`.
- **Consequences**: Compliant with Android 11+ scoped storage policies, zero intrusive permission prompts, and robust security sandbox isolation.

### ADR-007: Fragmented MP4 (fMP4) with InAppMuxer for Live Transcoding
- **Date**: 2026-10-29
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: Standard progressive MP4 requires the complete file to finish encoding before writing the `moov` index atom, preventing live streaming during transcode.
- **Decision**: Use Media3 Transformer's `InAppMuxer` with fragmented MP4 enabled (`setFragmentedMp4Enabled(true)`) and 1-second fragment intervals. Stream live via `GrowingFileInputStream`. Once finished, remux to progressive MP4 for permanent caching.
- **Consequences**: Immediate live playback after brief 30s buffer, coupled with full byte-range seekability for subsequent viewings.

### ADR-008: Optical PSK + HMAC-SHA256 Challenge-Response Pairing (Option C)
- **Date**: 2026-11-05
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: A static bearer token over unencrypted HTTP is vulnerable to passive LAN sniffing. However, full TLS on Smart TV receivers introduces severe CPU decryption bottlenecks on high-bitrate video pipelines.
- **Decision**: Deliver a 256-bit ephemeral session key exclusively through the optical channel (QR camera scan). Sign all control requests (`POST /pair`, `/play`, `/control`, `/disconnect`, `/refresh`) using `HMAC-SHA256` with sliding nonce replay caches and 30-second timestamp windows. Lock out IPs after 5 consecutive failures.
- **Consequences**: Eavesdroppers cannot capture keys without physical line-of-sight to the TV. Replay attacks are defeated. Video decoding incurs zero CPU cryptographic overhead.

### ADR-009: Removal of Wildcard CORS Headers Across All Servers
- **Date**: 2026-11-05
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: `Access-Control-Allow-Origin: *` exposed LAN users to drive-by CSRF and private media library exfiltration from web browsers.
- **Decision**: Remove all CORS headers from Mobile, TV, and iOS HTTP servers. Supported clients (OkHttp, ExoPlayer, URLSession, AVPlayer) are native and do not require CORS.
- **Consequences**: Complete closure of LAN web browser attack vectors.

### ADR-010: Memory-Bounded Asynchronous Chunk Streaming on iOS
- **Date**: 2026-11-05
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: Synchronous `fileHandle.readData(ofLength:)` on range requests attempted to load multi-gigabyte video files into RAM, triggering OOM crashes. Non-range requests truncated file downloads to 2 MB while advertising total file length.
- **Decision**: Refactor `PocketHttpServer.swift` to pump data in bounded 256 KB chunks using `NWConnection.send` with backpressure. Full-file requests return HTTP 200 with complete file length. Valid range requests return HTTP 206 with exact range chunk length. Invalid ranges return HTTP 416.
- **Consequences**: Bounded memory footprint (< 60 MB) regardless of video file size, RFC 7233 compliance, and zero OOM crashes.

### ADR-011: Audio Format Inspection and Direct Play Alignment on iOS
- **Date**: 2026-11-05
- **Status**: **ACCEPTED & IMPLEMENTED**
- **Context**: iOS hardcoded `audioCodec: "aac"` and forced transcoding for 10-bit content on `/api/v1/play`, causing playback failures on Matroska containers.
- **Decision**: Inspect audio format descriptions from `AVAssetTrack` and align `/api/v1/play` to default to Direct Play mode matching ADR-001.
- **Consequences**: Cross-platform behavioral parity and elimination of failed transcoding on MKV containers.
