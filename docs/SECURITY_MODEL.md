# PocketStream Security Model & Threat Assessment

## 1. Security Architecture & Threat Model Overview

PocketStream is designed as a private, local-network peer-to-peer streaming system. Its core operational philosophy prioritizes zero-friction home entertainment streaming over zero-trust defense-in-depth, relying primarily on the home Wi-Fi network perimeter as its primary security boundary.

This document formally articulates the security architecture, trust assumptions, boundary definitions, and accepted risk profile.

---

## 2. Trust Assumptions & Boundaries

### 2.1 The Local Wi-Fi Perimeter Assumption
- **Assumption**: Any client capable of reaching TCP ports 8080 (Phone), 8085 (TV), and UDP port 8088 is an authorized station on the user's private WPA2/WPA3 home Wi-Fi network.
- **Accepted Trade-off**: No mutual TLS (mTLS), session tokens, passwords, or HMAC signatures are required between the mobile device and the TV receiver.
- **Threat Vector**: In untrusted, shared, or guest Wi-Fi networks (e.g. hotels, universities, public hotspots), any rogue client on the same subnet can inspect unencrypted media streams, send playback interruption commands, or query file library metadata.

### 2.2 Device Pairing Trust Model (Optical PSK + Cryptographic Bonding)
- **Model**: PocketStream utilizes an out-of-band Optical Pre-Shared Key (PSK) delivered via high-density dynamic QR code combined with HMAC-SHA256 challenge-response request signing.
- **Enforcement**:
  1. The TV receiver generates an ephemeral 256-bit session key (`key`) and session ID (`sid`) displayed exclusively on the physical TV screen via QR.
  2. The phone scans the QR code optically. The secret key is never broadcast over UDP or mDNS.
  3. Every request to the TV receiver (`/pair`, `/play`, `/control`, `/disconnect`, `/refresh`) requires an `X-PS-Signature` header computed using HMAC-SHA256 with timestamp validation (30s window), random nonces, and SHA-256 body integrity hashing.
  4. Once paired, the TV locks onto the authorized IP and validates the HMAC signature on all subsequent calls.
  5. Any unauthorized LAN client attempting to issue commands receives HTTP 401 Unauthorized or HTTP 409 Conflict.
  6. Rate limiting enforces an automatic 60-second lockout after 5 consecutive failed signature attempts.

---

## 3. Storage & Filesystem Access Model

### 3.1 Android Storage Access Framework (SAF)
- **Principle of Least Privilege**: The mobile app does NOT request legacy dangerous permissions (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, or `MANAGE_EXTERNAL_STORAGE`).
- **Sandboxed Ingestion**: All user media is accessed strictly through scoped URIs obtained via Android SAF (`OpenDocumentTree` and `OpenMultipleDocuments`).
- **Persisted Grants**: Scoped read permissions are persisted via `ContentResolver.takePersistableUriPermission`.

### 3.2 Path Traversal Defenses
- **Regex Validation**:
  In `PocketHttpServer.kt`, media identifiers passed in HTTP paths are strictly validated against:
  ```kotlin
  private val SAFE_MEDIA_ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")
  ```
  This prevents directory traversal payloads (e.g. `/stream/../../../../etc/passwd` or `/api/v1/thumbnail/..%2f..%2f`).
- **ContentResolver Isolation**: Media files are resolved via `mediaCache[mediaId].uri` rather than direct filesystem string concatenation. An attacker cannot force the server to serve files from outside the user's explicitly approved SAF folder tree.

---

## 4. Network Security & Transport Layer

### 4.1 Cleartext HTTP Traffic & Request Signing Defense
- **Configuration**: Both `apps/mobile/src/main/AndroidManifest.xml` and `apps/tv/src/main/AndroidManifest.xml` configure `android:usesCleartextTraffic="true"`.
- **Justification**: Embedded video decoders (such as realtek/amlogic hardware decoders on smart TVs) incur substantial CPU overhead and latency when decrypting high-bitrate TLS streams (15-40 Mbps).
- **Protection**: While video stream chunks flow over plain HTTP for zero-overhead hardware playback, all control planes and command endpoints (`/pair`, `/play`, `/control`, `/disconnect`) are protected against tampering and replay attacks via HMAC-SHA256 request signatures and sliding nonce validation.

### 4.2 Cross-Origin Resource Sharing (CORS) Policy
- **Configuration**: Wildcard `Access-Control-*` headers have been completely eliminated across Android Mobile, Android TV, and iOS. OPTIONS preflight requests return HTTP 405 Method Not Allowed.
- **Protection**: Malicious web pages running in local network browsers cannot interact with the phone server or TV receiver via cross-origin JavaScript fetch/XHR calls.

---

## 5. Denial of Service & Resource Management

### 5.1 Transcoder Cache Boundaries
- **Cache Quota**: `TranscoderManager` enforces a 6 GB cache limit (`MAX_CACHE_SIZE_BYTES = 6L * 1024L * 1024L * 1024L`).
- **Purge Mechanics**: Older transcoded temporary files (`transcoded_*.mp4`) are automatically deleted using LRU order when total cache exceeds the quota.

### 5.2 Stream Handle Leaks
- **Bounded Streams**: `BoundedInputStream` in `PocketHttpServer.kt` closes underlying `ParcelFileDescriptor` handles when streams are exhausted or aborted.
- **Live Stream Timeout**: `GrowingFileInputStream` implements `MAX_WAIT_WITHOUT_GROWTH_MS = 15_000L` to prevent zombie threads from hanging indefinitely if hardware encoding stalls.

---

## 6. Security Improvement Recommendations (Roadmap)
1. **Visual Pairing PIN / Ephemeral Token**: Require the TV to display a 4-digit PIN or embed a cryptographic nonce in the QR code, which the phone must present in an `Authorization: Bearer <token>` header on subsequent requests.
2. **Restrict CORS Wildcard**: Remove `Access-Control-Allow-Origin: *` or restrict origin to known local schemes, mitigating drive-by LAN cross-site request forgery.
3. **Optional TLS / HTTPS**: Provide an optional self-signed HTTPS certificate mode for enterprise or shared campus networks.
