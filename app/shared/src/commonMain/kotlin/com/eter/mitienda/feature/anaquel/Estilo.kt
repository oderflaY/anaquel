package com.eter.mitienda.feature.anaquel

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.theme.NeuColors

// Piezas comunes del estilo Anaquel (plano, blanco, acento verde) para que
// negociar, pedir, chat y perfiles se vean como una sola app.

internal val VerdeMarca = Color(0xFF0E7C5A)
internal val VerdeClaro = Color(0xFF12A374)

/** Hoja inferior con el mismo fondo, esquinas y título en todas las pantallas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HojaAnaquel(
    titulo: String?, etiqueta: String? = null, onCerrar: () -> Unit,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NeuColors.Background, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
            etiqueta?.let { Text(it.uppercase(), color = VerdeMarca, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp) }
            titulo?.let {
                Text(it, fontSize = 22.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary, lineHeight = 26.sp)
                Spacer(Modifier.heightIn(min = 12.dp))
            }
            contenido()
        }
    }
}

@Composable
internal fun ChipVerde(texto: String, activo: Boolean, onClick: () -> Unit) {
    val fondo by animateColorAsState(if (activo) VerdeMarca else Color.White)
    val tinta by animateColorAsState(if (activo) Color.White else NeuColors.TextPrimary)
    val fuente = remember { MutableInteractionSource() }
    Text(
        texto, color = tinta, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.rebote(fuente, 0.9f).clip(CircleShape).background(fondo)
            .border(1.dp, if (activo) VerdeMarca else NeuColors.Hairline, CircleShape)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
internal fun BotonCircular(icono: ImageVector, descripcion: String, tam: Int = 52, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Box(
        Modifier.size(tam.dp).rebote(fuente, 0.85f).shadow(4.dp, CircleShape, spotColor = NeuColors.ShadowSoft)
            .background(Color.White, CircleShape).border(1.dp, NeuColors.Hairline, CircleShape)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = descripcion },
        contentAlignment = Alignment.Center,
    ) { Icon(icono, null, tint = VerdeMarca, modifier = Modifier.size((tam * 0.5f).dp)) }
}

/** Botón principal: verde con degradado, sombra verde y rebote. */
@Composable
internal fun BotonVerde(
    texto: String, icono: ImageVector? = null, modifier: Modifier = Modifier, habilitado: Boolean = true, onClick: () -> Unit,
) {
    val fuente = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (habilitado) 1f else 0.45f).rebote(fuente, 0.96f)
            .shadow(if (habilitado) 12.dp else 0.dp, RoundedCornerShape(18.dp), spotColor = VerdeMarca)
            .background(Brush.horizontalGradient(listOf(VerdeClaro, VerdeMarca)), RoundedCornerShape(18.dp))
            .clickable(interactionSource = fuente, indication = null, enabled = habilitado, onClick = onClick)
            .semantics { role = Role.Button },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(texto, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Black)
        icono?.let {
            Spacer(Modifier.width(10.dp))
            Icon(it, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** Miniatura de producto: foto real sobre gris claro o su ícono. */
@Composable
internal fun Miniatura(foto: String?, nombre: String, tam: Int = 52) {
    Box(
        Modifier.size(tam.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF4F6F5)).padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        FotoRed(foto, nombre, Modifier.size((tam - 10).dp), tinte = Color(0xFFF4F6F5)) {
            Icon(iconoDe(nombre), null, tint = VerdeMarca.copy(alpha = 0.6f), modifier = Modifier.size((tam * 0.5f).dp))
        }
    }
}
