package io.github.alerrandrov.easytelebot.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/*
 * Paleta própria (Android 8 a 11, sem cores dinâmicas). Todos os pares
 * fundo/texto têm contraste de pelo menos 4,5:1 (WCAG AA), ex.: primary
 * #1565A8 com branco = 6,1:1; no escuro #8ECDF8 com #00344F = 7,6:1.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1565A8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F3F7),
    surfaceContainer = Color(0xFFECEEF2),
    surfaceContainerHigh = Color(0xFFE7E8EC),
    surfaceContainerHighest = Color(0xFFE1E2E7),
    outline = Color(0xFF73777F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ECDF8),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004B71),
    onPrimaryContainer = Color(0xFFCAE6FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF111416),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111416),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
    surfaceContainerLowest = Color(0xFF0C0F11),
    surfaceContainerLow = Color(0xFF191C1F),
    surfaceContainer = Color(0xFF1D2023),
    surfaceContainerHigh = Color(0xFF272A2D),
    surfaceContainerHighest = Color(0xFF33373B),
    outline = Color(0xFF8C9199),
)

@Composable
fun EasyTelebotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
