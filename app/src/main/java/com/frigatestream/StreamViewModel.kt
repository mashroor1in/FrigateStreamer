package com.frigatestream

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ─── Stream State ──────────────────────────────────────────────────────────────

sealed class StreamState {
    object Idle : StreamState()
    object Connecting : StreamState()
    data class Streaming(
        val bitrateKbps: Int = 0,
        val uptime: String = "00:00"
    ) : StreamState()
    data class Reconnecting(val attempt: Int = 1) : StreamState()
    data class Error(val message: String) : StreamState()
}

// ─── ViewModel ─────────────────────────────────────────────────────────────────

class StreamViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesManager = PreferencesManager(application)

    // ── Config ─────────────────────────────────────────────────────────────────
    private val _streamConfig = MutableStateFlow(StreamConfig())
    val streamConfig: StateFlow<StreamConfig> = _streamConfig.asStateFlow()

    // ── Stream State ───────────────────────────────────────────────────────────
    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    // ─── Broadcast receiver — listens to StreamService status updates ──────────
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != StreamService.ACTION_STREAM_STATUS) return
            val status  = intent.getStringExtra(StreamService.EXTRA_STATUS) ?: return
            val bitrate = intent.getIntExtra(StreamService.EXTRA_BITRATE_KBPS, 0)
            val uptime  = intent.getStringExtra(StreamService.EXTRA_UPTIME) ?: "00:00"
            val error   = intent.getStringExtra(StreamService.EXTRA_ERROR)
            val attempt = intent.getIntExtra(StreamService.EXTRA_RECONNECT_ATTEMPT, 1)

            _streamState.value = when (status) {
                StreamService.STATUS_CONNECTING   -> StreamState.Connecting
                StreamService.STATUS_STREAMING    -> StreamState.Streaming(bitrate, uptime)
                StreamService.STATUS_RECONNECTING -> StreamState.Reconnecting(attempt)
                StreamService.STATUS_ERROR        -> StreamState.Error(error ?: "Unknown error")
                StreamService.STATUS_STOPPED      -> StreamState.Idle
                else -> StreamState.Idle
            }
        }
    }

    init {
        // Load initial config from DataStore once on startup
        viewModelScope.launch {
            preferencesManager.streamConfigFlow.firstOrNull()?.let {
                _streamConfig.value = it
            }
        }

        val filter = IntentFilter(StreamService.ACTION_STREAM_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            application.registerReceiver(statusReceiver, filter)
        }
    }

    // ─── Public Actions ────────────────────────────────────────────────────────

    fun saveConfig(config: StreamConfig) {
        _streamConfig.value = config
        viewModelScope.launch { preferencesManager.saveStreamConfig(config) }
    }

    fun startStream() {
        val config = streamConfig.value
        val intent = Intent(getApplication(), StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_CONFIG_IP,       config.serverIp)
            putExtra(StreamService.EXTRA_CONFIG_PORT,     config.serverPort)
            putExtra(StreamService.EXTRA_CONFIG_NAME,     config.streamName)
            putExtra(StreamService.EXTRA_CONFIG_USER,     config.username)
            putExtra(StreamService.EXTRA_CONFIG_PASS,     config.password)
            putExtra(StreamService.EXTRA_CONFIG_CAMERA,   config.cameraFacing)
            putExtra(StreamService.EXTRA_CONFIG_WIDTH,    config.resolution.width)
            putExtra(StreamService.EXTRA_CONFIG_HEIGHT,   config.resolution.height)
            putExtra(StreamService.EXTRA_CONFIG_FPS,      config.fps)
            putExtra(StreamService.EXTRA_CONFIG_VBITRATE, config.videoBitrate)
            putExtra(StreamService.EXTRA_CONFIG_ABITRATE, config.audioBitrate)
            putExtra(StreamService.EXTRA_CONFIG_SAMPLERATE, config.audioSampleRate)
        }
        getApplication<Application>().startForegroundService(intent)
        _streamState.value = StreamState.Connecting
    }

    fun stopStream() {
        val intent = Intent(getApplication(), StreamService::class.java).apply {
            action = StreamService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
        _streamState.value = StreamState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { getApplication<Application>().unregisterReceiver(statusReceiver) }
    }
}
