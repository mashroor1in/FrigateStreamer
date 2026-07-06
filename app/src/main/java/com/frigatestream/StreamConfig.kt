package com.frigatestream

/**
 * Holds all configuration needed to start an RTSP stream.
 * The phone acts as an RTSP push client — it connects to go2rtc's
 * RTSP server (port 8554) and announces a stream using RTSP ANNOUNCE/RECORD.
 */
data class StreamConfig(
    val serverIp: String = "",
    val serverPort: Int = 8554,
    val streamName: String = "phone_camera",
    val username: String = "",
    val password: String = "",
    val cameraFacing: Int = CAMERA_BACK,  // 0 = back, 1 = front
    val resolution: Resolution = Resolution.RES_720P,
    val fps: Int = 15,
    val videoBitrate: Int = 1_500_000,    // 1.5 Mbps — good for 720p
    val audioBitrate: Int = 128_000,      // 128 kbps AAC
    val audioSampleRate: Int = 44_100,
    val autoStartOnBoot: Boolean = true
) {
    companion object {
        const val CAMERA_BACK = 0
        const val CAMERA_FRONT = 1
    }

    enum class Resolution(val width: Int, val height: Int, val label: String) {
        RES_480P(640, 480, "480p SD"),
        RES_720P(1280, 720, "720p HD"),
        RES_1080P(1920, 1080, "1080p FHD")
    }

    /**
     * Builds the RTSP URL for pushing to go2rtc.
     * go2rtc accepts RTSP push on: rtsp://<ip>:<port>/<stream_name>
     */
    fun getRtspUrl(): String {
        val cleanIp = serverIp.trim()
        val cleanName = streamName.trim()
        val cleanUser = username.trim()
        return if (cleanUser.isNotBlank() && password.isNotBlank()) {
            "rtsp://${cleanUser}:${password}@${cleanIp}:${serverPort}/${cleanName}"
        } else {
            "rtsp://${cleanIp}:${serverPort}/${cleanName}"
        }
    }

    /** Sanitized display URL (hides password) */
    fun getDisplayUrl(): String {
        val cleanIp = serverIp.trim()
        val cleanName = streamName.trim()
        val cleanUser = username.trim()
        return if (cleanUser.isNotBlank()) {
            "rtsp://${cleanUser}:***@${cleanIp}:${serverPort}/${cleanName}"
        } else {
            "rtsp://${cleanIp}:${serverPort}/${cleanName}"
        }
    }

    fun isValid(): Boolean = serverIp.trim().isNotBlank() && streamName.trim().isNotBlank()
}
