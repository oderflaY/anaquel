package com.eter.mitienda.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.eter.mitienda.core.designsystem.theme.NeuColors

private val Rojo = Color(0xFFE5484D)

/**
 * Diálogo único de la app: tarjeta blanca, título grande, botón secundario
 * blanco con filete y botón principal verde (o rojo si [peligro]).
 */
@Composable
fun DialogoAnaquel(
    titulo: String,
    confirmar: String,
    onConfirmar: () -> Unit,
    onCerrar: () -> Unit,
    texto: String? = null,
    peligro: Boolean = false,
    cancelar: String = "Cancelar",
    contenido: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onCerrar) {
        Column(
            Modifier.fillMaxWidth().shadow(24.dp, RoundedCornerShape(26.dp), spotColor = NeuColors.ShadowSoft)
                .background(NeuColors.Surface, RoundedCornerShape(26.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(titulo, fontSize = 21.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary, lineHeight = 25.sp)
            texto?.let { Text(it, fontSize = 15.sp, color = NeuColors.TextSecondary, lineHeight = 21.sp) }
            contenido?.invoke(this)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Boton(cancelar, false, Rojo, Modifier.weight(1f), onCerrar)
                Boton(confirmar, true, if (peligro) Rojo else NeuColors.Magenta, Modifier.weight(1f), onConfirmar)
            }
        }
    }
}

@Composable
private fun Boton(texto: String, principal: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val forma = RoundedCornerShape(16.dp)
    Box(
        modifier.heightIn(min = 50.dp).clip(forma)
            .then(
                if (principal) Modifier.background(
                    if (color == NeuColors.Magenta) Brush.horizontalGradient(listOf(NeuColors.Blue, NeuColors.Magenta))
                    else Brush.horizontalGradient(listOf(color, color)), forma,
                ) else Modifier.background(NeuColors.Surface, forma).border(1.dp, NeuColors.InputBorder, forma),
            )
            .clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (principal) Color.White else NeuColors.TextPrimary, maxLines = 1)
    }
}
