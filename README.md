# SRT Live Streamer (Android)

Android Application for high-quality SRT streaming with remote control capabilities via Web Console.

## Features
- **SRT Streaming:** Support for low latency video streaming via Secure Reliable Transport (SRT) protocol.
- **RTSP Ingest:** Ability to pull RTSP streams (e.g., from Drones or IP Cameras) and re-stream them.
- **Remote Control:** Integrated with Web Console for remote management:
  - Start/Stop Streaming
  - Adjust Bitrate, Resolution, and FPS
  - Switch Cameras
- **Auto-Update:** Built-in self-update mechanism synchronized with the server version.
- **Overlay System:** Dynamic graphic overlays for sports/events.

## Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- JDK 17
- Docker Streaming Stack (backend)

### Project Structure
- `app/`: Main Android application module.
- `.github/workflows/`: CI/CD pipelines.

### Configuration
To sign release builds, create `app/key.properties` with your keystore details:
```properties
storeFile=../release.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

### Build Instructions
**Debug Build:**
```bash
./gradlew assembleDebug
```

**Release Build:**
```bash
./gradlew assembleRelease
```
The output APK will be located at: `app/build/outputs/apk/release/app-release.apk`

## CI/CD & Automation
This project uses GitHub Actions to automate the release process.

### How to trigger a release:
The deployment workflow is triggered by **Git Tags**.
1. Update version in `app/build.gradle.kts`.
2. Commit changes.
3. Push a tag starting with `v`:
   ```bash
   git tag v2.0.1
   git push origin v2.0.1
   ```
4. **Action:** The workflow will:
   - Build the Release APK.
   - Upload it to the server.
   - Call the Web Console API to update the system version automatically.

## API Integration
The app communicates with the Web Console via:
- **Config:** MQTT Topics for real-time control.
- **Version Check:** `/api/version` (Proxied to Firebase).
