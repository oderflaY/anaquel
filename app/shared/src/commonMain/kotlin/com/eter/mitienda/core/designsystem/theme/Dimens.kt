package com.eter.mitienda.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Intensidad de una sombra neumórfica, equivalente a `box-shadow: X X BLUR`.
 * [offset] desplaza la sombra en ambos ejes y [blur] es el radio de desenfoque.
 */
@Immutable
data class NeuElevation(val offset: Dp, val blur: Dp)

/** Métricas globales: espaciado (escala de 4dp), radios, tamaños y elevaciones. */
object NeuDimens {
    // Espaciado
    val SpaceXs = 4.dp
    val SpaceSm = 8.dp
    val SpaceMd = 12.dp
    val SpaceLg = 16.dp
    val SpaceXl = 24.dp
    val SpaceXxl = 32.dp

    /**
     * Margen lateral de pantalla. Debe ser >= offset + blur/2 de la elevación
     * más grande para que las sombras no se corten contra el borde.
     */
    val ScreenPadding = 24.dp

    // Radios
    val RadiusSmall = 12.dp
    val RadiusLarge = 18.dp

    // Tamaños de componentes
    val ButtonHeight = 52.dp
    val InputHeight = 56.dp
    val ChipHeight = 40.dp
    val IconButtonSize = 46.dp
    val IconButtonSmallSize = 38.dp
    val FabSize = 64.dp
    val IconSize = 22.dp
    val IconSmallSize = 18.dp

    // Elevaciones (CSS: 8px 8px 16px / 5px 5px 10px / 3px 3px 6px)
    val ElevationLarge = NeuElevation(offset = 8.dp, blur = 16.dp)
    val ElevationMedium = NeuElevation(offset = 5.dp, blur = 10.dp)
    val ElevationSmall = NeuElevation(offset = 3.dp, blur = 6.dp)

    /** Duración de la transición Outset → Inset al presionar. */
    const val PressAnimationMillis = 150
}

/** Formas reutilizables. */
object NeuShapes {
    val Small = RoundedCornerShape(NeuDimens.RadiusSmall)
    val Large = RoundedCornerShape(NeuDimens.RadiusLarge)
    val Pill = RoundedCornerShape(percent = 50)
    val Circle = CircleShape
}
