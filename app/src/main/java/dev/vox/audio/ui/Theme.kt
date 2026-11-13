package dev.vox.audio.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val VoxRed = Color(0xFFE50914)
val VoxBg = Color(0xFF0B0B0B)

private val Netflix = darkColorScheme(
    primary = VoxRed, onPrimary = Color.White,
    secondary = Color(0xFFB3B3B3), background = VoxBg, onBackground = Color.White,
    surface = VoxBg, onSurface = Color.White, surfaceVariant = Color(0xFF2B2B2B), onSurfaceVariant = Color(0xFFB3B3B3),
    surfaceContainer = Color(0xFF141414), surfaceContainerHigh = Color(0xFF1F1F1F), secondaryContainer = Color(0xFF2B2B2B),
    onSecondaryContainer = Color.White, error = Color(0xFFFF6B6B),
)

@Composable
fun VoxTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Netflix, content = content)
