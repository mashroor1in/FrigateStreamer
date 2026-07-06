package com.frigatestream

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// Top-level DataStore instance (singleton per process)
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "frigate_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_SERVER_IP        = stringPreferencesKey("server_ip")
        val KEY_SERVER_PORT      = intPreferencesKey("server_port")
        val KEY_STREAM_NAME      = stringPreferencesKey("stream_name")
        val KEY_USERNAME         = stringPreferencesKey("username")
        val KEY_PASSWORD         = stringPreferencesKey("password")
        val KEY_CAMERA_FACING    = intPreferencesKey("camera_facing")
        val KEY_RESOLUTION       = stringPreferencesKey("resolution")
        val KEY_FPS              = intPreferencesKey("fps")
        val KEY_VIDEO_BITRATE    = intPreferencesKey("video_bitrate")
        val KEY_AUTO_START_BOOT  = booleanPreferencesKey("auto_start_boot")
    }

    val streamConfigFlow: Flow<StreamConfig> = context.dataStore.data
        .catch { exception ->
            // Gracefully handle DataStore read failures
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }
        .map { prefs ->
            StreamConfig(
                serverIp       = prefs[KEY_SERVER_IP]       ?: "",
                serverPort     = prefs[KEY_SERVER_PORT]     ?: 8554,
                streamName     = prefs[KEY_STREAM_NAME]     ?: "phone_camera",
                username       = prefs[KEY_USERNAME]        ?: "",
                password       = prefs[KEY_PASSWORD]        ?: "",
                cameraFacing   = prefs[KEY_CAMERA_FACING]   ?: StreamConfig.CAMERA_BACK,
                resolution     = try {
                    StreamConfig.Resolution.valueOf(prefs[KEY_RESOLUTION] ?: "RES_720P")
                } catch (_: Exception) { StreamConfig.Resolution.RES_720P },
                fps            = prefs[KEY_FPS]             ?: 15,
                videoBitrate   = prefs[KEY_VIDEO_BITRATE]   ?: 1_500_000,
                autoStartOnBoot = prefs[KEY_AUTO_START_BOOT] ?: true
            )
        }

    suspend fun saveStreamConfig(config: StreamConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_IP]       = config.serverIp
            prefs[KEY_SERVER_PORT]     = config.serverPort
            prefs[KEY_STREAM_NAME]     = config.streamName
            prefs[KEY_USERNAME]        = config.username
            prefs[KEY_PASSWORD]        = config.password
            prefs[KEY_CAMERA_FACING]   = config.cameraFacing
            prefs[KEY_RESOLUTION]      = config.resolution.name
            prefs[KEY_FPS]             = config.fps
            prefs[KEY_VIDEO_BITRATE]   = config.videoBitrate
            prefs[KEY_AUTO_START_BOOT] = config.autoStartOnBoot
        }
    }
}
