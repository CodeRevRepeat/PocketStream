# PocketStream Technology Stack & Dependencies

## 1. Toolchains, Platforms & Build Systems

### 1.1 Android Applications (`apps/mobile` & `apps/tv`)
- **Build System**: Gradle 8.5 (Wrapper: `gradle-8.5-bin.zip`)
- **Android Gradle Plugin (AGP)**: `8.5.2`
- **Programming Language**: Kotlin `1.9.24` (Kotlin JVM & Android plugins)
- **Java Compatibility**: OpenJDK 17 (`sourceCompatibility = JavaVersion.VERSION_17`, `targetCompatibility = JavaVersion.VERSION_17`)
- **Kotlin JVM Target**: `17`
- **SDK Versions**:
  - `compileSdk`: 34 (Android 14)
  - `minSdk`: 26 (Android 8.0 Oreo)
  - `targetSdk`: 34 (Android 14)
- **NDK / Build Tools**: Standard Android SDK Build-Tools 34.0.0
- **Build Features**: Android ViewBinding enabled (`viewBinding true`)

### 1.2 iOS Application (`ios/PocketStream`)
- **Build System**: Xcode Build System (`PocketStream.xcodeproj`)
- **Programming Language**: Swift 5.9
- **Deployment Target**: iOS 16.0+
- **Architecture**: arm64 (iPhone / iPad)
- **UI Framework**: SwiftUI & Combine
- **Networking Framework**: Apple `Network.framework` (`NWListener`, `NWConnection`, `NWParameters`)
- **Media Framework**: `AVFoundation` (`AVAsset`, `AVAssetExportSession`, `AVAssetReader`, `AVAssetWriter`, `AVAssetImageGenerator`)

### 1.3 Shared Protocol (`shared/protocol`)
- **Platform**: Pure Kotlin/JVM library
- **Plugin**: `org.jetbrains.kotlin.jvm` version `1.9.24`
- **Target**: JVM 17 bytecode
- **Dependencies**: Minimal (only `org.json:json:20231013` for lightweight serialization)

---

## 2. Bill of Materials (Dependencies Breakdown)

### 2.1 Android Mobile (`apps/mobile/build.gradle`)
| Dependency | Version | Purpose |
|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | Kotlin extensions for Android Core |
| `androidx.appcompat:appcompat` | 1.6.1 | Backward-compatible Android UI components |
| `com.google.android.material:material` | 1.11.0 | Material Design design tokens and dialog components |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | Constraint-based responsive UI layouts |
| `androidx.recyclerview:recyclerview` | 1.3.2 | Media library scrolling list |
| `androidx.documentfile:documentfile` | 1.0.1 | Storage Access Framework tree scanning |
| `org.nanohttpd:nanohttpd` | 2.3.1 | Lightweight embedded HTTP server (Port 8080) |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP client for remote control and TV communication |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | Asynchronous background processing |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.7.0 | Lifecycle-aware coroutine scopes |
| `androidx.media3:media3-transformer` | 1.3.1 | Hardware-accelerated video transcoding engine |
| `androidx.media3:media3-common` | 1.3.1 | Shared media formats and clipping configuration |
| `androidx.media3:media3-effect` | 1.3.1 | Frame processing and tone-mapping effects |
| `com.journeyapps:zxing-android-embedded` | 4.3.0 | Camera-based TV QR code scanner |
| `project(':shared:protocol')` | Local | Shared data transfer models |

### 2.2 Android TV (`apps/tv/build.gradle`)
| Dependency | Version | Purpose |
|---|---|---|
| `androidx.core:core-ktx` | 1.12.0 | Kotlin extensions for Android Core |
| `androidx.appcompat:appcompat` | 1.6.1 | Base UI components |
| `androidx.recyclerview:recyclerview` | 1.3.2 | 4-column TV media grid (`GridLayoutManager`) |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | TV layout structure |
| `org.nanohttpd:nanohttpd` | 2.3.1 | Embedded TV receiver HTTP server (Port 8085) |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Media polling and thumbnail fetching |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | Asynchronous background processing |
| `androidx.media3:media3-exoplayer` | 1.3.1 | Hardware-accelerated media player engine |
| `androidx.media3:media3-ui` | 1.3.1 | ExoPlayer player view and controls |
| `androidx.media3:media3-common` | 1.3.1 | Common media interfaces |
| `com.google.zxing:core` | 3.5.3 | QR code bitmap matrix generation |
| `project(':shared:protocol')` | Local | Shared data transfer models |

### 2.3 iOS Dependencies (`ios/PocketStream`)
- **Third-Party Packages**: Zero external dependencies (zero CocoaPods, Carthage, or Swift Package Manager third-party dependencies).
- **First-Party Apple Frameworks**:
  - `SwiftUI`: User interface layout and views.
  - `Network`: `NWListener` and `NWConnection` for HTTP server and UDP broadcast.
  - `AVFoundation`: Media playback, hardware encoding, and camera QR scanning.
  - `CoreImage`: Dynamic QR code generation via `CIQRCodeGenerator`.
  - `UniformTypeIdentifiers`: Scoped file importer MIME/type handling.

---

## 3. Production Artifacts & Output Formats
- **Android Mobile APK**: `apps/mobile/build/outputs/apk/release/mobile-release.apk` (or `debug/mobile-debug.apk`)
- **Android TV APK**: `apps/tv/build/outputs/apk/release/tv-release.apk` (or `debug/tv-debug.apk`)
- **iOS Package**: `ios/PocketStream.ipa` (Archive export for sideloading / TestFlight)
