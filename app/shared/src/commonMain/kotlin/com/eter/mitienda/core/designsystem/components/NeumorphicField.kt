package com.eter.mitienda.core.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens

/**
 * Envoltorio de formulario: etiqueta fija arriba + control + mensaje de error.
 *
 * La etiqueta es fija (no solo placeholder) porque el placeholder desaparece al
 * escribir y el usuario pierde el contexto del campo.
 */
@Composable
fun NeumorphicField(
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NeuColors.TextSecondary,
            modifier = Modifier.padding(start = NeuDimens.SpaceXs, bottom = NeuDimens.SpaceSm),
        )
        content()
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = NeuColors.OnLowStock,
                modifier = Modifier.padding(start = NeuDimens.SpaceXs, top = NeuDimens.SpaceSm),
            )
        }
    }
}
