package com.marketplace.dayf.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val DayfColors = lightColorScheme(
    primary = Color(0xFF131B2E),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF006C4A),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE8ECEE),
    onSurfaceVariant = Color(0xFF45464D),
    outline = Color(0xFF76777D),
    error = Color(0xFFBA1A1A)
)

@Composable
fun DayfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DayfColors,
        typography = MaterialTheme.typography.copy(
            bodyLarge = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
            bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.SansSerif),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.SansSerif),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.SansSerif)
        ),
        content = content
    )
}
