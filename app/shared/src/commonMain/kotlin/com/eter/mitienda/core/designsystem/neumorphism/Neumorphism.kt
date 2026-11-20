package com.eter.mitienda.core.designsystem.neumorphism

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuElevation

/** Progreso constante para elementos siempre extruidos (tarjetas estáticas). */
val OutsetAlways: () -> Float = { 0f }

/** Progreso constante para elementos siempre hundidos (inputs, huecos de imagen). */
val InsetAlways: () -> Float = { 1f }

/**
 * Motor del neumorfismo: aplica las CUATRO sombras que en CSS serían
 *
 * ```css
 * box-shadow:  8px  8px 16px #c5cbd5,  -8px -8px 16px #ffffff;        // outset
 * box-shadow: inset 8px 8px 16px #c5cbd5, inset -8px -8px 16px #fff; // inset
 * ```
 *
 * Compose (1.9+) dibuja sombras con blur real y cualquier color mediante
 * `Modifier.dropShadow` (fuera de la forma) e `Modifier.innerShadow` (dentro
 * de la forma, recortada a ella). Como cada llamada es un nodo de dibujo
 * independiente, se pueden encadenar varias: así se resuelve la "sombra
 * múltiple" sin superponer contenedores ni usar librerías.
 *
 * Orden de dibujo (de abajo hacia arriba):
 * 1. Sombra oscura exterior (abajo-derecha)
 * 2. Sombra clara exterior (arriba-izquierda)
 * 3. Superficie del color de fondo
 * 4. Sombra oscura interior (arriba-izquierda, simula el borde del hueco)
 * 5. Sombra clara interior (abajo-derecha)
 * 6. Contenido (los nodos de sombra interior pintan ANTES de su contenido)
 *
 * @param insetProgress 0f = completamente extruido (outset), 1f = completamente
 * hundido (inset). Es una lambda que se lee en la fase de DIBUJO: al animar la
 * pulsación solo se redibuja la capa, sin recomposición ni re-layout.
 */
fun Modifier.neumorphic(
    shape: Shape,
    elevation: NeuElevation = NeuDimens.ElevationLarge,
    surfaceColor: Color = NeuColors.Background,
    darkShadow: Color = NeuColors.ShadowDark,
    lightShadow: Color = NeuColors.ShadowLight,
    insetProgress: () -> Float = OutsetAlways,
): Modifier {
    // Estilo Anaquel (plano, igual que el feed, el perfil y la hoja de negociar):
    // superficies blancas con una sombra suave hacia abajo y un filete fino.
    // «Hundido» (inputs, presionado) = gris verdoso claro, sin sombra.
    // Los nombres «neumorphic» se conservan para no tocar todas las pantallas.
    val neutra = surfaceColor == NeuColors.Background
    val arriba = if (neutra) NeuColors.Surface else surfaceColor
    val abajo = if (neutra) NeuColors.SurfaceSunken else lerp(surfaceColor, Color.Black, 0.12f)
    val filete = if (neutra) NeuColors.Hairline else Color.Transparent
    return this
        .dropShadow(shape) {
            val k = 1f - insetProgress().coerceIn(0f, 1f)
            radius = elevation.blur.toPx() * 0.9f * k
            offset = Offset(0f, elevation.offset.toPx() * 0.45f * k)
            color = if (neutra) NeuColors.ShadowSoft else surfaceColor
            alpha = (if (neutra) 1f else 0.35f) * k
        }
        .drawBehind {
            val p = insetProgress().coerceIn(0f, 1f)
            val o = shape.createOutline(size, layoutDirection, this)
            drawOutline(o, lerp(arriba, abajo, p))
            if (filete != Color.Transparent) drawOutline(o, filete, style = Stroke(1.dp.toPx()))
        }
}

/** Atajo para elementos estáticos hundidos (inputs, placeholders). */
fun Modifier.neumorphicInset(
    shape: Shape,
    elevation: NeuElevation = NeuDimens.ElevationMedium,
    surfaceColor: Color = NeuColors.Background,
): Modifier = neumorphic(shape, elevation, surfaceColor, insetProgress = InsetAlways)

/**
 * Convierte el estado "presionado" de un [InteractionSource] en un progreso
 * animado 0f → 1f para alimentar [neumorphic].
 *
 * @param forceInset mantiene el elemento hundido aunque no se esté tocando
 * (p. ej. un chip seleccionado).
 */
@Composable
fun animateInsetProgress(
    interactionSource: InteractionSource,
    forceInset: Boolean = false,
): State<Float> {
    val isPressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (isPressed || forceInset) 1f else 0f,
        animationSpec = tween(durationMillis = NeuDimens.PressAnimationMillis),
        label = "neuInsetProgress",
    )
}
