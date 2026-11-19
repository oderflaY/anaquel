package com.eter.mitienda.core.designsystem.neumorphism

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
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
): Modifier = this
    // 1) Sombra oscura exterior: se desvanece a medida que el elemento se hunde.
    .dropShadow(shape) {
        val k = 1f - insetProgress().coerceIn(0f, 1f)
        val d = elevation.offset.toPx() * k
        radius = elevation.blur.toPx() * k
        offset = Offset(d, d)
        color = darkShadow
        alpha = k
    }
    // 2) Sombra clara exterior (dirección opuesta).
    .dropShadow(shape) {
        val k = 1f - insetProgress().coerceIn(0f, 1f)
        val d = elevation.offset.toPx() * k
        radius = elevation.blur.toPx() * k
        offset = Offset(-d, -d)
        color = lightShadow
        alpha = k
    }
    // 3) Superficie. Debe ir entre las sombras exteriores y las interiores.
    .background(surfaceColor, shape)
    // 4) Sombra oscura interior: aparece al hundirse.
    .innerShadow(shape) {
        val k = insetProgress().coerceIn(0f, 1f)
        val d = elevation.offset.toPx() * k
        radius = elevation.blur.toPx() * k
        offset = Offset(d, d)
        color = darkShadow
        alpha = k
    }
    // 5) Sombra clara interior.
    .innerShadow(shape) {
        val k = insetProgress().coerceIn(0f, 1f)
        val d = elevation.offset.toPx() * k
        radius = elevation.blur.toPx() * k
        offset = Offset(-d, -d)
        color = lightShadow
        alpha = k
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
