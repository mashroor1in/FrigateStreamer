package com.frigatestream

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.frigatestream.ui.theme.*
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()

    // ── Permission Launcher ────────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startStream()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            FrigateStreamTheme {
                val config by viewModel.streamConfig.collectAsStateWithLifecycle()
                val state  by viewModel.streamState.collectAsStateWithLifecycle()

                FrigateStreamScreen(
                    config        = config,
                    streamState   = state,
                    onConfigChange = viewModel::saveConfig,
                    onStartStream  = { checkPermissionsAndStart() },
                    onStopStream   = viewModel::stopStream,
                    onSwitchCamera = {
                        val intent = Intent(this, StreamService::class.java).apply {
                            action = StreamService.ACTION_SWITCH_CAMERA
                        }
                        startService(intent)
                    },
                    onOpenBatterySettings = {
                        startActivity(Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        ))
                    },
                    hasBatteryExemption  = {
                        (getSystemService(POWER_SERVICE) as PowerManager)
                            .isIgnoringBatteryOptimizations(packageName)
                    }
                )
            }
        }
    }

    private fun checkPermissionsAndStart() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Screen
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun FrigateStreamScreen(
    config: StreamConfig,
    streamState: StreamState,
    onConfigChange: (StreamConfig) -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onSwitchCamera: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    hasBatteryExemption: () -> Boolean
) {
    val scrollState = rememberScrollState()
    val isStreaming = streamState is StreamState.Streaming
    val isActive    = streamState is StreamState.Streaming || streamState is StreamState.Connecting
                   || streamState is StreamState.Reconnecting

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        // Subtle animated background gradient
        AnimatedMeshBackground(isStreaming = isStreaming)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Header ────────────────────────────────────────────────────────
            AppHeader(streamState = streamState)

            // ── Status Banner ─────────────────────────────────────────────────
            AnimatedVisibility(
                visible = streamState !is StreamState.Idle,
                enter   = slideInVertically() + fadeIn(),
                exit    = slideOutVertically() + fadeOut()
            ) {
                StatusBanner(streamState = streamState)
            }


            // ── Server Config Card ────────────────────────────────────────────
            ConfigCard(title = "Server", icon = Icons.Outlined.Dns) {
                FStreamTextField(
                    id          = "field_server_ip",
                    value       = config.serverIp,
                    onValueChange = { onConfigChange(config.copy(serverIp = it)) },
                    label       = "Frigate / go2rtc IP Address",
                    placeholder = "192.168.1.100",
                    leadingIcon = { Icon(Icons.Outlined.Router, null, tint = CyanMid) },
                    keyboardType = KeyboardType.Uri,
                    enabled     = !isActive
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FStreamTextField(
                        id          = "field_server_port",
                        value       = if (config.serverPort == 0) "" else config.serverPort.toString(),
                        onValueChange = {
                            onConfigChange(config.copy(serverPort = it.toIntOrNull() ?: 8554))
                        },
                        label       = "RTSP Port",
                        placeholder = "8554",
                        keyboardType = KeyboardType.Number,
                        modifier    = Modifier.weight(0.4f),
                        enabled     = !isActive
                    )
                    FStreamTextField(
                        id          = "field_stream_name",
                        value       = config.streamName,
                        onValueChange = { onConfigChange(config.copy(streamName = it)) },
                        label       = "Stream Name",
                        placeholder = "phone_ps1",
                        modifier    = Modifier.weight(0.6f),
                        enabled     = !isActive
                    )
                }
                FStreamTextField(
                    id          = "field_username",
                    value       = config.username,
                    onValueChange = { onConfigChange(config.copy(username = it)) },
                    label       = "Username",
                    placeholder = "admin",
                    leadingIcon = { Icon(Icons.Outlined.Person, null, tint = TextSecondary) },
                    enabled     = !isActive
                )
                FStreamTextField(
                    id          = "field_password",
                    value       = config.password,
                    onValueChange = { onConfigChange(config.copy(password = it)) },
                    label       = "Password",
                    leadingIcon = { Icon(Icons.Outlined.Lock, null, tint = TextSecondary) },
                    isPassword  = true,
                    enabled     = !isActive
                )

                // Display RTSP URL preview
                if (config.isValid()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = InputBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = config.getDisplayUrl(),
                            style = MaterialTheme.typography.bodySmall,
                            color = CyanMid,
                            modifier = Modifier.padding(10.dp),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // ── Camera Config Card ────────────────────────────────────────────
            ConfigCard(title = "Camera", icon = Icons.Outlined.Videocam) {
                // Front / Rear toggle
                Text("Lens", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val canSwitchCamera = streamState is StreamState.Idle || streamState is StreamState.Streaming
                    CameraToggleButton(
                        id       = "btn_camera_back",
                        label    = "Rear",
                        icon     = Icons.Outlined.CameraRear,
                        selected = config.cameraFacing == StreamConfig.CAMERA_BACK,
                        onClick  = {
                            if (config.cameraFacing != StreamConfig.CAMERA_BACK) {
                                onConfigChange(config.copy(cameraFacing = StreamConfig.CAMERA_BACK))
                                if (streamState is StreamState.Streaming) {
                                    onSwitchCamera()
                                }
                            }
                        },
                        enabled  = canSwitchCamera,
                        modifier = Modifier.weight(1f)
                    )
                    CameraToggleButton(
                        id       = "btn_camera_front",
                        label    = "Front",
                        icon     = Icons.Outlined.CameraFront,
                        selected = config.cameraFacing == StreamConfig.CAMERA_FRONT,
                        onClick  = {
                            if (config.cameraFacing != StreamConfig.CAMERA_FRONT) {
                                onConfigChange(config.copy(cameraFacing = StreamConfig.CAMERA_FRONT))
                                if (streamState is StreamState.Streaming) {
                                    onSwitchCamera()
                                }
                            }
                        },
                        enabled  = canSwitchCamera,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Resolution chips
                Text("Resolution", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StreamConfig.Resolution.entries.forEach { res ->
                        ResolutionChip(
                            id       = "chip_res_${res.name}",
                            label    = res.label,
                            selected = config.resolution == res,
                            onClick  = { onConfigChange(config.copy(resolution = res)) },
                            enabled  = !isActive
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // FPS slider
                val fpsSteps = listOf(10, 15, 20, 25, 30)
                val fpsIndex = (fpsSteps.indexOfFirst { it == config.fps })
                    .coerceAtLeast(0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Speed, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("FPS: ${config.fps}", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                Slider(
                    value           = fpsIndex.toFloat(),
                    onValueChange   = { idx ->
                        onConfigChange(config.copy(fps = fpsSteps[idx.toInt()]))
                    },
                    valueRange      = 0f..(fpsSteps.size - 1).toFloat(),
                    steps           = fpsSteps.size - 2,
                    enabled         = !isActive,
                    colors          = SliderDefaults.colors(thumbColor = CyanMid, activeTrackColor = CyanMid)
                )

                // Bitrate
                Text(
                    "Video Bitrate: ${config.videoBitrate / 1000} kbps",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                val bitrateSteps = listOf(500_000, 1_000_000, 1_500_000, 2_000_000, 3_000_000, 4_000_000)
                val brIdx = bitrateSteps.indexOfFirst { it == config.videoBitrate }.coerceAtLeast(0)
                Slider(
                    value           = brIdx.toFloat(),
                    onValueChange   = { idx ->
                        onConfigChange(config.copy(videoBitrate = bitrateSteps[idx.toInt()]))
                    },
                    valueRange      = 0f..(bitrateSteps.size - 1).toFloat(),
                    steps           = bitrateSteps.size - 2,
                    enabled         = !isActive,
                    colors          = SliderDefaults.colors(thumbColor = PurpleMid, activeTrackColor = PurpleMid)
                )
            }

            // ── Options Card ─────────────────────────────────────────────────
            ConfigCard(title = "Options", icon = Icons.Outlined.Settings) {
                OptionSwitch(
                    id      = "switch_auto_boot",
                    label   = "Auto-start on Boot",
                    sublabel = "Stream automatically when device restarts",
                    icon    = Icons.Outlined.RestartAlt,
                    checked = config.autoStartOnBoot,
                    onCheckedChange = { onConfigChange(config.copy(autoStartOnBoot = it)) }
                )
                HorizontalDivider(color = OutlineColor.copy(alpha = 0.5f))
                OptionRow(
                    id      = "row_battery",
                    label   = if (hasBatteryExemption()) "Battery Optimization Exempt ✓"
                              else "Disable Battery Optimization",
                    sublabel = if (hasBatteryExemption()) "Stream will not be killed by the system"
                               else "Recommended — prevents stream from being stopped",
                    icon    = if (hasBatteryExemption()) Icons.Outlined.BatteryFull else Icons.Outlined.BatteryAlert,
                    iconTint = if (hasBatteryExemption()) GreenLive else AmberWarn,
                    onClick = onOpenBatterySettings,
                    enabled = !hasBatteryExemption()
                )
            }

            // ── Start / Stop Button ───────────────────────────────────────────
            StreamControlButton(
                id          = "btn_stream_control",
                streamState = streamState,
                onStart     = onStartStream,
                onStop      = onStopStream,
                isValid     = config.isValid()
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Components
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun AnimatedMeshBackground(isStreaming: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue  = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val alpha = if (isStreaming) 0.12f else 0.06f

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Animated radial glow — top right (cyan)
        val cx = size.width * 0.8f + sin(phase.toDouble()).toFloat() * 30f
        val cy = size.height * 0.15f + sin((phase + 1.0).toDouble()).toFloat() * 20f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(CyanMid.copy(alpha = alpha), Color.Transparent),
                center = Offset(cx, cy),
                radius = size.width * 0.5f
            ),
            radius = size.width * 0.5f,
            center = Offset(cx, cy)
        )
        // Purple glow — bottom left
        val px = size.width * 0.1f + sin((phase + 2.0).toDouble()).toFloat() * 30f
        val py = size.height * 0.8f + sin((phase + 3.0).toDouble()).toFloat() * 20f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PurpleMid.copy(alpha = alpha), Color.Transparent),
                center = Offset(px, py),
                radius = size.width * 0.5f
            ),
            radius = size.width * 0.5f,
            center = Offset(px, py)
        )
    }
}

@Composable
fun AppHeader(streamState: StreamState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status dot
        val dotColor = when (streamState) {
            is StreamState.Streaming    -> GreenLive
            is StreamState.Connecting   -> AmberWarn
            is StreamState.Reconnecting -> AmberWarn
            is StreamState.Error        -> RedError
            else -> TextHint
        }
        val infiniteTransition = rememberInfiniteTransition(label = "dot")
        val dotScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue  = if (streamState is StreamState.Streaming) 1.5f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot_scale"
        )
        Box(
            modifier = Modifier
                .size(10.dp)
                .scale(dotScale)
                .background(dotColor, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                "FRIGATE STREAM",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = TextPrimary
            )
            Text(
                "Home Security Camera",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Spacer(Modifier.weight(1f))
        // Camera icon with gradient
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    Brush.linearGradient(listOf(CyanMid.copy(0.2f), PurpleMid.copy(0.2f))),
                    CircleShape
                )
                .border(1.dp, Brush.linearGradient(listOf(CyanMid.copy(0.5f), PurpleMid.copy(0.5f))), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Videocam, null, tint = CyanBright, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
fun StatusBanner(streamState: StreamState) {
    val (bg, text, icon) = when (streamState) {
        is StreamState.Streaming -> Triple(GreenLive.copy(0.15f), GreenLive, Icons.Filled.FiberManualRecord)
        is StreamState.Connecting -> Triple(AmberWarn.copy(0.15f), AmberWarn, Icons.Filled.HourglassTop)
        is StreamState.Reconnecting -> Triple(AmberWarn.copy(0.15f), AmberWarn, Icons.Filled.Refresh)
        is StreamState.Error -> Triple(RedError.copy(0.15f), RedError, Icons.Filled.ErrorOutline)
        else -> Triple(TextHint.copy(0.1f), TextHint, Icons.Filled.Info)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, text.copy(0.3f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, null, tint = text, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (streamState) {
                        is StreamState.Streaming    -> "● LIVE — Streaming to Frigate"
                        is StreamState.Connecting   -> "Connecting…"
                        is StreamState.Reconnecting -> "Reconnecting… (attempt ${streamState.attempt})"
                        is StreamState.Error        -> "Error"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = text
                )
                if (streamState is StreamState.Streaming) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "↑ ${streamState.bitrateKbps} kbps",
                            style = MaterialTheme.typography.bodySmall,
                            color = text.copy(0.8f)
                        )
                        Text(
                            "⏱ ${streamState.uptime}",
                            style = MaterialTheme.typography.bodySmall,
                            color = text.copy(0.8f)
                        )
                    }
                }
                if (streamState is StreamState.Error) {
                    Text(
                        streamState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = text.copy(0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionWarningCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AmberWarn.copy(0.1f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, AmberWarn.copy(0.4f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = AmberWarn, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = AmberWarn, fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodySmall, color = AmberWarn.copy(0.8f))
            }
            Icon(Icons.Filled.ChevronRight, null, tint = AmberWarn.copy(0.6f))
        }
    }
}

@Composable
fun ConfigCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = SurfaceCard,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, OutlineColor, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(
                            Brush.linearGradient(listOf(CyanMid.copy(0.2f), PurpleMid.copy(0.2f))),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = CyanMid, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            content()
        }
    }
}

@Composable
fun FStreamTextField(
    id: String,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value          = value,
        onValueChange  = onValueChange,
        label          = { Text(label, style = MaterialTheme.typography.bodySmall) },
        placeholder    = { Text(placeholder, color = TextHint) },
        leadingIcon    = leadingIcon,
        trailingIcon   = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        null, tint = TextSecondary
                    )
                }
            }
        } else null,
        visualTransformation = if (isPassword && !passwordVisible)
            PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine      = true,
        enabled         = enabled,
        modifier        = modifier.testTag(id),
        shape           = RoundedCornerShape(10.dp),
        colors          = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = CyanMid,
            unfocusedBorderColor = OutlineColor,
            focusedLabelColor    = CyanMid,
            unfocusedLabelColor  = TextSecondary,
            cursorColor          = CyanMid,
            focusedTextColor     = TextPrimary,
            unfocusedTextColor   = TextPrimary,
            disabledTextColor    = TextSecondary,
            disabledBorderColor  = OutlineColor.copy(0.5f),
            disabledLabelColor   = TextHint,
            unfocusedContainerColor = InputBg,
            focusedContainerColor   = InputBg,
            disabledContainerColor  = InputBg.copy(0.5f)
        )
    )
}

@Composable
fun CameraToggleButton(
    id: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val bg = if (selected)
        Brush.linearGradient(listOf(CyanMid.copy(0.25f), PurpleMid.copy(0.25f)))
    else Brush.linearGradient(listOf(InputBg, InputBg))
    val borderColor = if (selected) CyanMid else OutlineColor

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = modifier
            .testTag(id)
            .height(52.dp)
            .background(bg, RoundedCornerShape(10.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon, null,
                tint = if (selected) CyanBright else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) CyanBright else TextSecondary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun ResolutionChip(
    id: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    FilterChip(
        selected = selected,
        onClick  = { if (enabled) onClick() },
        label    = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = Modifier.testTag(id),
        colors   = FilterChipDefaults.filterChipColors(
            selectedContainerColor   = CyanMid.copy(0.2f),
            selectedLabelColor       = CyanBright,
            containerColor           = InputBg,
            labelColor               = TextSecondary
        ),
        border = FilterChipDefaults.filterChipBorder(
            borderColor         = OutlineColor,
            selectedBorderColor = CyanMid,
            enabled             = enabled,
            selected            = selected
        )
    )
}

@Composable
fun OptionSwitch(
    id: String,
    label: String,
    sublabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .testTag(id)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = CyanMid, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(sublabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor       = Color.White,
                checkedTrackColor       = CyanMid,
                uncheckedThumbColor     = TextSecondary,
                uncheckedTrackColor     = InputBg
            )
        )
    }
}

@Composable
fun OptionRow(
    id: String,
    label: String,
    sublabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color = TextSecondary,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .testTag(id)
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(sublabel, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        if (enabled) {
            Icon(Icons.Filled.ChevronRight, null, tint = TextHint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun StreamControlButton(
    id: String,
    streamState: StreamState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    isValid: Boolean
) {
    val isActive = streamState is StreamState.Streaming || streamState is StreamState.Connecting
                || streamState is StreamState.Reconnecting

    val infiniteTransition = rememberInfiniteTransition(label = "btn")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue  = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val btnGradient = if (isActive) {
        Brush.linearGradient(listOf(RedError.copy(0.8f), Color(0xFFB71C1C)))
    } else if (isValid) {
        Brush.linearGradient(listOf(CyanMid, PurpleMid))
    } else {
        Brush.linearGradient(listOf(TextHint, TextHint))
    }

    val glowBrush = if (isActive) {
        Brush.radialGradient(listOf(RedError.copy(glowAlpha * 0.3f), Color.Transparent))
    } else if (isValid) {
        Brush.radialGradient(listOf(CyanMid.copy(glowAlpha * 0.3f), Color.Transparent))
    } else null

    Box(contentAlignment = Alignment.Center) {
        // Glow effect behind button
        if (glowBrush != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(glowBrush, RoundedCornerShape(20.dp))
            )
        }
        Box(
            modifier = Modifier
                .testTag(id)
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    if (!isValid && !isActive) SurfaceHigh.let {
                        Brush.linearGradient(listOf(it, it))
                    } else btnGradient,
                    RoundedCornerShape(16.dp)
                )
                .clickable(
                    enabled = isValid || isActive,
                    onClick = { if (isActive) onStop() else onStart() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = if (!isValid && !isActive) TextHint else Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = when (streamState) {
                        is StreamState.Connecting   -> "CONNECTING…"
                        is StreamState.Streaming    -> "STOP STREAM"
                        is StreamState.Reconnecting -> "RECONNECTING…"
                        else -> if (isValid) "START STREAM" else "ENTER SERVER IP FIRST"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp,
                    color = if (!isValid && !isActive) TextHint else Color.White
                )
            }
        }
    }
}
