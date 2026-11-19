package com.eter.mitienda.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta del sistema de diseño neumórfico, con los acentos del DIF Durango
 * (los mismos que la app RutaCree).
 *
 * Regla de oro del neumorfismo: todo elemento "sale" o "se hunde" en el MISMO
 * color del fondo; la forma se percibe únicamente por el par de sombras
 * (oscura abajo-derecha, clara arriba-izquierda). Los acentos se reservan para
 * acciones y estados.
 */
object NeuColors {
    /** Fondo global y color de superficie de tarjetas/inputs. */
    val Background = Color(0xFFE6ECF5)

    /** Sombra proyectada (abajo a la derecha). */
    val ShadowDark = Color(0xFFC5CBD5)

    /** Luz/brillo (arriba a la izquierda). */
    val ShadowLight = Color(0xFFFFFFFF)

    /** Acento principal, magenta DIF: acciones primarias (FAB, guardar, confirmar). */
    val Magenta = Color(0xFFD91178)

    /** Acento secundario, azul DIF: filtros activos, acciones de hardware (escáner). */
    val Blue = Color(0xFF2F5DA8)

    /** Texto e íconos sobre [Magenta] o [Blue]. */
    val OnAccent = Color(0xFFFFFFFF)

    /** Magenta aclarado para usar sobre fondos oscuros (cámara, snackbar). */
    val MagentaOnDark = Color(0xFFF56AAE)

    /** Badges suaves: fondo rosa DIF con texto vino. */
    val ChipBackground = Color(0xFFF7D3E5)
    val OnChip = Color(0xFF9C1866)

    /** Títulos (alto contraste). */
    val TextPrimary = Color(0xFF2C3E50)

    /** Subtítulos y metadatos. */
    val TextSecondary = Color(0xFF5A6C7D)

    /** Estado semántico: stock por debajo del mínimo. */
    val LowStockContainer = Color(0xFFFED7D7)
    val OnLowStock = Color(0xFF9B2C2C)

    /** Color de texto/íconos legible sobre [container]. */
    fun contentColorFor(container: Color): Color = when (container) {
        Magenta, Blue -> OnAccent
        ChipBackground -> OnChip
        LowStockContainer -> OnLowStock
        else -> TextPrimary
    }
}
