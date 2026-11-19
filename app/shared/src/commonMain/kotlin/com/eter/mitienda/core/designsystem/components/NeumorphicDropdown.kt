package com.eter.mitienda.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eter.mitienda.core.designsystem.neumorphism.neumorphicInset
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_expand_more
import org.jetbrains.compose.resources.painterResource

/**
 * Selector desplegable con el mismo hueco (inset) que [NeumorphicInput], para
 * que en un formulario todos los campos se lean como iguales.
 *
 * Es genérico: [T] puede ser nulo para ofrecer una opción "ninguno".
 */
@Composable
fun <T> NeumorphicDropdown(
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var fieldWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { fieldWidthPx = it.width }
                .neumorphicInset(NeuShapes.Large)
                .clickable(role = Role.DropdownList) { expanded = true }
                .heightIn(min = NeuDimens.InputHeight)
                .padding(horizontal = NeuDimens.SpaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = optionLabel(selected),
                style = MaterialTheme.typography.bodyLarge,
                color = NeuColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(NeuDimens.SpaceSm))
            Icon(
                painter = painterResource(Res.drawable.ic_expand_more),
                contentDescription = null,
                tint = NeuColors.TextSecondary,
                modifier = Modifier
                    .size(NeuDimens.IconSize)
                    .rotate(if (expanded) 180f else 0f),
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(with(density) { fieldWidthPx.toDp() }.coerceAtLeast(160.dp)),
            shape = NeuShapes.Large,
            containerColor = NeuColors.Background,
            shadowElevation = 8.dp,
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            text = optionLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = NeuColors.TextPrimary,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
