# Privacy Policy for PocketStream

**Last updated:** October 30, 2026

This Privacy Policy applies to the **PocketStream** mobile application (`com.pocketstream.mobile` / `com.coderevrepeat.pocketstream`) and the **PocketStream TV** application (`com.pocketstream.tv` / `com.coderevrepeat.pocketstream.tv`) developed by CodeRevRepeat.

PocketStream is committed to protecting your privacy. This policy explains what information is accessed by the application, how it is used, and confirms that **no personal data is collected, stored, or shared**.

---

## 1. Information Collection and Use

PocketStream is an open-source, local-network video streaming utility. **We do not collect, transmit, sell, or share any personal data, usage telemetry, or analytics.** All operations take place strictly on your local Wi-Fi network between your own devices.

### A. Media Files and Storage Access
- **Permission**: Read access to user-selected media and files (`READ_MEDIA_VIDEO`, `READ_EXTERNAL_STORAGE`, or Storage Access Framework).
- **Purpose**: PocketStream accesses videos solely to allow you to browse, play, and stream your personal video files directly from your phone to your TV.
- **Data Handling**: Your media files remain exclusively on your device and are never copied, uploaded, or sent to external cloud servers.

### B. Camera Access
- **Permission**: `CAMERA`
- **Purpose**: The camera is used solely to scan the pairing QR code displayed on your TV screen for instant connection.
- **Data Handling**: The camera stream is processed in real time on your device to read the local pairing code. No photos, videos, or camera recordings are ever captured, saved, or transmitted.

### C. Local Network and Wi-Fi Access
- **Permission**: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`
- **Purpose**: Used exclusively to discover your TV on the same local Wi-Fi network (LAN) and stream media directly between your phone and your TV over your local network.
- **Data Handling**: Network traffic is strictly local (peer-to-peer / LAN). No data passes through external proxy servers, remote relays, or third-party cloud infrastructure.

### D. Foreground Service and Notifications
- **Permission**: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`
- **Purpose**: Required by Android to keep the local streaming server and hardware encoder running reliably in the background while streaming a video to your TV, and to provide playback controls in your notification bar.

---

## 2. Third-Party Services and Analytics

- PocketStream contains **no advertising SDKs** (no ads).
- PocketStream contains **no tracking, behavioral analytics, or telemetry SDKs** (no Firebase Analytics, Google Analytics, Facebook SDK, or similar trackers).
- No personal identifiers, device identifiers, or IP addresses are collected or shared with any third party.

---

## 3. Data Storage and Cache

Any temporary transcoded files created by the hardware encoder for TV compatibility are stored entirely within the app's private local cache on your device. These files can be cleared at any time from within the app and are never uploaded or synced to external servers.

---

## 4. Children's Privacy

PocketStream does not address anyone under the age of 13. The application does not collect personally identifiable information from children or any other users.

---

## 5. Security

Because all communications occur strictly across your private local Wi-Fi network directly between your devices, your video content never traverses the public internet.

---

## 6. Open Source and Transparency

PocketStream is open-source software licensed under the GNU General Public License v3.0 (GPL-3.0). You can inspect the source code and verify our privacy practices at:
https://github.com/CodeRevRepeat/PocketStream

---

## 7. Contact Us

If you have any questions or suggestions regarding this Privacy Policy, please open an issue on our GitHub repository:
https://github.com/CodeRevRepeat/PocketStream/issues
