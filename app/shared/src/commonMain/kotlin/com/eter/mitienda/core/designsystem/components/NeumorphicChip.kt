package com.eter.mitienda.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.eter.mitienda.core.designsystem.neumorphism.animateInsetProgress
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes

/**
 * Chip de filtro. Seleccionado = hundido + azul DIF, de modo que el estado
 * activo se reconoce por forma Y por color (no depende solo del color).
 */
@Composable
fun NeumorphicChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val insetProgress by animateInsetProgress(interactionSource, forceInset = selected)
    val containerColor by animateColorAsState(
        targetValue = if (selected) NeuColors.Magenta else NeuColors.Background,
        animationSpec = tween(NeuDimens.PressAnimationMillis),
        label = "chipColor",
    )

    Box(
        modifier = modifier
            .heightIn(min = NeuDimens.ChipHeight)
            .neumorphic(
                shape = NeuShapes.Pill,
                elevation = NeuDimens.ElevationSmall,
                surfaceColor = containerColor,
                insetProgress = { insetProgress },
            )
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = NeuDimens.SpaceLg + NeuDimens.SpaceXs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) NeuColors.OnAccent else NeuColors.TextPrimary,
        )
    }
}
