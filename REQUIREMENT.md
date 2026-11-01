# PocketStream Mobile Application Specification & Requirements

This document captures the complete architecture, protocol specifications, data models, workflows, and functional requirements of the PocketStream Mobile client (reference implementation: `apps/mobile`).

---

## 1. System Overview

PocketStream Mobile is a high-performance local network media streaming server and remote controller. It turns the phone into an on-device HTTP media streaming server capable of:
1. Serving local video files directly to Android TV / smart TV receivers over local Wi-Fi.
2. Performing real-time hardware video transcoding (e.g. converting 10-bit HDR/HEVC/MKV to 8-bit H.264/AAC MP4) when a TV receiver lacks hardware support for a given format.
3. Providing full remote control (play, pause, rewind, fast forward, seek, stop, timeline scrubber) synchronized with TV playback.
4. Auto-discovering TVs on the local network via mDNS (Bonjour) and UDP broadcast, as well as pairing via QR code scanning.

---

## 2. Network & Communication Protocols

### 2.1 Service Ports
| Service | Protocol | Port | Description |
|---|---|---|---|
| Mobile HTTP Server | TCP | 8080 | Embedded HTTP server for media streaming & API |
| TV Receiver Server | TCP | 8085 | TV HTTP server for playback commands & controls |
| TV/Phone Discovery | UDP | 8088 | UDP broadcast discovery packet exchange |
| mDNS / Bonjour (Phone) | UDP | 5353 | Service type: `_pocketstream-phone._tcp.` |
| mDNS / Bonjour (TV) | UDP | 5353 | Service type: `_pocketstream-tv._tcp.` |

### 2.2 TV Discovery Mechanisms
1. **mDNS / Bonjour (NSD):**
   - Phone registers service `PocketStream-Phone` under `_pocketstream-phone._tcp.` on port `8080`.
   - Phone browses for services under `_pocketstream-tv._tcp.`. When resolved, extracts IP, port (8085), and TV device model name.
2. **UDP Broadcast (Port 8088):**
   - Periodically (every 1.5s for 3 attempts, then every 5s up to 10 attempts) broadcasts JSON:
     ```json
     {
       "type": "DISCOVER_TV",
       "phoneIp": "<local_wifi_ip>",
       "phonePort": 8080,
       "deviceName": "<Manufacturer Model> / PocketStream"
     }
     ```
   - Listens on UDP port 8088 for incoming announcements:
     ```json
     {
       "type": "TV_ANNOUNCE",
       "tvIp": "<tv_ip>",
       "tvPort": 8085,
       "deviceName": "<tv_name>"
     }
     ```
   - Sends `{"type": "PHONE_DISCONNECT", "phoneIp": "<local_wifi_ip>"}` on shutdown.
3. **QR Code Pairing:**
   - TV displays a QR code containing URL scheme:
     `pocketstream://connect?ip=<tv_ip>&port=8085&name=<url_encoded_tv_name>`
   - Phone camera scans the QR code, parses query parameters, and connects instantly.
4. **Pairing Handshake:**
   - Phone posts to TV receiver at `POST http://<tv_ip>:8085/pair`:
     ```json
     {
       "phoneIp": "<local_wifi_ip>",
       "phonePort": 8080,
       "deviceName": "<phone_name>"
     }
     ```
   - TV responds with capabilities:
     ```json
     {
       "status": "paired",
       "tvName": "<tv_device_model>",
       "capabilities": {
         "deviceModel": "TCL BeyondTV",
         "maxWidth": 1920,
         "maxHeight": 1088,
         "maxFps": 60,
         "supports10BitHevc": false,
         "supportsHdr": false,
         "supports4K": false,
         "supportedVideoMimes": ["video/avc", "video/hevc", "video/mp4v-es", "video/x-vnd.on2.vp9"],
         "hardwareDecoders": ["OMX.realtek.video.decoder"]
       }
     }
     ```

---

## 3. Mobile HTTP Server Endpoints (`:8080`)

All responses include standard CORS headers (`Access-Control-Allow-Origin: *`, `Accept-Ranges`).

### 3.1 `GET /api/v1/status`
Returns server operational health.
```json
{
  "isRunning": true,
  "host": "192.168.1.36",
  "port": 8080,
  "deviceName": "iPhone 15 Pro / PocketStream",
  "version": "1.2.1"
}
```

### 3.2 `GET /api/v1/library`
Returns the list of media items added by the user.
```json
[
  {
    "id": "a1b2c3d4-...",
    "title": "Movie Title.mkv",
    "sizeBytes": 3124567890,
    "mimeType": "video/x-matroska",
    "durationMs": 7200000,
    "width": 1920,
    "height": 1080,
    "videoCodec": "hevc",
    "audioCodec": "aac",
    "streamPath": "/stream/a1b2c3d4-..."
  }
]
```

### 3.3 `GET /api/v1/transcode/status?id=<mediaId>`
Returns status of on-device hardware transcoding.
```json
{
  "id": "a1b2c3d4-...",
  "isTranscoding": true,
  "progress": 35,
  "isReady": false
}
```

### 3.4 `POST /api/v1/play`
Called by TV to request stream info for a media ID.
Payload: `{"mediaId": "<id>"}`
Response:
- **Direct Play:**
  ```json
  {
    "status": "ok",
    "mode": "direct",
    "streamUrl": "http://192.168.1.36:8080/stream/<id>",
    "title": "Movie Title",
    "durationMs": 7200000,
    "message": "Direct stream started."
  }
  ```
- **Transcode Mode:**
  ```json
  {
    "status": "ok",
    "mode": "transcode",
    "streamUrl": "http://192.168.1.36:8080/stream/live/<id>",
    "title": "[Converted] Movie Title",
    "durationMs": 7200000,
    "message": "Encoding using phone and started the stream"
  }
  ```

### 3.5 `POST /api/v1/stop`
Notifies phone that TV playback has stopped. Cancels active transcoding.

### 3.6 `POST /api/v1/transcode/seek`
Called when TV initiates a seek on an active live transcoded stream.
Payload: `{"mediaId": "<id>", "positionMs": 1800000}`
Response: `{"status": "seeking", "mediaId": "<id>", "positionMs": 1800000}`
Triggers phone to:
1. Restart hardware transcoder clipping starting from `positionMs`.
2. Display 30-second preparation countdown on phone.
3. After 30 seconds, dispatch `POST http://<tv_ip>:8085/play` with `startPositionMs = positionMs`.

### 3.7 `GET /stream/<id>`
Direct stream of original local media file.
- Supports HTTP `Range: bytes=start-end` headers (HTTP 206 Partial Content).
- Returns `Accept-Ranges: bytes` and `Content-Range: bytes start-end/total`.

### 3.8 `GET /stream/live/<id>?t=<timestamp>`
Live streaming of active fragmented MP4 hardware transcode output.
- Chunked transfer encoding (`Accept-Ranges: none`).
- Uses `GrowingFileInputStream` to serve chunks dynamically as the hardware encoder produces them.

### 3.9 `GET /stream/transcoded/<id>`
Static stream of completed transcoded file. Supports HTTP 206 Partial Content Range seeking.

---

## 4. Hardware Transcoding & Buffering Specification

### 4.1 Compatibility Detection
When a video is selected, its metadata is compared with `TvCapabilities`:
- If `is10Bit` (or filename contains `10bit`, `10-bit`, `hdr`) AND `!supports10BitHevc`: Incompatible.
- If resolution `width > maxWidth` or `height > maxHeight`: Incompatible.
- If codec is not supported by TV hardware decoder: Incompatible.

### 4.2 Incompatible Video Dialog
Prompts user:
- **"Encode & Stream (Recommended)"**: Starts live hardware transcode with 30s buffer.
- **"Direct Play Anyway"**: Sends raw stream directly to TV.
- **"Cancel"**: Dismisses.

### 4.3 30-Second Buffer Countdown
Before playback starts on an encoded stream:
1. Mobile app displays a modal preparing dialog:
   - Spinner animation.
   - Title: `"Preparing Video for TV"` or `"Seeking to 14:20..."`.
   - Subtitle: `"Rendering and buffering with hardware encoder to ensure smooth, stutter-free playback on your TV."`.
   - Countdown text: `"Starting stream in 30s..."` down to `"Starting stream in 1s..."`.
   - Progress bar: 0 to 30s elapsed.
   - Transcode percentage label: `"Hardware Transcode: N%"`.
   - `"Cancel"` button: Immediately cancels transcode and aborts streaming.
2. Hardware encoder transcode target:
   - Video: H.264 Baseline/High Profile Level 4.2, 1080p, 4.5 Mbps bitrate, 60 fps cap.
   - Audio: AAC stereo, 192 kbps.
   - Container: Fragmented MP4 (fMP4) with 1-second fragment chunks.
3. When seeking in an encoded stream (`startPositionMs > 0`):
   - Phone sends `POST http://<tv_ip>:8085/control` with `action: "PREPARING_SEEK", positionMs: <new_pos>`.
   - TV pauses and shows matching 30s countdown overlay.
   - Phone resets hardware encoder with start clipping position set to `<new_pos>`.
   - Both devices countdown 30 seconds synchronously.
   - When 30 seconds elapses, phone sends `POST http://<tv_ip>:8085/play` with `startPositionMs = <new_pos>` and stream starts immediately without stutter.

---

## 5. TV Control & Remote Synchronization

Phone controls TV via `POST http://<tv_ip>:8085/control`:
| Action | Payload | Behavior |
|---|---|---|
| `PLAY` | `{"action": "PLAY"}` | Resumes TV playback |
| `PAUSE` | `{"action": "PAUSE"}` | Pauses TV playback |
| `RW` | `{"action": "RW"}` | Rewinds TV playback 10 seconds |
| `FF` | `{"action": "FF"}` | Fast forwards TV playback 10 seconds |
| `RESTART` | `{"action": "RESTART"}` | Restarts playback from 00:00 |
| `STOP` | `{"action": "STOP"}` | Stops TV playback and returns to TV home |
| `SEEK` | `{"action": "SEEK", "positionMs": N}` | Direct seek to timeline position |
| `PREPARING_SEEK` | `{"action": "PREPARING_SEEK", "positionMs": N}` | Pauses TV and displays 30s buffer countdown |

### 5.1 Status Polling Loop
While playback is active, phone executes background polling every 1 second:
`GET http://<tv_ip>:8085/status` -> `{"isPlaying": bool, "positionMs": long, "durationMs": long}`
Updates:
- Scrubber position: `(positionMs / durationMs) * 1000`.
- Time display: `formatTime(positionMs) / formatTime(durationMs)`.
- Play/Pause button text and icon.
- Badge: `"TV PLAYING"` (green) or `"PAUSED"` (muted gray).
- If playback ended/idle (`!isPlaying && pos == 0 && len == 0`), hides Now Playing card.

---

## 6. User Interface & Screen Architecture

### 6.1 Theme & Styling
- Background: `#0B0F19` deep dark blue / black.
- Cards / Panels: `#151D30` dark slate with `#24324D` borders.
- Primary Accent: `#38BDF8` Cyan.
- Secondary Accent: `#818CF8` Indigo / Purple.
- Success Accent: `#22C55E` Green.
- Text Primary: `#F8FAFC` White; Text Secondary: `#94A3B8` Slate Gray.

### 6.2 Top Header
- App Title: **PocketStream** with cyan dot indicator.
- Subtitle: `"Local Wi-Fi Streaming & Transcoder"`.
- TV Target Card:
  - TV icon, TV Name (e.g. `"TCL BeyondTV"`), IP and Port (`"192.168.1.35:8085"`).
  - Online status badge: `"Connected"` (cyan/green) or `"Not Connected"` (red/gray).
  - `"Scan TV (QR)"` button with QR code icon.

### 6.3 Server Control Panel
- Server Status Badge: `"ONLINE"` (green) or `"STOPPED"` (gray) or `"STARTING..."`.
- Local IP & Port display (`"http://192.168.1.36:8080"`).
- Action Button: `"Start Server"` / `"Stop Server"` with indeterminate circular loading spinner during state transitions.

### 6.4 Media Library Management
- Toolbar Buttons:
  - `"Add Folder"`: Opens directory picker.
  - `"Add Videos"`: Opens media/document picker for multiple video files.
  - `"Select"` / `"Done"`: Toggles batch selection mode.
- Selection Action Bar (visible when selection active):
  - `"Select All"` / `"Deselect All"`.
  - `"Remove (N)"`: Deletes selected items from library with confirmation alert.
  - `"Cancel"`: Exits selection mode.
- Video List (Scrollable):
  - Video title.
  - Badges: Resolution (`"1080p"`, `"4K"`), Duration (`"01:45:22"`), File Size (`"2.4 GB"`), Format/Codec (`"MKV • HEVC 10-bit"`).
  - Tap Item:
    - If TV not paired: Prompts user to pair with TV.
    - If server not running: Auto-prompts to start server.
    - If compatible: Displays options: Direct Play, Live Encode & Stream, Preview, Copy URL.
    - If incompatible: Shows Incompatible Format Dialog.

### 6.5 Now Playing Remote Control Card (Bottom Anchor)
- Appears whenever TV playback is active.
- Title of currently playing video.
- Status badge: `"TV PLAYING"` or `"PAUSED"`.
- Interactive Scrubber: Seekbar showing current position vs total length.
- Time text: `00:14:22 / 01:45:00`.
- Control Buttons:
  - `[Restart]` (Jump to start)
  - `[ -10s ]` (Rewind 10s)
  - `[ Play / Pause ]` (Toggle playback)
  - `[ +10s ]` (Fast forward 10s)
  - `[ Stop ]` (Stop playback & hide remote)
