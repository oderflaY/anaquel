package com.eter.mitienda.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import com.eter.mitienda.core.designsystem.neumorphism.animateInsetProgress
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuElevation
import com.eter.mitienda.core.designsystem.theme.NeuShapes

/**
 * Contenedor extruido base para productos o grupos de información.
 *
 * Si recibe [onClick], la tarjeta se hunde al presionarla (misma transición que
 * los botones); si no, es una superficie estática.
 *
 * Nota: las sombras se dibujan FUERA de los límites del componente. El padre no
 * debe recortar (`clip`) y las listas deben dar `contentPadding` suficiente.
 */
@Composable
fun NeumorphicCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = NeuShapes.Large,
    elevation: NeuElevation = NeuDimens.ElevationLarge,
    contentPadding: PaddingValues = PaddingValues(NeuDimens.SpaceLg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val insetProgress by animateInsetProgress(interactionSource)

    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null, // el "hundimiento" sustituye al ripple
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .neumorphic(shape = shape, elevation = elevation, insetProgress = { insetProgress })
            .then(clickModifier)
            .padding(contentPadding),
        content = content,
    )
}
