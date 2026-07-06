package com.frigatestream

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtsp.RtspCamera2
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ForegroundService that manages the RTSP push stream.
 *
 * Architecture:
 *   • Creates a 1×1 transparent overlay window (TYPE_APPLICATION_OVERLAY)
 *     so RtspCamera2 (which needs an OpenGlView/SurfaceView) can attach
 *     the camera2 capture session — no UI required, works off‑screen.
 *   • Uses RootEncoder's RtspCamera2 which wraps Camera2 + MediaCodec +
 *     RTSP ANNOUNCE/RECORD push into one class.
 *   • Auto‑reconnects on disconnect / connection failure.
 *   • Broadcasts status updates (bitrate, uptime) to the ViewModel every 2 s.
 */
class StreamService : Service() {

    // ─── Intent Actions ────────────────────────────────────────────────────────
    companion object {
        const val ACTION_START           = "com.frigatestream.action.START"
        const val ACTION_STOP            = "com.frigatestream.action.STOP"
        const val ACTION_SWITCH_CAMERA   = "com.frigatestream.action.SWITCH_CAMERA"
        const val ACTION_STREAM_STATUS   = "com.frigatestream.action.STATUS"

        // Broadcast extras
        const val EXTRA_STATUS              = "status"
        const val EXTRA_BITRATE_KBPS        = "bitrate_kbps"
        const val EXTRA_UPTIME              = "uptime"
        const val EXTRA_ERROR               = "error"
        const val EXTRA_RECONNECT_ATTEMPT   = "reconnect_attempt"

        // Status values
        const val STATUS_CONNECTING   = "connecting"
        const val STATUS_STREAMING    = "streaming"
        const val STATUS_RECONNECTING = "reconnecting"
        const val STATUS_ERROR        = "error"
        const val STATUS_STOPPED      = "stopped"

        // Config extras (from ViewModel → service)
        const val EXTRA_CONFIG_IP         = "cfg_ip"
        const val EXTRA_CONFIG_PORT       = "cfg_port"
        const val EXTRA_CONFIG_NAME       = "cfg_name"
        const val EXTRA_CONFIG_USER       = "cfg_user"
        const val EXTRA_CONFIG_PASS       = "cfg_pass"
        const val EXTRA_CONFIG_CAMERA     = "cfg_camera"
        const val EXTRA_CONFIG_WIDTH      = "cfg_width"
        const val EXTRA_CONFIG_HEIGHT     = "cfg_height"
        const val EXTRA_CONFIG_FPS        = "cfg_fps"
        const val EXTRA_CONFIG_VBITRATE   = "cfg_vbitrate"
        const val EXTRA_CONFIG_ABITRATE   = "cfg_abitrate"
        const val EXTRA_CONFIG_SAMPLERATE = "cfg_samplerate"

        private const val CHANNEL_ID      = "frigate_stream_ch"
        private const val NOTIF_ID        = 1001
        private const val MAX_RECONNECTS  = 20          // give up after 20 tries
        private const val RECONNECT_DELAY = 5_000L      // 5 s between retries
    }

    // ─── State ─────────────────────────────────────────────────────────────────
    private var rtspCamera2: RtspCamera2? = null
    private var currentConfig: StreamConfig? = null

    private val serviceScope       = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isIntentionallyStopped = AtomicBoolean(false)
    private val isCurrentlyStreaming   = AtomicBoolean(false)
    private val reconnectAttempt       = AtomicInteger(0)

    private var streamStartTime = 0L
    private var reconnectJob: Job? = null
    private var statsJob: Job? = null

    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FrigateStream::WakeLock")
    }

    // ─── ConnectChecker — RootEncoder callbacks ────────────────────────────────
    private val connectChecker = object : ConnectChecker {

        override fun onConnectionStarted(url: String) {
            broadcast(STATUS_CONNECTING)
            updateNotif("⏳ Connecting to go2rtc…")
        }

        override fun onConnectionSuccess() {
            isCurrentlyStreaming.set(true)
            reconnectAttempt.set(0)
            streamStartTime = System.currentTimeMillis()
            broadcast(STATUS_STREAMING)
            updateNotif("🟢 Live — pushing to Frigate")
            startStatsLoop()
        }

        override fun onConnectionFailed(reason: String) {
            isCurrentlyStreaming.set(false)
            if (!isIntentionallyStopped.get()) scheduleReconnect(reason)
        }

        override fun onDisconnect() {
            isCurrentlyStreaming.set(false)
            if (!isIntentionallyStopped.get()) scheduleReconnect("Disconnected")
        }

        override fun onAuthError() {
            isCurrentlyStreaming.set(false)
            broadcast(STATUS_ERROR, error = "Authentication failed — check username/password")
            updateNotif("❌ Auth error")
            // Don't reconnect on auth error — user needs to fix credentials
            stopSelf()
        }

        override fun onAuthSuccess() {
            // Handled via onConnectionSuccess
        }
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isIntentionallyStopped.set(false)
                reconnectAttempt.set(0)
                // Promote to foreground immediately
                startForeground(NOTIF_ID, buildNotification("⏳ Starting…"))

                serviceScope.launch {
                    val savedConfig = PreferencesManager(applicationContext).streamConfigFlow.first()
                    val config = if (intent != null && intent.hasExtra(EXTRA_CONFIG_IP)) {
                        extractConfig(intent)
                    } else {
                        savedConfig
                    }
                    currentConfig = config

                    if (config.isValid()) {
                        startStreaming(config)
                    } else {
                        updateNotif("❌ Error: Invalid configuration")
                        broadcast(STATUS_ERROR, error = "Cannot start stream: Configuration is invalid (IP or Stream Name is empty).")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP -> {
                isIntentionallyStopped.set(true)
                stopEverything()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SWITCH_CAMERA -> {
                try {
                    rtspCamera2?.switchCamera()
                } catch (e: Exception) {
                    broadcast(STATUS_ERROR, error = "Failed to switch camera: ${e.message}")
                }
            }
        }
        // START_STICKY: system will restart with a null intent after being killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
        serviceScope.cancel()
    }

    // ─── Streaming Setup ───────────────────────────────────────────────────────

    private fun startStreaming(config: StreamConfig) {
        serviceScope.launch {
            if (!wakeLock.isHeld) {
                wakeLock.acquire(12 * 60 * 60 * 1000L) // 12 h max
            }
            initRtspCamera2(config)
        }
    }

    private fun initRtspCamera2(config: StreamConfig) {
        try {
            val camera = RtspCamera2(applicationContext, connectChecker)
            rtspCamera2 = camera

            val facing = if (config.cameraFacing == StreamConfig.CAMERA_FRONT) {
                CameraHelper.Facing.FRONT
            } else {
                CameraHelper.Facing.BACK
            }

            camera.startPreview(facing)

            val videoOk = camera.prepareVideo(
                /* width     */ config.resolution.width,
                /* height    */ config.resolution.height,
                /* fps       */ config.fps,
                /* bitrate   */ config.videoBitrate,
                /* rotation  */ 0,
                /* camera    */ config.cameraFacing
            )
            val audioOk = camera.prepareAudio(
                /* bitrate      */ config.audioBitrate,
                /* sampleRate   */ config.audioSampleRate,
                /* stereo       */ true
            )

            if (videoOk && audioOk) {
                camera.startStream(config.getRtspUrl())
            } else {
                broadcast(STATUS_ERROR, error = "Encoder preparation failed — " +
                        "resolution or codec not supported on this device")
                stopSelf()
            }
        } catch (e: Exception) {
            broadcast(STATUS_ERROR, error = "Camera init failed: ${e.message}")
            stopSelf()
        }
    }

    // ─── Reconnect Logic ───────────────────────────────────────────────────────

    private fun scheduleReconnect(reason: String) {
        val attempt = reconnectAttempt.incrementAndGet()
        if (attempt > MAX_RECONNECTS) {
            broadcast(STATUS_ERROR, error = "Gave up after $MAX_RECONNECTS reconnects. Last reason: $reason")
            updateNotif("❌ Too many retries — tap to open app")
            stopSelf()
            return
        }

        broadcast(STATUS_RECONNECTING, reconnectAttempt = attempt)
        updateNotif("🔄 Reconnecting… (attempt $attempt/$MAX_RECONNECTS)")

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(RECONNECT_DELAY)
            val config = currentConfig ?: return@launch
            try {
                rtspCamera2?.stopStream()
                rtspCamera2?.startStream(config.getRtspUrl())
            } catch (e: Exception) {
                // connectChecker.onConnectionFailed will handle the next retry
            }
        }
    }

    // ─── Stats Loop — updates bitrate & uptime every 2 s ─────────────────────

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isCurrentlyStreaming.get() && !isIntentionallyStopped.get()) {
                delay(2_000)
                val kbps   = (rtspCamera2?.getBitrate() ?: 0) / 1000
                val uptime = formatUptime(System.currentTimeMillis() - streamStartTime)
                broadcast(STATUS_STREAMING, bitrateKbps = kbps.toInt(), uptime = uptime)
                updateNotif("🟢 ${kbps} kbps · $uptime")
            }
        }
    }

    // ─── Cleanup ───────────────────────────────────────────────────────────────

    private fun stopEverything() {
        statsJob?.cancel()
        reconnectJob?.cancel()
        isCurrentlyStreaming.set(false)

        runCatching {
            rtspCamera2?.stopStream()
            rtspCamera2?.stopPreview()
        }
        rtspCamera2 = null

        if (wakeLock.isHeld) wakeLock.release()
        broadcast(STATUS_STOPPED)
    }

    // ─── Broadcast ─────────────────────────────────────────────────────────────

    private fun broadcast(
        status: String,
        bitrateKbps: Int = 0,
        uptime: String = "",
        error: String? = null,
        reconnectAttempt: Int = 0
    ) {
        sendBroadcast(Intent(ACTION_STREAM_STATUS).apply {
            setPackage(packageName)          // restrict to our app only
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_BITRATE_KBPS, bitrateKbps)
            putExtra(EXTRA_UPTIME, uptime)
            error?.let { putExtra(EXTRA_ERROR, it) }
            putExtra(EXTRA_RECONNECT_ATTEMPT, reconnectAttempt)
        })
    }

    // ─── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Frigate Stream", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Live stream status"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openAppPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Frigate Stream")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openAppPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun extractConfig(intent: Intent) = StreamConfig(
        serverIp     = intent.getStringExtra(EXTRA_CONFIG_IP)   ?: "",
        serverPort   = intent.getIntExtra(EXTRA_CONFIG_PORT, 8554),
        streamName   = intent.getStringExtra(EXTRA_CONFIG_NAME) ?: "phone_camera",
        username     = intent.getStringExtra(EXTRA_CONFIG_USER) ?: "",
        password     = intent.getStringExtra(EXTRA_CONFIG_PASS) ?: "",
        cameraFacing = intent.getIntExtra(EXTRA_CONFIG_CAMERA, 0),
        resolution   = when (intent.getIntExtra(EXTRA_CONFIG_WIDTH, 1280)) {
            in 1920..Int.MAX_VALUE -> StreamConfig.Resolution.RES_1080P
            in 1280..1919          -> StreamConfig.Resolution.RES_720P
            else                   -> StreamConfig.Resolution.RES_480P
        },
        fps          = intent.getIntExtra(EXTRA_CONFIG_FPS, 15),
        videoBitrate = intent.getIntExtra(EXTRA_CONFIG_VBITRATE, 1_500_000),
        audioBitrate = intent.getIntExtra(EXTRA_CONFIG_ABITRATE, 128_000),
        audioSampleRate = intent.getIntExtra(EXTRA_CONFIG_SAMPLERATE, 44_100)
    )

    private fun formatUptime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600;  val m = (s % 3600) / 60;  val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec)
        else              "%02d:%02d".format(m, sec)
    }
}
