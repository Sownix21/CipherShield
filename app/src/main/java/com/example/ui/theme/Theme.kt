package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CipherShieldColors = darkColorScheme(
    primary = TechCyan,
    onPrimary = CosmicBackground,
    secondary = ScannerPurple,
    onSecondary = CosmicBackground,
    tertiary = EmeraldGreen,
    background = CosmicBackground,
    surface = CosmicSurface,
    onBackground = WhiteText,
    onSurface = WhiteText,
    onSurfaceVariant = SoftText,
    outline = DarkBorder,
    surfaceContainer = CosmicCard,
)

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CipherShieldColors, typography = Typography, content = content)
}
