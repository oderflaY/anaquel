package com.eter.mitienda.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.eter.mitienda.core.designsystem.neumorphism.neumorphicInset
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes

/**
 * Campo de texto hundido (inset), el "agujero" del neumorfismo.
 *
 * Usa [BasicTextField] en lugar de `TextField` de Material para controlar el
 * fondo por completo: el `decorationBox` pinta el hueco y coloca los íconos.
 *
 * @param leadingIcon ícono decorativo a la izquierda (p. ej. lupa).
 * @param trailingIcon ícono o acción a la derecha (p. ej. botón de escáner).
 */
@Composable
fun NeumorphicInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val textStyle = MaterialTheme.typography.bodyLarge

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = NeuColors.TextPrimary),
        cursorBrush = SolidColor(NeuColors.TextPrimary),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .neumorphicInset(NeuShapes.Large)
                    .heightIn(min = NeuDimens.InputHeight)
                    .padding(
                        start = NeuDimens.SpaceLg,
                        // Con acción a la derecha se reduce el margen para que el
                        // botón quede "dentro" del hueco con aire simétrico.
                        end = if (trailingIcon != null) NeuDimens.SpaceSm else NeuDimens.SpaceLg,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(Modifier.width(NeuDimens.SpaceMd))
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = NeuColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (trailingIcon != null) {
                    Spacer(Modifier.width(NeuDimens.SpaceSm))
                    trailingIcon()
                }
            }
        },
    )
}
