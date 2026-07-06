package com.frigatestream.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary          = CyanMid,
    onPrimary        = Color(0xFF001C28),
    primaryContainer = Color(0xFF003548),
    onPrimaryContainer = CyanBright,

    secondary        = PurpleMid,
    onSecondary      = Color(0xFF1F0040),
    secondaryContainer = Color(0xFF3A0070),
    onSecondaryContainer = Color(0xFFE8B4FF),

    background       = Background,
    onBackground     = TextPrimary,
    surface          = SurfaceCard,
    onSurface        = TextPrimary,
    surfaceVariant   = SurfaceHigh,
    onSurfaceVariant = TextSecondary,

    outline          = OutlineColor,
    error            = RedError,
    onError          = Color.White,
    errorContainer   = Color(0xFF7F0000),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun FrigateStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
