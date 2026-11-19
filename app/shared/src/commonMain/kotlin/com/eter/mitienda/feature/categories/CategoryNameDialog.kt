package com.eter.mitienda.feature.categories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.window.Dialog
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicInput
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes

/**
 * Diálogo neumórfico para crear o renombrar una categoría. El error (p. ej.
 * nombre repetido) llega del ViewModel y el diálogo sigue abierto para corregir.
 */
@Composable
fun CategoryNameDialog(
    title: String,
    confirmLabel: String,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
) {
    var name by rememberSaveable { mutableStateOf(initialName) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NeuDimens.SpaceLg)
                .neumorphic(NeuShapes.Large, NeuDimens.ElevationLarge)
                .padding(NeuDimens.SpaceXl),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
            Spacer(Modifier.size(NeuDimens.SpaceLg))
            NeumorphicInput(
                value = name,
                onValueChange = { if (it.length <= 40) name = it },
                placeholder = "Ej. Arte y manualidades",
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onConfirm(name) }),
            )
            if (error != null) {
                Spacer(Modifier.size(NeuDimens.SpaceSm))
                Text(error, style = MaterialTheme.typography.bodySmall, color = NeuColors.OnLowStock)
            }
            Spacer(Modifier.size(NeuDimens.SpaceXl))
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicButton(
                    text = "Cancelar",
                    onClick = onDismiss,
                    containerColor = NeuColors.Background,
                    modifier = Modifier.weight(1f),
                )
                NeumorphicButton(
                    text = confirmLabel,
                    onClick = { onConfirm(name) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
