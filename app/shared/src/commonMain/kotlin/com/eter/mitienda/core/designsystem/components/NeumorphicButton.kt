package com.eter.mitienda.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import com.eter.mitienda.core.designsystem.neumorphism.animateInsetProgress
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuElevation
import com.eter.mitienda.core.designsystem.theme.NeuShapes

/** Escala mínima al presionar: refuerza la sensación de "hundirse". */
internal const val PRESSED_SCALE_DELTA = 0.03f

/**
 * Botón de acción con color de acento.
 *
 * Interacción: Outset en reposo → Inset al presionar, animado en
 * [NeuDimens.PressAnimationMillis] ms. Las sombras exteriores se desvanecen
 * mientras aparecen las interiores (ver `Modifier.neumorphic`).
 *
 * @param containerColor normalmente [NeuColors.Magenta] o [NeuColors.Blue]; el
 * texto toma el color legible de [NeuColors.contentColorFor].
 */
@Composable
fun NeumorphicButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = NeuColors.Magenta,
    icon: Painter? = null,
    enabled: Boolean = true,
    elevation: NeuElevation = NeuDimens.ElevationMedium,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val insetProgress by animateInsetProgress(interactionSource)
    // Blue = acción secundaria: botón blanco con filete y texto verde (como «Mensaje» en el perfil).
    val secundario = containerColor == NeuColors.Blue
    val contentColor = if (secundario) NeuColors.Magenta else NeuColors.contentColorFor(containerColor)

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = NeuDimens.ButtonHeight)
            .graphicsLayer {
                val scale = 1f - PRESSED_SCALE_DELTA * insetProgress
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.5f
            }
            .then(
                if (containerColor == NeuColors.Magenta) {
                    // Acción principal: mismo verde con degradado y brillo que las hojas nuevas.
                    Modifier
                        .shadow(if (enabled) 10.dp else 0.dp, NeuShapes.Large, spotColor = NeuColors.Magenta, ambientColor = NeuColors.Magenta)
                        .background(Brush.horizontalGradient(listOf(NeuColors.Blue, NeuColors.Magenta)), NeuShapes.Large)
                } else if (secundario) {
                    Modifier.background(NeuColors.Surface, NeuShapes.Large)
                        .border(1.5.dp, NeuColors.Magenta.copy(alpha = 0.55f), NeuShapes.Large)
                } else {
                    Modifier.neumorphic(
                        shape = NeuShapes.Large,
                        elevation = elevation,
                        surfaceColor = containerColor,
                        insetProgress = { insetProgress },
                    )
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = NeuDimens.SpaceXl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painter = icon,
                contentDescription = null, // el texto ya describe la acción
                modifier = Modifier.size(NeuDimens.IconSmallSize),
                tint = contentColor,
            )
            Spacer(Modifier.width(NeuDimens.SpaceSm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}
