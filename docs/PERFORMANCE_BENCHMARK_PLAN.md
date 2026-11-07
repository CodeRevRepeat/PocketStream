# PocketStream Performance Benchmarking & Reliability Plan

## 1. Objectives & Principles
This plan defines the formal performance, reliability, and stress benchmarking framework for PocketStream.

> [!IMPORTANT]
> **Core Principle**: Do not optimize based on unverified assumptions. Every proposed optimization must target a measurable bottleneck with verifiable baseline metrics, clear benchmarking procedures, and objective pass/fail thresholds.

---

## 2. Measurable Bottlenecks & Benchmark Test Matrix

### 2.1 BM-01: `GrowingFileInputStream` CPU Polling vs. Event-Driven Signaling
- **Component**: `apps/mobile/src/main/java/com/pocketstream/mobile/server/GrowingFileInputStream.kt`
- **Current Behavior**: Loops with `Thread.sleep(25L)` waiting for new transcoded data. At 40 wakeups per second, this induces continuous CPU core wakeups and context switches on mobile SoCs.
- **Metric to Measure**:
  - Thread wakeups per second (`adb shell "cat /proc/[pid]/status | grep voluntary_ctxt_switches"`).
  - CPU usage during active waiting (`adb shell top -b -n 1 -p [pid]`).
- **Benchmark Procedure**:
  1. Start live transcoding of a 1080p 10-bit file.
  2. While the TV buffers, measure context switches and CPU consumption over 60 seconds on the stream reading thread.
- **Target Pass Threshold**:
  - Baseline (Current): ~40 context switches/sec per stream thread; ~3-5% CPU overhead solely in sleeping loop.
  - Remediated (Event-Driven Condition Variable): < 2 context switches/sec during data starvation; CPU overhead < 0.2%.

---

### 2.2 BM-02: `ParcelFileDescriptor` Lifecycle & Scrubbing Stress Test
- **Component**: `apps/mobile/src/main/java/com/pocketstream/mobile/server/PocketHttpServer.kt`
- **Current Behavior**: Opens PFD via `contentResolver.openFileDescriptor()`. Under rapid timeline scrubbing (rapid HTTP range requests that abort midway), PFDs may linger until GC finalization.
- **Metric to Measure**:
  - Open file descriptor count (`adb shell "ls -l /proc/[pid]/fd | wc -l"`).
- **Benchmark Procedure**:
  1. Script 50 consecutive seeks across a 2-hour video over 25 seconds via `POST /control {"action":"SEEK", "positionMs": ...}`.
  2. Monitor open file descriptors every 5 seconds for 2 minutes.
- **Target Pass Threshold**:
  - Peak open file descriptors must remain below 45 at all times.
  - Must return to baseline idle (< 30) within 3 seconds of scrubbing cessation.
  - Zero `EMFILE (Too many open files)` exceptions.

---

### 2.3 BM-03: Media3 Transformer Transcoding Throughput & Thermal Stability
- **Component**: `apps/mobile/src/main/java/com/pocketstream/mobile/transcoder/TranscoderManager.kt`
- **Current Behavior**: 14 Mbps VBR High Profile H.264 encoding with 60 FPS performance cap and hardware tone-mapping.
- **Metrics to Measure**:
  - Transcoding Speed Factor: Ratio of encoded media duration to elapsed real time ($\text{Speed} = \frac{\Delta \text{media\_duration}}{\Delta \text{wall\_time}}$).
  - Battery discharge rate (`adb shell dumpsys batterystats`).
  - Device SoC surface temperature (`adb shell dumpsys thermalservice`).
- **Benchmark Procedure**:
  1. Transcode a 20-minute 4K 10-bit HEVC (BT.2020) sample video on a mid-range Snapdragon device.
  2. Record speed factor every 60 seconds and battery discharge over 20 minutes.
- **Target Pass Threshold**:
  - Speed Factor: Must maintain $\ge 2.2\times$ real-time on Snapdragon 8 Gen 1/2/3, $\ge 1.4\times$ on mid-range SoCs (Snapdragon 778G / 7 Gen 1).
  - Thermal: Device battery/skin temperature must stay below 42°C.
  - Frame drop: Zero dropped frames during live playback on TV.

---

### 2.4 BM-04: TV RecyclerView D-Pad Scrolling & Thumbnail Memory Footprint
- **Component**: `apps/tv/src/main/java/com/pocketstream/tv/TvMediaAdapter.kt`
- **Current Behavior**: 4-column `GridLayoutManager` with 30 MB in-memory `LruCache` for thumbnails.
- **Metrics to Measure**:
  - UI Frame rendering time (p90, p95, p99 ms) and jank frame percentage via `adb shell dumpsys gfxinfo com.pocketstream.tv framestats`.
  - Process heap allocation (`adb shell dumpsys meminfo com.pocketstream.tv`).
- **Benchmark Procedure**:
  1. Populate library with 100 media items with thumbnails.
  2. Execute continuous rapid D-Pad Down / Up scrolling across 25 rows for 60 seconds.
- **Target Pass Threshold**:
  - Jank frames: $< 5.0\%$ of total rendered frames.
  - 95th percentile frame time: $< 16.6\text{ ms}$ (smooth 60 FPS rendering on 1080p / 4K displays).
  - Heap memory: Steady-state memory footprint $< 85\text{ MB}$; zero memory leaks or runaway bitmap allocation.

---

### 2.5 BM-05: iOS Streaming Engine Memory & Throughput (`BUG-STREAM-001`)
- **Component**: `ios/PocketStream/Server/PocketHttpServer.swift`
- **Current Behavior**: Synchronous `readData(ofLength:)` allocates entire requested range in memory.
- **Metrics to Measure**:
  - Peak Resident Memory (RSS) in Xcode Instruments (Allocations / VM Tracker).
  - Time-to-First-Frame (TTFF) on receiving TV player.
  - Sustained network throughput (Mbps) over Wi-Fi.
- **Benchmark Procedure**:
  1. Stream a 4.5 GB 1080p video file from iPhone to Android TV receiver.
  2. Monitor memory footprint in Xcode Instruments during initial start, 10 continuous seeks, and 30 minutes of playback.
- **Target Pass Threshold**:
  - Baseline (Buggy): Memory spikes to 2–4 GB on `bytes=0-` request, triggering immediate iOS OOM termination.
  - Remediated (Bounded Chunk Pump): Peak memory remains strictly $< 60\text{ MB}$ regardless of video file size (1 GB to 20 GB).
  - Sustained throughput: $\ge 45\text{ Mbps}$ on 5 GHz Wi-Fi.

---

### 2.6 BM-06: Continuous Playback Soak Test (Reliability & Memory Stability)
- **Component**: End-to-End System
- **Metric to Measure**: Playback continuity, memory leak slope, audio/video synchronization drift.
- **Benchmark Procedure**:
  1. Initiate continuous Direct Play session of a 2.5-hour movie.
  2. Verify playback does not stutter, freeze, or drift out of A/V sync.
  3. Verify phone background service (`ServerService`) remains active without OS kill.
- **Target Pass Threshold**:
  - Zero playback interruptions or crashes across 150 continuous minutes.
  - Memory slope: Delta between minute 10 and minute 150 must be $< 10\text{ MB}$ (zero progressive memory leakage).

---

## 3. Tooling & Automation Commands Reference

```powershell
# 1. Gfxinfo Jank Frame Profile (TV Grid)
adb -s <tv_ip>:5555 shell dumpsys gfxinfo com.pocketstream.tv reset
# (Perform 30s scrolling)
adb -s <tv_ip>:5555 shell dumpsys gfxinfo com.pocketstream.tv

# 2. Open File Descriptors Check (Mobile)
adb -s <phone_ip>:5555 shell "ls -l /proc/$(pidof com.pocketstream.mobile)/fd | wc -l"

# 3. Context Switches & CPU Thread Stats (Mobile)
adb -s <phone_ip>:5555 shell "cat /proc/$(pidof com.pocketstream.mobile)/status | grep -i ctxt"

# 4. Memory Heap Inspection
adb -s <phone_ip>:5555 shell dumpsys meminfo com.pocketstream.mobile
```
