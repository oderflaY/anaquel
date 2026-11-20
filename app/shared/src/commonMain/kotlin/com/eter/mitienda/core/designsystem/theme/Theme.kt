package com.eter.mitienda.core.designsystem.theme

import androidx.compose.ui.graphics.Color

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Mapea la paleta neumórfica a Material 3 para que cualquier componente de
 * Material que se use después (diálogos, snackbars, selección de texto) herede
 * los mismos colores sin configuración extra.
 */
private val NeuColorScheme = lightColorScheme(
    primary = NeuColors.Magenta,
    onPrimary = NeuColors.OnAccent,
    secondary = NeuColors.Blue,
    onSecondary = NeuColors.OnAccent,
    background = NeuColors.Background,
    onBackground = NeuColors.TextPrimary,
    surface = NeuColors.Surface,
    onSurface = NeuColors.TextPrimary,
    surfaceVariant = NeuColors.SurfaceSunken,
    surfaceContainer = NeuColors.Surface,
    surfaceContainerHigh = NeuColors.Surface,
    surfaceContainerHighest = NeuColors.Surface,
    surfaceContainerLow = NeuColors.Background,
    surfaceContainerLowest = NeuColors.Surface,
    primaryContainer = NeuColors.ChipBackground,
    onPrimaryContainer = NeuColors.OnChip,
    outline = Color(0x33000000),
    outlineVariant = NeuColors.Hairline,
    onSurfaceVariant = NeuColors.TextSecondary,
    error = NeuColors.OnLowStock,
    errorContainer = NeuColors.LowStockContainer,
)

@Composable
fun MiTiendaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NeuColorScheme,
        typography = NeuTypography,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides NeuColors.TextPrimary,
            content = content,
        )
    }
}
