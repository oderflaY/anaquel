package com.eter.mitienda.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.neumorphism.animateInsetProgress
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes

data class BottomBarItem(
    val key: String,
    val label: String,
    val icon: Painter,
    /** Número en la burbuja (p. ej. pedidos urgentes); 0 = sin burbuja. */
    val badge: Int = 0,
)

/** Barra inferior neumórfica: la pestaña activa se ve hundida y en magenta. */
@Composable
fun NeumorphicBottomBar(
    items: List<BottomBarItem>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .neumorphic(RoundedCornerShape(topStart = NeuDimens.SpaceXl, topEnd = NeuDimens.SpaceXl), NeuDimens.ElevationMedium)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = NeuDimens.SpaceSm, vertical = NeuDimens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            BarTab(item, selected = item.key == selectedKey, onClick = { onSelect(item.key) }, modifier = Modifier.weight(1f), compacta = items.size > 4)
        }
    }
}

@Composable
private fun BarTab(item: BottomBarItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier, compacta: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val inset by animateInsetProgress(interaction, forceInset = selected)
    val color = if (selected) NeuColors.Magenta else NeuColors.TextSecondary

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .neumorphic(NeuShapes.Large, NeuDimens.ElevationSmall, insetProgress = { inset })
                .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
                .padding(horizontal = if (compacta) NeuDimens.SpaceSm else NeuDimens.SpaceLg, vertical = NeuDimens.SpaceSm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Icon(item.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                if (item.badge > 0) {
                    Text(
                        item.badge.toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.OnAccent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-6).dp)
                            .background(NeuColors.Magenta, NeuShapes.Pill)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                item.label,
                style = if (compacta) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
