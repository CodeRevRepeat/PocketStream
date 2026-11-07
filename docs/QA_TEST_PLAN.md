# PocketStream Quality Assurance & Test Plan

## 1. Quality Strategy & Testing Scope

This document establishes the comprehensive verification framework for PocketStream across its three client applications:
- **Mobile Client** (`apps/mobile`)
- **TV Receiver** (`apps/tv`)
- **iOS Client** (`ios/PocketStream`)

> [!WARNING] Test Coverage Gap Notice
> As of this audit, zero automated unit, integration, or instrumentation tests exist within the repository. All verification currently relies on manual QA protocols and ad-hoc scripts. This document defines the formal manual test matrix and the roadmap for automating test suites.

---

## 2. Test Execution Matrix

### 2.1 Device Discovery & Pairing Tests

| Test ID | Title | Prerequisites | Steps | Expected Result | Status |
|---|---|---|---|---|---|
| **TC-PAIR-001** | mDNS Auto-Discovery | Phone and TV on same Wi-Fi subnet | 1. Start TV app.<br>2. Start Phone media server. | Phone auto-detects TV name within 3 seconds without QR scan. | Verified Manual |
| **TC-PAIR-002** | QR Code Pairing | TV displaying QR code | 1. Tap "Scan TV QR" on Phone.<br>2. Scan TV QR with camera. | Phone connects instantly; TV updates badge to "Connected". | Verified Manual |
| **TC-PAIR-003** | 1:1 Connection Exclusivity | TV bonded to Phone A | 1. From Phone B on same Wi-Fi, attempt `POST /pair` or scan QR. | TV rejects Phone B with HTTP 409 Conflict. Phone B displays "TV is busy". | Verified Manual |
| **TC-PAIR-004** | Disconnect on Server Stop | Phone bonded to TV | 1. On Phone, tap "Stop Server". | Phone dispatches `/disconnect` and UDP disconnect. TV resets to searching mode. | Verified Manual |
| **TC-PAIR-005** | TV Manual Disconnect | Phone bonded to TV | 1. On TV, click "Disconnect Phone" button. | TV clears connection; phone status returns to unbonded. | Verified Manual |

---

### 2.2 Media Ingestion & Analysis Tests

| Test ID | Title | Prerequisites | Steps | Expected Result | Status |
|---|---|---|---|---|---|
| **TC-INGEST-001** | SAF Directory Import | Local video folders on device | 1. Tap "Select Folder (SAF)".<br>2. Select folder with MKV, MP4, WEBM. | Videos appear in library with correct titles, sizes, and formats. | Verified Manual |
| **TC-INGEST-002** | Persistent SAF Grant | Folder already imported | 1. Force close phone app.<br>2. Re-open phone app. | Library restores automatically without prompting for folder permissions. | Verified Manual |
| **TC-META-001** | 10-bit HDR Technical Detection | 10-bit HEVC file named `sample.mkv` | 1. Ingest file into library.<br>2. Check badge and metadata. | Detected as 10-bit HEVC via track analysis, not filename string matching. | Verified Manual |
| **TC-META-002** | 7% Thumbnail Extraction | Video file > 10 seconds | 1. Ingest video into library. | Thumbnail extracted at 7% mark; black intro frames avoided. | Verified Manual |
| **TC-LIB-001** | Batch Item Removal | Multiple videos in library | 1. Tap "Select".<br>2. Select 2 items.<br>3. Tap "Remove (2)". | Items removed from app library; original files on disk remain untouched. | Verified Manual |

---

### 2.3 Streaming & Playback Tests

| Test ID | Title | Prerequisites | Steps | Expected Result | Status |
|---|---|---|---|---|---|
| **TC-STREAM-001** | Direct Play Preference (Mobile) | TV paired, 10-bit file | 1. Tap "Stream to TV".<br>2. Observe Compatibility Dialog.<br>3. Tap "Direct Play (Recommended)". | Direct stream (`/stream/<id>`) begins on TV without transcode delay. | Verified Manual |
| **TC-STREAM-002** | Direct Play from TV Grid | TV paired with phone | 1. Navigate TV grid via D-Pad.<br>2. Press D-Pad Center on any video. | Direct stream starts immediately; phone receives play notification. | Verified Manual |
| **TC-STREAM-003** | Live Hardware Transcode | TV paired, 10-bit file | 1. Tap "Stream to TV".<br>2. Tap "Encode & Stream". | Phone shows 30s countdown; TV receives live fMP4 chunks; smooth playback. | Verified Manual |
| **TC-STREAM-004** | Byte-Range Seeking | Direct stream playing | 1. On phone remote, scrub to 50% mark. | TV jumps to target position seamlessly using HTTP 206 Partial Content. | Verified Manual |
| **TC-STREAM-005** | Seek on Active Transcode | Live transcode playing | 1. On TV, scrub forward via D-Pad. | Phone restarts encoder with startPositionMs; TV shows preparing countdown. | Verified Manual |

---

### 2.4 TV UI & D-Pad Remote Tests

| Test ID | Title | Prerequisites | Steps | Expected Result | Status |
|---|---|---|---|---|---|
| **TC-TV-001** | 4-Column Grid Responsiveness | >= 8 items in library | 1. View TV home screen. | Exactly 4 items per row; no horizontal clipping beyond screen edge. | Verified Manual |
| **TC-TV-002** | D-Pad Focus Highlighting | TV home screen | 1. Press D-Pad Left/Right/Up/Down. | Active item displays visible lime/green focus ring. | Verified Manual |
| **TC-TV-003** | OSD Inactivity Auto-Hide | Video playing on TV | 1. Bring up OSD controls.<br>2. Leave remote untouched for 5s. | OSD automatically fades out. | Verified Manual |
| **TC-TV-004** | Audio Track Switching | Video with multiple audio streams | 1. Open audio track selector on TV.<br>2. Switch to secondary language. | ExoPlayer switches audio track seamlessly without restarting stream. | Verified Manual |

---

### 2.5 Network Resilience & Disruption Tests

| Test ID | Title | Prerequisites | Steps | Expected Result | Status |
|---|---|---|---|---|---|
| **TC-NET-001** | Wi-Fi Signal Drop During Stream | Direct stream playing | 1. Toggle phone Wi-Fi off and on. | Player pauses/buffers; resumes or reports clear network error. | Pending |
| **TC-NET-002** | Phone Sleep / Background Lock | Server running in background | 1. Lock phone screen while streaming. | Foreground service with wake lock maintains uninterrupted streaming. | Verified Manual |
| **TC-NET-003** | Rogue Client Rejection | Active session alive | 1. Run curl script to `POST /control` from third IP. | TV responds with HTTP 403 / 409 and rejects control injection. | Verified Manual |

### 2.6 Automated Cryptographic & Security Verification Tests

| Test ID | Title | Scope | Steps | Expected Result | Status |
|---|---|---|---|---|---|
| **TC-SEC-001** | Secure Key & Nonce Generation | `AuthProtocol` | Generate 256-bit key and random nonces. | 64-char key generated; nonces strictly unique. | **PASSED (Automated)** |
| **TC-SEC-002** | HMAC-SHA256 Signature Validation | `AuthProtocol` | Sign request with method, path, timestamp, nonce, body hash. | Signature verifies successfully against canonical payload. | **PASSED (Automated)** |
| **TC-SEC-003** | Tampered Payload Rejection | `AuthProtocol` | Alter request body after signing. | Verification fails; request rejected. | **PASSED (Automated)** |
| **TC-SEC-004** | Timestamp Skew & Replay Rejection | `AuthProtocol` | Replay signed request with timestamp skew > 30s. | Verification fails; out-of-window requests rejected. | **PASSED (Automated)** |
| **TC-SEC-005** | QR Code Optical PSK Encoding/Parsing | `TvInfo` | Encode and decode QR code with `sid` and `key`. | All fields (IP, port, name, sid, key) match 100%. | **PASSED (Automated)** |

---

## 3. Automation Implementation Roadmap
1. **Protocol Unit Tests** (`shared/protocol`): JUnit 5 tests verifying JSON serialization and deserialization of `TvCapabilities`, `MediaItem`, and `PlaybackControl`.
2. **Server Mock Tests** (`apps/mobile` & `apps/tv`): Robolectric / MockWebServer tests validating HTTP 200/206/409 responses, range parsing, and CORS headers.
3. **Codec Compatibility Unit Tests**: Unit tests feeding mocked `MediaFormat` dictionaries into `TvCapabilities.checkCompatibility` to verify 10-bit detection logic.
