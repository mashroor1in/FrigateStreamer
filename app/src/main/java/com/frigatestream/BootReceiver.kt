package com.frigatestream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

/**
 * Receives BOOT_COMPLETED and starts the streaming service automatically
 * if the user has enabled "Auto-start on boot" and the server IP is configured.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val intentAction = intent.action ?: return
        if (intentAction != Intent.ACTION_BOOT_COMPLETED &&
            intentAction != "android.intent.action.QUICKBOOT_POWERON" &&
            intentAction != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        // Use goAsync() to safely launch a coroutine from a BroadcastReceiver
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PreferencesManager(context).streamConfigFlow.collect { config ->
                    if (config.autoStartOnBoot && config.isValid()) {
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
                    // Collect only first emission, then stop
                    this.coroutineContext.cancel()
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
