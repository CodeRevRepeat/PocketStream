# PocketStream Production Readiness Checklist & Launch Gates

## 1. Scope & Objective
This checklist defines the non-negotiable verification gates required before PocketStream can be designated **Production Ready** across Android Mobile, Android TV, and iOS.

---

## 2. Production Readiness Gate Status

| Category | Gate Item | Status | Verified Evidence / Blocker | Gate Decision |
|---|---|---|---|---|
| **Security & Privacy** | Wildcard CORS Header Removal | **READY FOR FIX** | Removal planned in `REM-01`. Native clients verified independent of CORS. | Must be merged before release |
| | Cryptographic Pairing & Authentication | **PLANNED** | HMAC-SHA256 request signing & QR key exchange designed in `REM-04`. | Critical gate for public Wi-Fi release |
| | Credential Storage at Rest | **PLANNED** | Android Keystore (`EncryptedSharedPreferences`) and iOS Keychain integration designed. | High priority |
| | No Plaintext Secrets in Repository | **PASSED** | Verified: Git history inspected; zero signing keys or passwords tracked in Git. | Gate cleared |
| | Path Traversal Defense | **PASSED** | Verified: `SAFE_MEDIA_ID_REGEX` (`^[a-zA-Z0-9_-]+$`) and SAF sandboxing validated. | Gate cleared |
| | Scoped Storage Isolation | **PASSED** | Verified: Zero legacy storage permissions; Android SAF trees and iOS security bookmarks used. | Gate cleared |
| **Streaming & Core Engine** | iOS Full-File & Range Streaming Fix | **BLOCKED** | Verified bug: `BUG-STREAM-001` causes stream truncation and OOM crashes on iOS. | Must fix before release |
| | Android Direct Play Preference | **PASSED** | Verified: Direct play default active in all dialogs and TV grid selections. | Gate cleared |
| | Technical 10-bit Analysis | **PASSED** | Verified: `MediaExtractor` and `MediaCodecInfo` profile parsing active; filename parsing removed. | Gate cleared |
| | 7% Thumbnail Frame Extraction | **PASSED** | Verified: Timeline extraction at 7% with version invalidation active. | Gate cleared |
| | Live Transcode & Growing Stream | **PASSED** | Verified: Media3 Transformer fMP4 with 1-second chunks and tone-mapping operational. | Gate cleared |
| **TV Experience (10-Foot UI)** | 4-Column Media Grid | **PASSED** | Verified: `GridLayoutManager(4)` eliminates horizontal screen clipping. | Gate cleared |
| | D-Pad Navigation & Focus Rings | **PASSED** | Verified: Remote navigation and green focus rings verified across cards and OSD. | Gate cleared |
| | 1:1 Connection Exclusivity | **PASSED** | Verified: TV enforces IP lock; competing devices receive HTTP 409 Conflict. | Gate cleared |
| | OSD Auto-Hide & Track Switching | **PASSED** | Verified: 5s timer and ExoPlayer audio/subtitle track selectors operational. | Gate cleared |
| **Cross-Platform Parity** | iOS Capability Decision Matrix | **BLOCKED** | Verified divergence: iOS `/api/v1/play` route forces transcode on 10-bit content. | Must fix before release |
| | Discovery Protocol Parity | **PASSED** | Verified: Both platforms implement mDNS 5353 and UDP 8088 broadcast contracts. | Gate cleared |
| **Reliability & Performance** | GrowingFileInputStream Optimization | **PLANNED** | Event-driven condition variable designed in `REM-06` to replace 25ms thread sleep. | Recommended for thermal/battery |
| | File Descriptor Lifecycle Management | **PLANNED** | Explicit stream registry designed in `REM-07` to eliminate PFD leaks during scrub stress. | Recommended |
| | WakeLock & Foreground Service | **PASSED** | Verified: `ServerService` runs as foreground service with Wi-Fi and wake locks. | Gate cleared |
| **Quality Assurance** | Automated Test Coverage | **BLOCKED** | Complete gap: 0 automated tests in repository across all modules. | Automated suite required |
| | D-Pad Remote Stress Testing | **PASSED (MANUAL)** | Verified manual QA protocol completed. | Automation recommended |

---

## 3. Pre-Release Sign-Off Criteria

Before tagging a production `v1.3.0` release:

1. **Gate 1: Critical Bug Resolution**:
   - `BUG-STREAM-001` (iOS stream truncation / OOM) must be remediated and verified with files > 2 GB.
   - `SEC-NET-001` (CORS wildcard) must be removed.
2. **Gate 2: Functional Parity**:
   - `PAR-IOS-001` (iOS Direct Play matrix) must match Android specification.
3. **Gate 3: Automated Regression Harness**:
   - Minimum 80% unit test coverage for `shared/protocol`.
   - Automated MockWebServer integration tests for HTTP range streaming on both Android and iOS.
4. **Gate 4: Security Verification**:
   - Pairing authentication handshake (`REM-04`) implemented and verified with zero plaintext leakage in logs or URLs.
   - Brute-force rate limiting verified on TV receiver.
5. **Gate 5: Soak & Performance Benchmarking**:
   - 2-hour continuous direct play session without audio/video drift or memory growth.
   - 1-hour continuous live transcode session without thermal shutdown or CPU spike above 45%.
