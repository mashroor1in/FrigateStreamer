# Frigate Stream

<p align="center">
  <b>Turn your old or rooted Android phones into low-latency IP cameras that push directly to Frigate NVR.</b>
</p>

---

Frigate Stream is a lightweight, background-friendly Android application designed to repurpose old devices (Android 11 to 13+) as smart security cameras. By utilizing **RTSP push (ANNOUNCE mode)**, the app streams H.264 video and AAC audio directly to Frigate's built-in `go2rtc` server without requiring complex port forwarding or open inbound ports on your phone.

---

## Key Features

*   **Header-less Background Streaming**: Runs completely previewless using native `Camera2` virtual textures. Screen off? App in background? The stream continues seamlessly.
*   **Foreground Service Pipeline**: Runs as a persistent foreground service with a notification displaying live bitrate and uptime.
*   **Dynamic Lens Switching**: Toggle between Front and Rear camera lenses **mid-stream** without dropping the RTSP socket connection.
*   **Auto-Start on Boot**: Optional toggle to launch and start streaming automatically when the phone restarts.
*   **Battery & Performance Optimized**: Adjustable resolutions (480p, 720p, 1080p), FPS limits, and bitrates to prevent battery bloat and keep CPU usage low.
*   **Premium Modern Interface**: Dark theme Jetpack Compose UI with real-time connection status badges, pulsing start button, and live telemetry data.

---

## Getting Started

### 1. Compile the APK
Import the project into **Android Studio**, build the project, and output the debug APK:
```bash
# Output location: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug
```

### 2. Configure your Frigate Server (`config.yml`)
Add the camera endpoints to your Frigate configuration so `go2rtc` is prepared to receive the incoming RTSP streams:

```yaml
go2rtc:
  streams:
    phone_ps1: []  # Tells go2rtc to accept incoming RTSP push streams
    phone_ps2: []
    phone_ps3: []

cameras:
  phone_ps1:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/phone_ps1
          roles:
            - detect
            - record
    detect:
      enabled: true
      width: 1280
      height: 720
      fps: 15
```
*Note: Make sure port **`8554`** is mapped out of your Docker container in your docker-compose file.*

### 3. Setup the App
1. Install the APK on your device:
   ```bash
   adb install app-debug.apk
   ```
2. Grant **Camera** and **Microphone** permissions.
3. Exemption from battery saving: Navigate to **Battery settings** on your device and set Frigate Stream to **Unrestricted** (or tap the in-app shortcut row).
4. Enter the Frigate Server IP (e.g. `192.168.31.106`) and the RTSP port (`8554`).
5. Choose your target **Lens** and **Resolution**, then tap **START STREAM**.

---

## Optimizing CPU Usage on Your Server

If you notice high FFmpeg CPU usage on your Frigate server:
1. **Reduce FPS**: Slide the FPS slider in the app down to **`5`** or **`10`**. A lower FPS is highly recommended for security object detection and uses 66% less processing power than 15/30 FPS.
2. **Reduce Resolution**: Select **`480p SD`** (`640x480`) in the app. Lower resolutions require significantly fewer CPU cycles to decode.
3. **Enable Hardware Acceleration**: Add hardware acceleration presets in your Frigate `config.yml` (e.g., `preset-vaapi` for Intel CPUs or `preset-rpi-64-h264` for Raspberry Pi).

---

## Tech Stack

*   **UI**: Jetpack Compose (Material 3)
*   **Database**: Android DataStore Preferences
*   **Camera Pipeline**: Camera2 API via RootEncoder (`pedroSG94/RootEncoder`)
*   **Video Encoder**: MediaCodec H.264 (Hardware Accelerated)
*   **Audio Encoder**: MediaCodec AAC-LC
*   **Min SDK**: 30 (Android 11)
*   **Target SDK**: 34 (Android 13/14)

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
