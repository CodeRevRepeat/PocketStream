# PocketStream Security Test Plan & Verification Harness

## 1. Overview & Test Objectives
This document defines the formal security testing specification for verifying all security controls, cryptographic pairing protocols, transport defenses, and sandboxing in the PocketStream platform.

---

## 2. Automated Security Test Suites

### 2.1 Cryptographic Pairing & Authentication Tests (`SEC-AUTH`)

| Test ID | Test Case | Target Application | Test Input / Vector | Expected Outcome |
|---|---|---|---|---|
| **ST-AUTH-001** | Valid QR Pairing Handshake | TV Receiver (`:8085`) | Scans QR, extracts `sid` and `key`, posts valid HMAC-SHA256 signature to `POST /pair`. | HTTP 200 OK; returns `status: "paired"`, sets bonded state. |
| **ST-AUTH-002** | Invalid HMAC Signature Rejection | TV Receiver (`:8085`) | Dispatches `POST /pair` with corrupted `X-PS-Signature`. | HTTP 401 Unauthorized; pairing rejected; error logged. |
| **ST-AUTH-003** | Expired Timestamp Rejection | TV Receiver (`:8085`) | Dispatches valid signature with `X-PS-Timestamp` set to $(current - 60s)$. | HTTP 401 Unauthorized; timestamp skew detected; pairing rejected. |
| **ST-AUTH-004** | Nonce Replay Attack Defense | TV Receiver (`:8085`) | Intercepts valid request and dispatches exact same request within timestamp window. | HTTP 401 Unauthorized (Replay Detected); nonce already in sliding cache. |
| **ST-AUTH-005** | Competing Device Lockout (Exclusivity) | TV Receiver (`:8085`) | TV bonded to Device A; Device B sends signed `POST /pair` with valid QR credentials. | HTTP 409 Conflict (`{"status":"busy"}`); Device A remains uninterrupted. |
| **ST-AUTH-006** | Brute-Force Rate Limiting | TV Receiver (`:8085`) | Sends 6 rapid invalid pairing attempts from single IP within 1 minute. | Attempts 1–5 receive HTTP 401; Attempt 6 receives HTTP 429 Too Many Requests (15-min IP ban). |
| **ST-AUTH-007** | Credential Revocation on Disconnect | Both | TV clicks "Disconnect Phone" or Mobile stops server. | TV increments session ID, regenerates secret key; previous tokens receive HTTP 401. |

---

### 2.2 Network & Transport Layer Tests (`SEC-NET`)

| Test ID | Test Case | Target Application | Test Input / Vector | Expected Outcome |
|---|---|---|---|---|
| **ST-NET-001** | CORS Header Absence Verification | All Servers (8080, 8085) | Send HTTP `OPTIONS`, `GET /api/v1/library`, `GET /status`. | Response does NOT contain `Access-Control-Allow-Origin` or `Access-Control-*`. |
| **ST-NET-002** | Cross-Origin Browser Fetch Block | All Servers | Malicious web page on LAN invokes `fetch('http://192.168.1.36:8080/api/v1/library')`. | Web browser enforces Same-Origin Policy; JavaScript cannot read library data. |
| **ST-NET-003** | Unauthenticated Control Injection | TV Receiver (`:8085`) | Curl command on LAN sends `POST /control {"action":"STOP"}` without auth headers. | HTTP 401 Unauthorized; player continues playback uninterrupted. |
| **ST-NET-004** | Unauthenticated Stream Harvesting | Mobile Server (`:8080`) | Third-party device requests `GET /stream/7b8f9e2a` without signed capability token. | HTTP 401 Unauthorized; video stream refused. |

---

### 2.3 Filesystem & Sandboxing Tests (`SEC-SANDBOX`)

| Test ID | Test Case | Target Application | Test Input / Vector | Expected Outcome |
|---|---|---|---|---|
| **ST-FILE-001** | Path Traversal via Media ID | Mobile Server (`:8080`) | `GET /stream/..%2f..%2f..%2fetc%2fpasswd` | HTTP 400 Bad Request (`Invalid media ID format`). |
| **ST-FILE-002** | Path Traversal in Thumbnail Route | Mobile Server (`:8080`) | `GET /api/v1/thumbnail/..%2f..%2fshared_prefs%2fkeys.xml` | HTTP 400 Bad Request (`Invalid media ID format`). |
| **ST-FILE-003** | Arbitrary Unapproved URI Access | Mobile Server (`:8080`) | Construct media ID resolving to unapproved system file. | Repository lookup returns null; returns HTTP 404 Not Found. |
| **ST-FILE-004** | Excluded Media Persistence | Mobile Storage | Remove item; re-scan parent folder. | Item remains excluded in `mediaCache` and does not re-appear. |

---

### 2.4 Credential Storage & Secret Leakage Tests (`SEC-STORE`)

| Test ID | Test Case | Target Application | Test Input / Vector | Expected Outcome |
|---|---|---|---|---|
| **ST-SECR-001** | Android Keystore Encryption | Mobile (`apps/mobile`) | Inspect root private storage XML files (`/data/data/com.pocketstream.mobile/`). | `tvSecretKey` and credentials are stored ciphertext-only under `EncryptedSharedPreferences`. |
| **ST-SECR-002** | iOS Keychain Attribute Check | iOS Client | Query Keychain item attributes. | Stored with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; excluded from iCloud backups. |
| **ST-SECR-003** | Logcat / Console Secret Leaks | All Apps | Run full pairing and streaming cycle; grep logs for secret keys. | Zero secret keys, PINs, or raw signatures present in logs or debug toasts. |
| **ST-SECR-004** | Git Secret Commit Gate | CI / Pre-commit | Run `gitleaks` or `git log -S "password"`. | Zero credentials, `.jks`, or `.keystore` files committed. |

---

## 3. Test Execution Automation Script Harness

Automated tests will be packaged under:
- `apps/mobile/src/test/java/com/pocketstream/mobile/security/SecurityIntegrationTest.kt`
- `apps/tv/src/test/java/com/pocketstream/tv/security/TvReceiverSecurityTest.kt`
- `shared/protocol/src/test/java/com/pocketstream/protocol/HmacSignatureTest.kt`

Using JUnit 5, MockWebServer, and OkHttp to simulate LAN attacker vectors programmatically.
