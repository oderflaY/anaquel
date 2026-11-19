package com.eter.mitienda.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eter.mitienda.core.designsystem.neumorphism.animateInsetProgress
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuElevation
import com.eter.mitienda.core.designsystem.theme.NeuShapes

/**
 * Botón circular para acciones secundarias ("Ajustes", "Sincronizar Bluetooth").
 *
 * @param contentDescription obligatorio: sin texto visible, es lo único que
 * leen TalkBack/VoiceOver.
 */
@Composable
fun NeumorphicIconButton(
    icon: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = NeuDimens.IconButtonSize,
    iconSize: Dp = NeuDimens.IconSize,
    containerColor: Color = NeuColors.Background,
    iconTint: Color = NeuColors.contentColorFor(containerColor),
    elevation: NeuElevation = NeuDimens.ElevationMedium,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val insetProgress by animateInsetProgress(interactionSource)

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val scale = 1f - PRESSED_SCALE_DELTA * insetProgress
                scaleX = scale
                scaleY = scale
            }
            .neumorphic(
                shape = NeuShapes.Circle,
                elevation = elevation,
                surfaceColor = containerColor,
                insetProgress = { insetProgress },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = iconTint,
        )
    }
}

/**
 * FAB neumórfico: [NeumorphicIconButton] grande, en magenta DIF y con la
 * elevación máxima para destacar como acción principal de la pantalla.
 */
@Composable
fun NeumorphicFab(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NeumorphicIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        size = NeuDimens.FabSize,
        iconSize = 30.dp,
        containerColor = NeuColors.Magenta,
        elevation = NeuDimens.ElevationLarge,
    )
}
