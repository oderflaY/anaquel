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
    val Background = Color(0xFFF2F5F3)

    /** Tarjetas, botones claros e inputs (estilo plano). */
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSunken = Color(0xFFEAEFEC)
    val Hairline = Color(0x14000000)
    val InputBorder = Color(0x24000000)
    val ShadowSoft = Color(0x1F0B3D2E)

    /** Sombra proyectada (abajo a la derecha). */
    val ShadowDark = Color(0xFFD5DDD8)

    /** Luz/brillo (arriba a la izquierda). */
    val ShadowLight = Color(0xFFFFFFFF)

    /** Acento principal, magenta DIF: acciones primarias (FAB, guardar, confirmar). */
    val Magenta = Color(0xFF0E7C5A) // Anaquel: verde de la marca

    /** Acento secundario, azul DIF: filtros activos, acciones de hardware (escáner). */
    val Blue = Color(0xFF12A374)

    /** Texto e íconos sobre [Magenta] o [Blue]. */
    val OnAccent = Color(0xFFFFFFFF)

    /** Magenta aclarado para usar sobre fondos oscuros (cámara, snackbar). */
    val MagentaOnDark = Color(0xFF4FD1A2)

    /** Badges suaves: fondo rosa DIF con texto vino. */
    val ChipBackground = Color(0xFFDFF3EA)
    val OnChip = Color(0xFF0A5F45)

    /** Títulos (alto contraste). */
    val TextPrimary = Color(0xFF17251F)

    /** Subtítulos y metadatos. */
    val TextSecondary = Color(0xFF5F6F68)

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
