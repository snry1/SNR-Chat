package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SnrLightColorScheme = lightColorScheme(
    primary = CyberEmeraldDeep,
    onPrimary = Color.White,
    primaryContainer = SnrIndigoContainerLight,
    onPrimaryContainer = Color(0xFF003824),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8F1FD),
    onSecondaryContainer = Color(0xFF05446B),
    tertiary = Color(0xFF6D28D9),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightBackground,
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.65f),
    error = SnrErrorRed,
    onError = Color.White
)

private val SnrDarkColorScheme = darkColorScheme(
    primary = CyberEmerald,
    onPrimary = Color(0xFF021C12),
    primaryContainer = SnrIndigoContainerDark,
    onPrimaryContainer = Color(0xFF8CFFCE),
    secondary = CyberCyan,
    onSecondary = Color(0xFF021A24),
    secondaryContainer = Color(0xFF0A2C3B),
    onSecondaryContainer = Color(0xFF8CEFFF),
    tertiary = CyberViolet,
    onTertiary = Color(0xFF190A36),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurfaceContainer,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = Color(0xFF1B2B3F),
    outline = DarkOutline,
    outlineVariant = Color(0xFF132636),
    error = SnrErrorRedDark,
    onError = Color(0xFF330610)
)

private val SnrShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

@Composable
fun SnrChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) SnrDarkColorScheme else SnrLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SnrTypography,
        shapes = SnrShapes,
        content = content
    )
}
