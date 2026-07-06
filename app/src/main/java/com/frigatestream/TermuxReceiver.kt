package com.frigatestream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Receiver that allows external shell commands (Termux, ADB) to control the stream
 * and change configuration settings using Broadcast Intents.
 *
 * Example Termux Commands (as root or shell):
 *
 * 1. Start Stream:
 *    am broadcast -a com.frigatestream.ACTION_COMMAND -n com.frigatestream/.TermuxReceiver --es cmd start
 *
 * 2. Stop Stream:
 *    am broadcast -a com.frigatestream.ACTION_COMMAND -n com.frigatestream/.TermuxReceiver --es cmd stop
 *
 * 3. Change Camera Lens (Front/Rear):
 *    am broadcast -a com.frigatestream.ACTION_COMMAND -n com.frigatestream/.TermuxReceiver --es cmd set_camera --es camera front
 *    am broadcast -a com.frigatestream.ACTION_COMMAND -n com.frigatestream/.TermuxReceiver --es cmd set_camera --es camera rear
 *
 * 4. Change Server Configuration:
 *    am broadcast -a com.frigatestream.ACTION_COMMAND -n com.frigatestream/.TermuxReceiver --es cmd set_config --es ip 192.168.31.106 --es name my_stream
 */
class TermuxReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMMAND = "com.frigatestream.ACTION_COMMAND"
        private const val TAG = "TermuxReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return

        val cmd = intent.getStringExtra("cmd") ?: intent.getStringExtra("command")
        if (cmd == null) {
            Log.w(TAG, "No command ('cmd' or 'command' extra) provided in broadcast.")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = PreferencesManager(context)
                val currentConfig = prefs.streamConfigFlow.first()

                when (cmd.lowercase()) {
                    "start" -> {
                        // Optionally allow temporary configuration overrides on start
                        val updatedConfig = parseConfigOverrides(intent, currentConfig)
                        if (updatedConfig != currentConfig) {
                            prefs.saveStreamConfig(updatedConfig)
                        }
                        if (updatedConfig.isValid()) {
                            startService(context, updatedConfig)
                        } else {
                            Log.e(TAG, "Cannot start stream: Configuration is invalid (IP or Stream Name is empty).")
                        }
                    }

                    "stop" -> {
                        stopService(context)
                    }

                    "set_camera" -> {
                        val cameraExtra = intent.getStringExtra("camera")?.lowercase()
                        if (cameraExtra == "front" || cameraExtra == "rear") {
                            val newFacing = if (cameraExtra == "front") {
                                StreamConfig.CAMERA_FRONT
                            } else {
                                StreamConfig.CAMERA_BACK
                            }

                            if (currentConfig.cameraFacing != newFacing) {
                                val updatedConfig = currentConfig.copy(cameraFacing = newFacing)
                                prefs.saveStreamConfig(updatedConfig)

                                // If currently streaming, trigger dynamic camera switch
                                val serviceIntent = Intent(context, StreamService::class.java).apply {
                                    action = StreamService.ACTION_SWITCH_CAMERA
                                }
                                context.startService(serviceIntent)
                                Log.i(TAG, "Switched camera lens to: $cameraExtra")
                            }
                        } else {
                            Log.w(TAG, "Invalid camera lens extra. Use 'front' or 'rear'.")
                        }
                    }

                    "set_config" -> {
                        val updatedConfig = parseConfigOverrides(intent, currentConfig)
                        prefs.saveStreamConfig(updatedConfig)
                        Log.i(TAG, "Saved new server config via Termux.")
                    }

                    else -> {
                        Log.w(TAG, "Unknown command received: $cmd")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing Termux broadcast command: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun parseConfigOverrides(intent: Intent, baseConfig: StreamConfig): StreamConfig {
        var config = baseConfig

        intent.getStringExtra("ip")?.let { config = config.copy(serverIp = it.trim()) }
        
        // Handle port override (both string and integer extra types)
        val portStr = intent.getStringExtra("port")
        if (portStr != null) {
            portStr.toIntOrNull()?.let { config = config.copy(serverPort = it) }
        } else if (intent.hasExtra("port")) {
            val portInt = intent.getIntExtra("port", -1)
            if (portInt != -1) {
                config = config.copy(serverPort = portInt)
            }
        }

        intent.getStringExtra("name")?.let { config = config.copy(streamName = it.trim()) }
        intent.getStringExtra("user")?.let { config = config.copy(username = it.trim()) }
        intent.getStringExtra("pass")?.let { config = config.copy(password = it) }

        return config
    }

    private fun startService(context: Context, config: StreamConfig) {
        val serviceIntent = Intent(context, StreamService::class.java).apply {
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
        context.startForegroundService(serviceIntent)
    }

    private fun stopService(context: Context) {
        val serviceIntent = Intent(context, StreamService::class.java).apply {
            action = StreamService.ACTION_STOP
        }
        context.startService(serviceIntent)
    }
}
