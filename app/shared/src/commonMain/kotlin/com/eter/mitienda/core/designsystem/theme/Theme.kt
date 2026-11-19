package com.eter.mitienda.core.designsystem.theme

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
    surface = NeuColors.Background,
    onSurface = NeuColors.TextPrimary,
    surfaceVariant = NeuColors.Background,
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
