# PocketStream Security & Production Remediation Plan

## 1. Executive Summary & Purpose
This document presents the detailed, production-readiness remediation plan for the PocketStream ecosystem (Android Mobile, Android TV, iOS Client, and Shared Protocol). It addresses all findings identified during the architecture and security audits, incorporating the approved architectural direction for **Pairing-Based Authentication (Option B)**, **CORS Wildcard Removal**, **iOS Streaming Engine Overhaul**, **Capability-Based Playback Matrix**, and **Secret Management**.

> [!IMPORTANT]
> **Strict Execution Status**: Planning and verification phase only. No application source code has been altered. Implementation will commence only upon explicit stakeholder approval.

---

## 2. Core Security Architecture: Pairing-Based Authentication (Option B)

### 2.1 Threat Model & Transport Interception Analysis
The user requirement explicitly states:
> *"A bearer token over unencrypted HTTP is not sufficient protection against LAN traffic interception. Evaluate transport encryption, certificate pinning or an appropriate challenge-response design. Explain the trade-offs and recommend an architecture suitable for a local media streaming application."*

#### Comparative Analysis of LAN Security Designs

| Architecture | Mechanism | Confidentiality | Replay Protection | Interception / MITM Defense | TV Hardware Decoder Impact | Implementation Complexity | Recommendation |
|---|---|---|---|---|---|---|---|
| **A. Static Bearer Token over Cleartext HTTP** | Token embedded in QR code; sent as `Authorization: Bearer <token>` | None | None (sniffed token is reusable indefinitely) | Vulnerable to passive LAN sniffing | Zero overhead | Minimal | **REJECTED** (Insecure against passive sniffing) |
| **B. Local HTTPS with Ephemeral Self-Signed Certificates & QR Pinning** | TV & Phone generate ephemeral TLS keys on launch; QR code contains SHA-256 cert fingerprint; TLS 1.3 | Full (AES-GCM / ChaCha20) | Full (TLS record sequence numbers) | Immune to MITM and sniffing | **High Risk**: Older TV SoCs (Realtek, Amlogic 32-bit) choke on CPU-based TLS decryption at 25-40 Mbps | High (Custom KeyStores, TrustManagers, OkHttp & NWConnection TLS configuration) | **Viable for Control, Problematic for High-Bitrate Video** |
| **C. Cryptographic Challenge-Response / HMAC-SHA256 Signed Requests** | High-entropy session key in QR code; every control request signed with timestamp, nonce, and HMAC; video uses time-limited signed capability tokens | Control payload authenticated; Video tokens expire in 60s | Full (Nonces + 30s timestamp window) | Sniffed control requests cannot be replayed; token forging impossible without session key | **Zero Overhead**: Zero TLS crypto decryption bottleneck on TV video pipelines | Moderate (Standard crypto primitives in Android, iOS, JVM) | **RECOMMENDED FOR LOCAL STREAMING** |
| **D. Dual-Plane Hybrid Architecture (TLS Control + Tokenized Direct Media)** | Ephemeral Mutual TLS for Control Plane (Port 8085/8081); Cleartext Tokenized Streaming for Media Plane (Port 8080) | Full control privacy; authenticated media tokens | Full | Full protection against control hijacking and rogue pairing | Media streams direct to hardware decoder without TLS CPU penalty | Moderate-High | **OPTIMAL ALTERNATIVE IF TLS IS MANDATED** |

---

### 2.2 Recommended Architecture: Zero-Cloud Cryptographic Pairing & Authentication

We recommend **Architecture C (Cryptographic Pre-Shared Key via QR + HMAC-SHA256 Control Signatures + Time-Limited Media Capability Tokens)**, with an optional path to **Architecture D (mTLS Control)**.

#### 2.2.1 Pairing Handshake & Key Derivation Flow
1. **TV Session Key Generation**:
   Upon launching, the TV receiver generates:
   - `tvSessionId`: A random UUIDv4 identifying the active session.
   - `tvSecretKey`: 256 bits of cryptographically secure random bytes (`java.security.SecureRandom`), Base64URL-encoded.
   - `pairingPin`: A 4-digit human-readable visual confirmation PIN displayed alongside the QR code.
2. **QR Code Payload**:
   The TV renders a dynamic QR code containing the extended URI:
   ```
   pocketstream://connect?v=2&ip=192.168.1.35&port=8085&sid=<tvSessionId>&key=<tvSecretKey>&name=TCL%20BeyondTV
   ```
   *Security Note*: The key is delivered exclusively through the optical channel (QR camera scan). An eavesdropper on the local Wi-Fi cannot capture `tvSecretKey` without physical line-of-sight to the TV screen.
3. **Phone Client Credential Storage**:
   - **Android**: The mobile app stores `tvSecretKey` and `tvSessionId` using **Android Keystore-backed `EncryptedSharedPreferences`** (MasterKey with AES256-GCM).
   - **iOS**: The iOS app stores `tvSecretKey` and `tvSessionId` in the **Apple Keychain Services** with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
   - Credentials are NEVER logged, appended to URLs, or exported in plaintext.
4. **Mutual Pairing Confirmation (`POST /pair`)**:
   Phone sends an authenticated pairing request to TV:
   - Phone generates its own `phoneSessionId` and `phoneSecretKey`.
   - Headers attached:
     ```http
     X-PS-Session: <tvSessionId>
     X-PS-Timestamp: 1793881200
     X-PS-Nonce: a1b2c3d4e5f6
     X-PS-Signature: HMAC-SHA256(tvSecretKey, "POST\n/pair\n1793881200\na1b2c3d4e5f6\n" + SHA256(body))
     ```
   - Payload:
     ```json
     {
       "phoneIp": "192.168.1.36",
       "phonePort": 8080,
       "phoneSessionId": "p-98765",
       "phoneSecretKey": "<Base64URL_PhoneKey>",
       "deviceName": "Google Pixel 8 Pro"
     }
     ```
   - TV verifies:
     1. Signature matches `tvSecretKey`.
     2. Timestamp is within $\pm 30$ seconds of TV system clock.
     3. Nonce has not been seen within the sliding replay cache.
     4. TV is unbonded or IP matches existing bonded phone.
   - TV stores `phoneSecretKey` in-memory. TV is now bonded to Phone.

#### 2.2.2 Authenticated Control Plane (`/play`, `/control`, `/disconnect`)
Every subsequent control request sent from Phone to TV (or TV to Phone) must carry the `X-PS-Signature`.
- If an unauthorized LAN device sends `POST /control` or `POST /pair`, verification fails instantly with **HTTP 401 Unauthorized**.
- If a rogue device sniffs a valid request on Wi-Fi and attempts to replay it, the replay is rejected with **HTTP 401 Unauthorized (Replay Detected)** because the `X-PS-Nonce` is already retired or the timestamp is outside the 30s window.

#### 2.2.3 Media Streaming Authorization (Tokenized Capability URLs)
To prevent unauthorized LAN devices from browsing `/api/v1/library` or downloading private videos via `/stream/{id}`:
- `GET /api/v1/library` requires HMAC-SHA256 signature in headers.
- Media streaming URLs (`/stream/{id}`, `/stream/live/{id}`) use time-limited cryptographic capability tokens:
  ```
  http://192.168.1.36:8080/stream/7b8f9e2a?exp=1793884800&nonce=4f8a&sig=HMAC-SHA256(phoneSecretKey, "7b8f9e2a:1793884800:4f8a")
  ```
  - Mobile server validates `sig` and checks that `exp > currentTime`.
  - Expiry is set to stream duration + 2 hours.
  - An unauthorized attacker who does not possess `phoneSecretKey` cannot forge valid streaming URLs.

#### 2.2.4 Brute-Force & Lockout Protections
- **Pairing Rate-Limiting**: The TV receiver tracks failed `/pair` attempts. After 5 consecutive failures from an IP within 5 minutes, that IP is locked out for 15 minutes (**HTTP 429 Too Many Requests**).
- **Session Revocation**:
  - Manual Disconnect on TV: Clicking "Disconnect Phone" immediately wipes `phoneSecretKey`, increments `tvSessionId`, regenerates `tvSecretKey`, and updates the QR code. All existing tokens are instantly revoked.
  - Stop Server on Phone: Phone sends signed `/disconnect` and wipes cached TV credentials.

---

## 3. Remediation Items & Technical Specifications

---

### REM-01: Remove Wildcard CORS Headers (`SEC-NET-001`)

#### 1. Finding ID
`SEC-NET-001` (Severity: High)

#### 2. Root Cause
`PocketHttpServer.kt`, `TvReceiverServer.kt`, and `PocketHttpServer.swift` unconditionally inject `Access-Control-Allow-Origin: *` into every HTTP response. This was added during early development for hypothetical browser access, but exposes LAN users to drive-by cross-site request forgery and data exfiltration from standard web browsers.

#### 3. Affected Applications and Files
- `apps/mobile/src/main/java/com/pocketstream/mobile/server/PocketHttpServer.kt` (lines 34–39)
- `apps/tv/src/main/java/com/pocketstream/tv/receiver/TvReceiverServer.kt` (lines 38–43)
- `ios/PocketStream/Server/PocketHttpServer.swift` (lines 363–365)
- `REQUIREMENT.md` (spec cleanup)

#### 4. Recommended Implementation
- Remove `addCorsHeaders()` and all `Access-Control-*` header injections across Android and iOS servers.
- Remove `OPTIONS` preflight handling or return `405 Method Not Allowed` for `OPTIONS`.
- Verify: Supported clients (Android OkHttp, ExoPlayer, iOS URLSession, AVPlayer) do NOT send preflight `OPTIONS` requests or inspect CORS headers.

#### 5. Compatibility Risks
- **Zero Risk** to existing Android TV, Android Phone, and iOS clients.
- If a web-based client is developed in the future, it would require a dedicated local proxy or reverse proxy.

#### 6. Regression Tests
- `TC-CORS-001`: Run pairing, library fetching, and video playback on TV. Confirm all HTTP calls succeed without CORS headers.
- `TC-CORS-002`: Send a cross-origin `fetch()` request from a web browser on LAN; confirm browser blocks response reading.

#### 7. Rollback Strategy
Re-add `addCorsHeaders()` if an unexpected supported client requires it.

#### 8. Priority
**P0 (Immediate)**

---

### REM-02: Overhaul iOS Media Streaming Engine (`BUG-STREAM-001`)

#### 1. Finding ID
`BUG-STREAM-001` (Severity: High)

#### 2. Root Cause
In `PocketHttpServer.swift` (`serveFile` lines 346–358):
1. For non-range requests, `Content-Length` is set to total file size, but only 2 MB is read and sent before closing the connection, truncating the stream.
2. For range requests (`Range: bytes=0-`), `fileHandle.readData(ofLength:)` attempts to read the entire remainder of the file into memory synchronously. For a 2–4 GB video file, this causes an immediate out-of-memory (OOM) crash on iOS.
3. Synchronous disk reads block the `NWConnection` dispatch queue.

#### 3. Affected Applications and Files
- `ios/PocketStream/Server/PocketHttpServer.swift` (lines 320–382)
- `ios/PocketStream/Views/ContentView.swift`

#### 4. Recommended Implementation
Refactor `serveFile` into an asynchronous, chunked streaming pump using bounded buffers:
1. **Streaming Window**: Define `CHUNK_SIZE = 256 * 1024` (256 KB) or `1024 * 1024` (1 MB max per transfer frame).
2. **Byte-Range Compliance (HTTP 206)**:
   - When `Range: bytes=start-end` is received:
     - If `end` is omitted or exceeds `fileSize - 1`, cap the immediate response window to `min(start + MAX_RANGE_WINDOW - 1, fileSize - 1)` (e.g. 8 MB window) OR stream the requested range iteratively using `NWConnection.send(content:isComplete:completion:)` with backpressure.
     - Set `Content-Range: bytes \(start)-\(end)/\(fileSize)` and `Content-Length: \(rangeLength)`.
     - Respond with `HTTP/1.1 206 Partial Content`.
3. **Full-File Streaming (HTTP 200)**:
   - When no `Range` header is present:
     - Set `Accept-Ranges: bytes`, `Content-Length: \(fileSize)`.
     - Pump data in 256 KB buffers using recursive completion handlers (`NWConnection.send`) until EOF or connection cancellation.
4. **Lifecycle & Cleanup**:
   - Ensure `fileHandle.close()` is called within a `defer` block or when `connection.stateUpdateHandler` transitions to `.cancelled` or `.failed`.

#### 5. Compatibility Risks
- Android TV ExoPlayer relies heavily on HTTP 206 Partial Content and `Accept-Ranges: bytes`. Incorrect `Content-Range` math causes ExoPlayer `UnrecognizedInputFormatException` or `HttpDataSourceException`.
- Strict RFC 7233 compliance ensures seamless compatibility.

#### 6. Regression Tests
- `TC-IOS-001`: Direct Play of 3 GB MP4 file from iOS to Android TV. Verify playback starts within 1.5s.
- `TC-IOS-002`: Forward and backward timeline seek 20 times in succession. Verify memory remains stable under 60 MB and no OOM crashes occur.
- `TC-IOS-003`: Non-range `curl -i http://<ios_ip>:8080/stream/<id>` downloads full file without premature termination.

#### 7. Rollback Strategy
Revert to single-chunk range responses with explicit length bounds.

#### 8. Priority
**P0 (Immediate)**

---

### REM-03: Capability-Based Playback Decision Engine (`PAR-IOS-001`)

#### 1. Finding ID
`PAR-IOS-001` (Severity: Medium)

#### 2. Root Cause
In `PocketHttpServer.swift` line 236, the `/api/v1/play` route automatically forces transcode mode for any file with `is10Bit == true`. This directly contradicts the user decision to prefer Direct Play in all cases (ADR-001). Furthermore, `AVAssetExportSession` on iOS cannot transcode Matroska (`.mkv`) containers or DTS/AC3 audio tracks, causing playback failure.

#### 3. Affected Applications and Files
- `ios/PocketStream/Server/PocketHttpServer.swift` (lines 235–260)
- `ios/PocketStream/Storage/MediaRepository.swift` (lines 216–256)
- `ios/PocketStream/Views/IncompatibleFormatView.swift`
- `ios/PocketStream/Views/ContentView.swift`

#### 4. Recommended Implementation
Implement a comprehensive **Capability-Based Compatibility Matrix**:
1. **Audio & Container Probing on iOS**:
   - Update `MediaRepository.swift` to probe all audio tracks via `asset.tracks(withMediaType: .audio)`. Extract actual fourCC codecs (e.g. `mp4a` -> AAC, `ac-3` -> AC3, `ec-3` -> E-AC3, `dts ` -> DTS). Do not hardcode `"aac"`.
2. **Capability Matching against TV Receiver**:
   - When user taps "Stream to TV", compare:
     - Container format against TV supported demuxers.
     - Video codec (`avc1`, `hvc1`, `vp09`, `av01`) against `tvCapabilities.supportedVideoMimes`.
     - Bit depth against `tvCapabilities.supports10BitHevc`.
     - Resolution against `tvCapabilities.maxWidth` / `maxHeight`.
3. **Direct Play Default**:
   - In `/api/v1/play`, default to Direct Play mode matching Android.
   - If format is technically incompatible, prompt user with:
     - Primary: `▶ Direct Play (Recommended)`
     - Secondary: `▷ Encode & Stream` (Only enabled if iOS AVFoundation is capable of transcoding that container and codec; otherwise disabled with tooltip explaining container constraints).

#### 5. Compatibility Risks
- If a low-end TV attempts direct playback of a 4K 10-bit HEVC stream and crashes, the user still retains the "Encode & Stream" option.

#### 6. Regression Tests
- `TC-PAR-001`: Request playback of 10-bit MKV from TV grid; verify Direct Play initiates immediately.
- `TC-PAR-002`: Verify audio plays correctly without codec mismatch errors.

#### 7. Rollback Strategy
Revert to manual compatibility dialog prompt.

#### 8. Priority
**P1 (High)**

---

### REM-04: Implement Cryptographic Challenge-Response Pairing & Auth (`SEC-AUTH-001`)

#### 1. Finding ID
`SEC-AUTH-001` (Severity: High)

#### 2. Root Cause
Absence of mutual authentication or session tokens on ports 8080 and 8085. Pairing relies purely on IP matching, allowing any device on LAN to control TV or harvest media metadata.

#### 3. Affected Applications and Files
- `shared/protocol/src/main/java/com/pocketstream/protocol/` (new auth contracts)
- `apps/tv/src/main/java/com/pocketstream/tv/receiver/TvReceiverServer.kt`
- `apps/mobile/src/main/java/com/pocketstream/mobile/server/PocketHttpServer.kt`
- `apps/mobile/src/main/java/com/pocketstream/mobile/MainActivity.kt`
- `ios/PocketStream/Server/PocketHttpServer.swift`
- `ios/PocketStream/Views/QRScannerView.swift`

#### 4. Recommended Implementation
1. TV generates dynamic 256-bit `sessionKey` and embeds it in QR code scheme:
   `pocketstream://connect?v=2&ip=...&port=8085&key=<base64Key>&sid=<sessionId>`
2. Phone scans QR code, securely stores `sessionKey` in Keystore / Keychain.
3. All requests to `/pair`, `/play`, `/control`, `/disconnect`, `/refresh` must supply:
   - `X-PS-Session: <sessionId>`
   - `X-PS-Timestamp: <epoch_sec>`
   - `X-PS-Nonce: <random_hex>`
   - `X-PS-Signature: HMAC-SHA256(sessionKey, message)`
4. TV rejects invalid signatures with `401 Unauthorized` and maintains sliding nonce replay cache.
5. Rate limit: 5 failed pairing attempts in 5 minutes triggers 15-minute IP lockout.
6. Server stop / TV disconnect purges keys and immediately revokes all access.

#### 5. Compatibility Risks
- Older versions of PocketStream Mobile will not be able to pair with updated TV receivers (Breaking protocol bump to v2).
- Both TV and Mobile must be updated simultaneously.

#### 6. Regression Tests
- `TC-AUTH-001`: Valid QR pairing establishes authenticated session; control commands succeed.
- `TC-AUTH-002`: Rogue device on LAN dispatches `POST /control`; TV returns HTTP 401.
- `TC-AUTH-003`: Intercepted control packet replayed after 35 seconds; TV returns HTTP 401 (Expired).

#### 7. Rollback Strategy
Retain protocol fallback flag `ALLOW_LEGACY_UNAUTHENTICATED_PAIRING = false` during transition.

#### 8. Priority
**P1 (High)**

---

### REM-05: Secret Management & Keystore Remediation (`SEC-SECR-001`)

#### 1. Finding ID
`SEC-SECR-001` (Severity: Low)

#### 2. Root Cause & Git History Verification
- **Verified Fact**: Git history was inspected across all commits (`git log -S "sungrumani"` and `git log -- keystore.properties`). The plaintext passwords and `.jks` keys **have never been committed to Git**.
- However, `keystore.properties` resides in the local working directory with plaintext passwords. If committed by mistake or shared in an archive, release signing keys would be compromised.

#### 3. Affected Applications and Files
- `keystore.properties`
- `apps/mobile/build.gradle` (lines 19–29)
- `apps/tv/build.gradle` (lines 19–29)

#### 4. Recommended Implementation
1. Update `apps/mobile/build.gradle` and `apps/tv/build.gradle` to prioritize **environment variables**:
   ```groovy
   storeFile file(System.getenv("POCKETSTREAM_KEYSTORE_FILE") ?: props['RELEASE_STORE_FILE'])
   storePassword System.getenv("POCKETSTREAM_KEYSTORE_PASSWORD") ?: props['RELEASE_STORE_PASSWORD']
   keyAlias System.getenv("POCKETSTREAM_KEY_ALIAS") ?: props['RELEASE_KEY_ALIAS']
   keyPassword System.getenv("POCKETSTREAM_KEY_PASSWORD") ?: props['RELEASE_KEY_PASSWORD']
   ```
2. Create `keystore.properties.example` with sanitized placeholders.
3. Ensure `keystore.properties` remains in `.gitignore`.

#### 5. Compatibility Risks
Zero risk; backwards compatible with local developer setups.

#### 6. Regression Tests
Run release build with environment variables set; confirm APK is signed correctly.

#### 7. Rollback Strategy
Restore original Gradle properties reading.

#### 8. Priority
**P2 (Medium)**

---

### REM-06: Event-Driven Synchronization for `GrowingFileInputStream` (`PERF-STREAM-001`)

#### 1. Finding ID
`PERF-STREAM-001` (Severity: Medium)

#### 2. Root Cause
In `GrowingFileInputStream.kt` lines 61–72, when a reading thread reaches the end of actively encoding fMP4 data, it loops with `Thread.sleep(25L)`. Under concurrent seeking or multiple connections, this creates CPU wakeups, context switches, and repeated filesystem queries (`file.length()`).

#### 3. Affected Applications and Files
- `apps/mobile/src/main/java/com/pocketstream/mobile/server/GrowingFileInputStream.kt`
- `apps/mobile/src/main/java/com/pocketstream/mobile/transcoder/TranscoderManager.kt`

#### 4. Recommended Implementation
Replace busy-wait sleep with **Condition Variable / Event-Driven Signaling**:
1. `TranscoderManager` maintains a `FileGrowthNotifier` for the active transcode file.
2. In `Media3 Transformer`'s muxer listener or fragment callback, whenever a new fragment is flushed to disk, call `growthNotifier.notifyBytesAppended(newLength)`.
3. In `GrowingFileInputStream`, replace `Thread.sleep(25L)` with `growthNotifier.awaitGrowth(position, timeoutMs = 2000L)` using `java.util.concurrent.locks.Condition`.
4. Read thread sleeps with zero CPU overhead until fresh data is flushed.

#### 5. Compatibility Risks
Low; preserves identical streaming behavior while drastically reducing mobile CPU cycles.

#### 6. Regression Tests
Benchmark CPU utilization and thread wakeups during 1080p transcode streaming before and after remediation.

#### 7. Rollback Strategy
Revert to 25ms polling loop if fragment flush notifications are missed by older Android OEM codecs.

#### 8. Priority
**P2 (Medium)**

---

### REM-07: File Descriptor Leak Prevention (`RES-LEAK-001`)

#### 1. Finding ID
`RES-LEAK-001` (Severity: Medium)

#### 2. Root Cause
In `PocketHttpServer.kt`, `ParcelFileDescriptor` instances are opened for each media stream. If a client terminates socket connection abruptly without consuming the stream, the PFD closure relies on garbage collector finalizers, risking `EMFILE` under aggressive scrubbing.

#### 3. Affected Applications and Files
- `apps/mobile/src/main/java/com/pocketstream/mobile/server/PocketHttpServer.kt`

#### 4. Recommended Implementation
1. Implement a thread-safe `ActiveStreamRegistry` mapping HTTP connection IDs to open `Closeable` instances.
2. Hook into NanoHTTPD's connection termination lifecycle to invoke `close()` immediately when a socket disconnects.
3. Wrap all `FileInputStream` and `ParcelFileDescriptor` objects in a self-cleaning `AutoCloseable` guard.

#### 5. Compatibility Risks
Zero risk; strengthens resource cleanup.

#### 6. Regression Tests
Execute 100 rapid seek operations in 30 seconds; monitor `/proc/[pid]/fd` count; verify it returns to baseline (< 40 open FDs).

#### 7. Rollback Strategy
Revert to existing `BoundedInputStream` destructor pattern.

#### 8. Priority
**P2 (Medium)**

---

## 4. Remediation Priority Matrix

| Priority | Finding ID | Title | Estimated Effort | Impact |
|---|---|---|---|---|
| **P0** | `BUG-STREAM-001` | Fix iOS HTTP Server Stream Truncation & Memory Exhaustion | 1.5 Days | Eliminates iOS streaming failures and OOM crashes |
| **P0** | `REM-01 / SEC-NET-001` | Remove Permissive CORS Headers Across All Servers | 0.5 Days | Eliminates LAN browser CSRF and metadata exfiltration |
| **P1** | `PAR-IOS-001` | Implement Capability-Based Playback Matrix on iOS | 1.5 Days | Prevents unnecessary transcoding and unsupported container crashes |
| **P1** | `SEC-AUTH-001` | Implement Cryptographic QR Pairing & HMAC Request Signing | 3 Days | Secures LAN against rogue pairing and control injection |
| **P2** | `PERF-STREAM-001` | Event-Driven Signaling for `GrowingFileInputStream` | 1 Day | Reduces mobile CPU utilization and battery consumption |
| **P2** | `RES-LEAK-001` | File Descriptor Lifecycle Registry on Android Server | 1 Day | Eliminates socket/file descriptor leaks during scrub stress |
| **P2** | `SEC-SECR-001` | Environment Variable Keystore Secrets Management | 0.5 Days | Protects signing credentials against accidental disclosure |
