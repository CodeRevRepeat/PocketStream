# PocketStream Network Protocol Specification

## 1. Network Topology & Port Allocations

PocketStream nodes communicate exclusively over the local Wi-Fi network across four network services:

| Port | Transport | Service Name | Host Role | Description |
|---|---|---|---|---|
| **8080** | TCP (HTTP/1.1) | PocketStream Media Server | Phone (Android/iOS) | Media file streaming, thumbnail delivery, and server REST API |
| **8085** | TCP (HTTP/1.1) | PocketStream TV Receiver | TV (Android TV) | Receiver playback control, pairing, status queries |
| **8088** | UDP (Broadcast) | PocketStream Discovery | Both | Local subnet announcement and peer probing |
| **5353** | UDP (Multicast) | mDNS / Bonjour | Both | Zero-configuration DNS service discovery |

---

## 2. TV Discovery Protocols

### 2.1 mDNS / DNS-SD (Bonjour)
- **Phone Service Type**: `_pocketstream-phone._tcp.` on port 8080.
- **TV Service Type**: `_pocketstream-tv._tcp.` on port 8085.
- **Attributes / TXT Record**: Contains device model, software version, and host identifier.

### 2.2 UDP Subnet Broadcast (Port 8088)
Clients broadcast UTF-8 encoded JSON packets to the subnet broadcast address (e.g., `255.255.255.255` or interface broadcast).

#### Message: `DISCOVER_TV` (Phone -> Subnet)
Dispatched by phone upon startup or when initiating discovery:
```json
{
  "type": "DISCOVER_TV",
  "phoneIp": "192.168.1.36",
  "phonePort": 8080,
  "deviceName": "Google Pixel 8 Pro / PocketStream"
}
```

#### Message: `TV_ANNOUNCE` (TV -> Subnet / Phone)
Dispatched by TV periodically or in direct response to `DISCOVER_TV`:
```json
{
  "type": "TV_ANNOUNCE",
  "tvIp": "192.168.1.35",
  "tvPort": 8085,
  "deviceName": "TCL BeyondTV"
}
```

#### Message: `TV_BUSY` (TV -> Phone)
Dispatched by TV if a discovery probe is received while an active connection is already bonded to another phone:
```json
{
  "type": "TV_BUSY",
  "tvIp": "192.168.1.35",
  "tvPort": 8085,
  "deviceName": "TCL BeyondTV"
}
```

#### Message: `PHONE_DISCONNECT` (Phone -> Subnet)
Dispatched by phone when the media server is manually stopped or the app is terminated:
```json
{
  "type": "PHONE_DISCONNECT",
  "phoneIp": "192.168.1.36"
}
```

### 2.3 QR Code URI Scheme (Optical PSK Pairing)
The TV renders a dynamic QR code encoding the v2 connection URI:
```
pocketstream://connect?ip=<tv_ip>&port=8085&name=<url_encoded_tv_name>&sid=<ephemeral_session_id>&key=<ephemeral_session_key>
```
**Parameters**:
- `ip`: IPv4 address of the TV receiver on the local Wi-Fi subnet.
- `port`: TCP receiver port (default: `8085`).
- `name`: URL-encoded display name of the TV (e.g. `TCL%20BeyondTV`).
- `sid`: Ephemeral 16-character alphanumeric session identifier generated per TV startup/session.
- `key`: Ephemeral 256-bit URL-safe base64 pre-shared key (PSK) displayed visually only on the physical TV screen via QR.

---

## 3. Mutual Authentication & Request Signing (Option C)

All TV receiver control endpoints (`/pair`, `/play`, `/control`, `/disconnect`, `/refresh`) require cryptographic HMAC-SHA256 request signing:

### 3.1 Authentication Headers
```http
X-PS-Session: <ephemeral_session_id>
X-PS-Timestamp: <current_epoch_seconds>
X-PS-Nonce: <cryptographic_random_nonce>
X-PS-Signature: <hmac_sha256_hex_digest>
```

### 3.2 Canonical Signature Payload
The HMAC-SHA256 signature is calculated over the newline-delimited canonical string:
```
<UPPERCASE_HTTP_METHOD>\n
<REQUEST_PATH>\n
<TIMESTAMP_EPOCH_SECONDS>\n
<NONCE>\n
<SHA256_HEX_OF_REQUEST_BODY>
```
*For requests with an empty body (or GET/HEAD), `<SHA256_HEX_OF_REQUEST_BODY>` is an empty string `""`.*

### 3.3 Security & Replay Protections
- **Timestamp Skew Limit**: Max 30 seconds (`|current_epoch - timestamp| <= 30L`). Out-of-window requests return HTTP 401 Unauthorized.
- **Nonce Replay Prevention**: TV maintains an in-memory sliding window cache of recent nonces. Duplicate nonces within the valid time window are rejected with HTTP 401 Unauthorized.
- **Brute-Force Rate Lockout**: TV tracks failed signature attempts per IP. After 5 consecutive invalid signatures, the client is locked out for 60 seconds (HTTP 429 Too Many Requests).
- **Session Revocation**: User can disconnect at any time on TV, regenerating a new `sid` and `key`, instantly invalidating any prior paired devices.

---

## 4. TV Receiver Server Endpoints (Port 8085)

*Wildcard CORS headers have been completely eliminated. Direct API clients (Android / iOS native apps) communicate via socket endpoints. OPTIONS requests return HTTP 405 Method Not Allowed.*

### 3.1 `POST /pair`
Initiates a 1:1 bonding session between the phone and TV receiver.

- **Request Body**:
  ```json
  {
    "phoneIp": "192.168.1.36",
    "phonePort": 8080,
    "deviceName": "Google Pixel 8 Pro"
  }
  ```
- **Responses**:
  - `200 OK`: Pairing successful.
    ```json
    {
      "status": "paired",
      "tvName": "TCL BeyondTV",
      "capabilities": {
        "deviceModel": "TCL BeyondTV",
        "maxWidth": 1920,
        "maxHeight": 1088,
        "maxFps": 60,
        "supports10BitHevc": false,
        "supportsHdr": false,
        "supports4K": false,
        "supportedVideoMimes": [
          "video/avc",
          "video/hevc",
          "video/mp4v-es",
          "video/x-vnd.on2.vp9"
        ],
        "hardwareDecoders": [
          "OMX.realtek.video.decoder"
        ]
      }
    }
    ```
  - `409 Conflict`: TV is already bonded to another phone IP.
    ```json
    {
      "status": "busy",
      "message": "TV is already connected to another device (192.168.1.40)"
    }
    ```

### 3.2 `GET /capabilities`
Returns TV hardware decoder specifications and limits without modifying pairing state.
- **Response `200 OK`**: Returns `TvCapabilities` JSON object.

### 3.3 `POST /disconnect`
Instructs TV to reset its bonded state.
- **Access Rule**: Only permitted if the requesting IP matches the bonded phone IP or if TV is unbonded.
- **Response**:
  - `200 OK`: `{"status": "disconnected"}`
  - `403 Forbidden`: `{"status": "forbidden"}` (if IP does not match bonded device).

### 3.4 `POST /refresh`
Triggers TV to re-fetch `/api/v1/library` from the connected phone.
- **Response `200 OK`**: `{"status": "refreshed"}`

### 3.5 `POST /play`
Commands TV receiver to load and play a specified stream URL.
- **Request Body**:
  ```json
  {
    "streamUrl": "http://192.168.1.36:8080/stream/a1b2c3d4",
    "title": "Movie Title (2025)",
    "durationMs": 7200000,
    "startPositionMs": 0
  }
  ```
- **Responses**:
  - `200 OK`: `{"status": "ok"}`
  - `409 Conflict`: Rejected if client IP is not the bonded phone.

### 3.6 `POST /control`
Dispatches playback commands to TV receiver.
- **Request Body**:
  ```json
  {
    "action": "PLAY | PAUSE | RW | FF | RESTART | STOP | SEEK | PREPARING_SEEK",
    "positionMs": 0
  }
  ```
- **Actions**:
  - `PLAY`: Resumes paused playback.
  - `PAUSE`: Pauses active playback.
  - `RW`: Rewinds by 10,000 ms.
  - `FF`: Fast forwards by 10,000 ms.
  - `RESTART`: Seeks to position 0 ms.
  - `STOP`: Terminates player and returns to TV library home screen.
  - `SEEK`: Direct seek to `positionMs`.
  - `PREPARING_SEEK`: Pauses player and triggers 15/30s TV countdown overlay for live transcode preparation.

### 3.7 `GET /status`
Returns real-time playback telemetry.
- **Response `200 OK`**:
  ```json
  {
    "isPlaying": true,
    "positionMs": 142300,
    "durationMs": 7200000
  }
  ```

---

## 4. Mobile Media Server Endpoints (Port 8080)

All responses include standard CORS headers and support HTTP HEAD requests.

### 4.1 `GET /api/v1/status`
Operational health check.
```json
{
  "isRunning": true,
  "host": "192.168.1.36",
  "port": 8080,
  "deviceName": "Google Pixel 8 Pro / PocketStream",
  "version": "1.2.0"
}
```

### 4.2 `GET /api/v1/library`
Returns the array of available `MediaItem` records.
```json
[
  {
    "id": "7b8f9e2a",
    "title": "Documentary.mkv",
    "sizeBytes": 2948576000,
    "mimeType": "video/x-matroska",
    "durationMs": 5400000,
    "width": 1920,
    "height": 1080,
    "videoCodec": "hevc",
    "audioCodec": "aac",
    "streamPath": "/stream/7b8f9e2a",
    "thumbnailPath": "/api/v1/thumbnail/7b8f9e2a"
  }
]
```

### 4.3 `GET /api/v1/thumbnail/{id}`
Returns extracted video frame image (JPEG format, extracted at 7% timeline position).
- Headers: `Content-Type: image/jpeg`, `Cache-Control: public, max-age=86400`.

### 4.4 `GET /stream/{id}`
Direct streaming of original video file.
- Supports HTTP `Range: bytes=start-end`.
- Responses:
  - `206 Partial Content`: Includes `Content-Range: bytes start-end/total` and `Accept-Ranges: bytes`.
  - `200 OK`: Full file stream when no Range header is supplied.

### 4.5 `GET /stream/live/{id}`
Live streaming of active fragmented MP4 hardware transcode output.
- Chunked transfer encoding.
- Header: `Accept-Ranges: none`.

### 4.6 `GET /stream/transcoded/{id}`
Static streaming of completed transcoded file.
- Progressive MP4 with `moov` at front; supports HTTP 206 Range seeking.

### 4.7 `POST /api/v1/play`
Called by TV when user selects an item on TV grid:
- Payload: `{"mediaId": "7b8f9e2a"}`
- Response: Returns Direct Play mode and stream URL.

### 4.8 `POST /api/v1/stop`
Notifies phone that playback has stopped, updating notification and UI.
