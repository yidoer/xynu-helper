package com.xynu.campus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = XynuRed,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = XynuRedLight,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = XynuBlue,
    background = SurfaceBg,
    surface = CardBg,
    onBackground = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
    surfaceVariant = DividerGray,
    outline = DividerGray,
)

@Composable
fun XynuCampusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content
    )
}