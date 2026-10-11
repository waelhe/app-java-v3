package com.marketplace.android.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DayfColors = lightColorScheme(
    primary = Color(0xFF17665A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0F0E7),
    onPrimaryContainer = Color(0xFF073B34),
    secondary = Color(0xFFB85D45),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBD0),
    onSecondaryContainer = Color(0xFF431B11),
    background = Color(0xFFF7F8F5),
    onBackground = Color(0xFF19211F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF19211F),
    surfaceVariant = Color(0xFFE8EEEA),
    onSurfaceVariant = Color(0xFF4C5B55),
    outline = Color(0xFFBBC8C1),
    error = Color(0xFFB3261E)
)

@Composable
fun DayfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DayfColors,
        content = content
    )
}
