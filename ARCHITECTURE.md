# Architecture Guide — Frigate Stream

This document outlines the technical design, pipeline, and background lifecycle strategies used in the Frigate Stream Android application.

---

## System Overview

Frigate Stream is designed to act as an **RTSP push client** that operates persistently in the background. Instead of running a heavy local RTSP server on the phone and requiring the home server to pull the stream (which fails behind strict firewalls/NATs), the phone establishes an outbound connection to Frigate's built-in `go2rtc` media server and announces its stream.

```
+───────────────────────────+
|      Android Phone        |
|  +─────────────────────+  |
|  |   MainActivity      |  |
|  |  (Jetpack Compose)  |  |
|  +──────────┬──────────+  |
|             │ Intent      |
|  +──────────▼──────────+  |
|  |     StreamService   |  |          RTSP push
|  |  (ForegroundService)|──┼──────────────────────────────┐
|  |   * Camera2 API     |  | (Announce / H.264 + AAC)     |
|  |   * MediaCodec      |  |                              |
|  +─────────────────────+  |                              |
+───────────────────────────+                              |
                                                           ▼
+──────────────────────────────────────────────────────────────────────+
|                           Home Server                                |
|   +──────────────────────────────────────────────────────────────+   |
|   |                       go2rtc (Docker)                        |   |
|   |                  (Accepts stream on :8554)                   |   |
|   +──────────────────────────────┬───────────────────────────────+   |
|                                  │ internal restream                 |
|   +──────────────────────────────▼───────────────────────────────+   |
|   |                      Frigate NVR Dashboard                   |   |
|   |                       (Detect + Record)                      |   |
|   +──────────────────────────────────────────────────────────────+   |
+──────────────────────────────────────────────────────────────────────+
```

---

## Core Components

### 1. Foreground Service (`StreamService`)
To keep the camera stream active when the phone screen is turned off or when the app is in the background, the streaming pipeline runs inside a **Foreground Service** (`StreamService`). 
*   **Permissions**: Declares `FOREGROUND_SERVICE_CAMERA` and `FOREGROUND_SERVICE_MICROPHONE` types to comply with Android 11 (API 30) through Android 13+ background capture guidelines.
*   **Wakelocks**: Holds a `PARTIAL_WAKE_LOCK` to prevent the device's CPU from entering low-power sleep mode, ensuring steady frame rates and zero network drops.

### 2. Previewless Background Streaming
Historically, camera streaming libraries required a visible `SurfaceView` or `TextureView` on screen.
*   Frigate Stream leverages `pedroSG94/RootEncoder`'s context-based constructor:
    ```kotlin
    val camera = RtspCamera2(applicationContext, connectChecker)
    ```
*   This constructor initializes the `Camera2` capture session using a headless virtual surface. 
*   This eliminates the need for draw-over-other-apps permissions (`SYSTEM_ALERT_WINDOW`) or transparent 1x1 overlay window hacks.

### 3. Dynamic Camera Lens Switching
During streaming, users can toggle between the Front and Rear cameras.
*   When a lens button is tapped in the UI, `MainActivity` updates the config and fires a `SWITCH_CAMERA` action intent to `StreamService`.
*   The service delegates this to `rtspCamera2.switchCamera()`.
*   The transition is handled on-the-fly by rebuilding the camera session under the hood without breaking the underlying RTSP socket connection.

### 4. Zero-Latency UI State
To prevent typing lag in settings text fields:
*   State values are stored in a local in-memory `MutableStateFlow` cache within `StreamViewModel`.
*   Updates are sent synchronously to Compose views for instant 60 FPS text rendering.
*   Writes to Android's asynchronous `DataStore` run concurrently on a background coroutine thread, avoiding disk-thrashing blockages.

### 5. Boot Auto-Start (`BootReceiver`)
*   An `ACTION_BOOT_COMPLETED` BroadcastReceiver intercepts system restarts.
*   It invokes the asynchronous `goAsync()` API to spawn a temporary coroutine context.
*   It reads user preferences from the local `DataStore` database and, if auto-start is enabled and a valid configuration exists, boots up the `StreamService` directly.
