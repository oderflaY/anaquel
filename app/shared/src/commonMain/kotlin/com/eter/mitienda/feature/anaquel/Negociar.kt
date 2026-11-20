package com.eter.mitienda.feature.anaquel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.util.centsToInput
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.core.util.parseMoneyToCents

// Hoja para negociar: producto con foto, cantidad con atajos de mayoreo,
// descuento rápido, cuánto ahorras, qué tan probable es que acepten y mensajes listos.

private val Verde = Color(0xFF0E7C5A)
private val Ambar = Color(0xFFE0871B)
private val Rojo = Color(0xFFE5484D)

/** Lo que se negocia, venga de una oferta de la red o de un trato abierto. */
class Negociacion(
    val titulo: String, val tienda: String?, val foto: String?, val icono: ImageVector,
    val precioLista: Long, val precioMayoreo: Long?, val minimoMayoreo: Long?, val disponible: Long?,
    val cantidadInicial: Long, val precioInicial: Long,
    /** proponer (compras) | ofrecer (le vendes a quien busca) | contra (respondes un trato) */
    val modo: String,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HojaNegociacion(n: Negociacion, onCerrar: () -> Unit, onEnviar: (Long, Long, String) -> Unit) {
    val vendo = n.modo == "ofrecer"
    fun sugerido(c: Long): Long =
        if ((n.precioMayoreo ?: 0) > 0 && c >= (n.minimoMayoreo ?: Long.MAX_VALUE)) n.precioMayoreo!! else n.precioLista
    var cantidad by remember { mutableStateOf(n.cantidadInicial.coerceAtLeast(1)) }
    var precioTxt by remember { mutableStateOf(n.precioInicial.takeIf { it > 0 }?.let(::centsToInput).orEmpty()) }
    var mensaje by remember { mutableStateOf("") }
    val pc = parseMoneyToCents(precioTxt) ?: 0
    val referencia = if (n.modo == "contra") n.precioInicial else sugerido(cantidad)
    val ahorroPct = if (referencia > 0 && pc > 0) ((referencia - pc) * 100 / referencia).toInt() else 0
    // Qué tan probable es que acepten: cerca del precio de referencia, alta; muy abajo, baja.
    val prob = when {
        vendo -> 0.75f
        pc <= 0 -> 0f
        ahorroPct <= 0 -> 0.95f
        ahorroPct <= 5 -> 0.8f
        ahorroPct <= 10 -> 0.6f
        ahorroPct <= 18 -> 0.35f
        else -> 0.15f
    }
    val probAnim by animateFloatAsState(prob, tween(500, easing = FastOutSlowInEasing))
    val colorProb by animateColorAsState(if (prob >= 0.7f) Verde else if (prob >= 0.4f) Ambar else Rojo)
    ModalBottomSheet(
        onDismissRequest = onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NeuColors.Background, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- Producto ----------
            Row(Modifier.entrada(0), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(76.dp).shadow(8.dp, RoundedCornerShape(20.dp)).background(Color.White, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp)).padding(8.dp),
                    contentAlignment = Alignment.Center,
                ) { FotoRed(n.foto, n.titulo, Modifier.size(60.dp)) { Icon(n.icono, null, tint = Verde, modifier = Modifier.size(36.dp)) } }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(when (n.modo) { "contra" -> "CONTRAOFERTA"; "ofrecer" -> "OFRECER"; else -> "NEGOCIAR" },
                        color = Verde, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                    Text(n.titulo, fontSize = 20.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
                    n.tienda?.let { Text("con ${it.removeSuffix(" (demo)")}", fontSize = 13.sp, color = NeuColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            // ---------- Precios de referencia ----------
            if (n.precioLista > 0) Row(Modifier.entrada(1), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Referencia(if (n.modo == "contra") "Su propuesta" else "Precio de lista", formatPrice(if (n.modo == "contra") n.precioInicial else n.precioLista), Modifier.weight(1f))
                if ((n.precioMayoreo ?: 0) > 0 && n.modo != "contra") Referencia("Mayoreo desde ${n.minimoMayoreo}", formatPrice(n.precioMayoreo!!), Modifier.weight(1f), destacado = true)
                n.disponible?.let { Referencia("Disponibles", miles(it), Modifier.weight(1f)) }
            }
            // ---------- Cantidad ----------
            Seccion("¿Cuántas piezas?", Modifier.entrada(2)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BotonCircular(Icons.Rounded.Remove, "Menos") {
                        cantidad = (cantidad - pasoDe(cantidad)).coerceAtLeast(1)
                        if (n.modo != "contra") precioTxt = centsToInput(sugerido(cantidad))
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        NumeroAnimado(cantidad.toInt(), NeuColors.TextPrimary, 34.sp, FontWeight.Black)
                    }
                    BotonCircular(Icons.Rounded.Add, "Más") {
                        cantidad += pasoDe(cantidad)
                        if (n.modo != "contra") precioTxt = centsToInput(sugerido(cantidad))
                    }
                }
                val atajos = listOfNotNull(n.minimoMayoreo?.takeIf { it > 1 }, 12L, 24L, 48L, 100L).distinct().sorted().take(4)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    atajos.forEach { c ->
                        ChipVerde(if (c == n.minimoMayoreo) "$c · mayoreo" else "$c pzas", cantidad == c) {
                            cantidad = c
                            if (n.modo != "contra") precioTxt = centsToInput(sugerido(c))
                        }
                    }
                }
            }
            // ---------- Precio ----------
            Seccion(if (vendo) "¿A cuánto lo ofreces?" else "¿A cuánto lo quieres?", Modifier.entrada(3)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Color.White, RoundedCornerShape(16.dp))
                        .border(2.dp, colorProb.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextSecondary)
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        precioTxt, { precioTxt = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, cursorBrush = SolidColor(Verde),
                    )
                    Text("c/u", fontSize = 14.sp, color = NeuColors.TextSecondary)
                }
                if (!vendo && referencia > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 3, 5, 8, 12).forEach { d ->
                        val p = referencia * (100 - d) / 100
                        ChipVerde(if (d == 0) "Sin descuento" else "−$d%", pc == p) { precioTxt = centsToInput(p) }
                    }
                }
            }
            // ---------- Resumen: total, ahorro y probabilidad ----------
            Column(
                Modifier.entrada(4).fillMaxWidth().shadow(10.dp, RoundedCornerShape(20.dp), spotColor = Verde)
                    .background(Brush.linearGradient(listOf(Color(0xFF0E7C5A), Color(0xFF0B3D2E))), RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Total", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(formatPrice(cantidad * pc), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    }
                    if (!vendo && ahorroPct > 0 && referencia > 0) Column(horizontalAlignment = Alignment.End) {
                        Text(formatPrice(cantidad * referencia), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, textDecoration = TextDecoration.LineThrough)
                        Text("Ahorras ${formatPrice(cantidad * (referencia - pc))}", color = Color(0xFFB8F5DC), fontSize = 14.sp, fontWeight = FontWeight.Black,
                            modifier = Modifier.latido(fuerza = 0.04f))
                    }
                }
                if (!vendo) Column {
                    Row {
                        Text("Probabilidad de que acepte", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(when { prob >= 0.7f -> "Alta"; prob >= 0.4f -> "Media"; pc <= 0 -> "—"; else -> "Baja" },
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxWidth(probAnim.coerceIn(0.02f, 1f)).height(8.dp).background(colorProb, RoundedCornerShape(4.dp)))
                    }
                }
            }
            // ---------- Mensaje ----------
            Seccion("Mensaje (opcional)", Modifier.entrada(5)) {
                val listos = if (vendo) listOf("Te lo llevo hoy", "Tengo factura", "Precio especial por volumen")
                else listOf("Pago de contado", "Paso a recogerlo", "Te compro cada semana", "¿Me das crédito?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listos.forEach { m -> ChipVerde(m, mensaje.contains(m)) { mensaje = if (mensaje.contains(m)) mensaje.replace(m, "").replace(" · ·", " ·").trim(' ', '·') else listOf(mensaje, m).filter { it.isNotBlank() }.joinToString(" · ") } }
                }
                BasicTextField(
                    mensaje, { mensaje = it }, Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color.White, RoundedCornerShape(14.dp)).padding(14.dp),
                    textStyle = TextStyle(fontSize = 15.sp, color = NeuColors.TextPrimary), cursorBrush = SolidColor(Verde),
                    decorationBox = { campo -> if (mensaje.isEmpty()) Text("Escríbele algo…", color = NeuColors.TextSecondary, fontSize = 15.sp); campo() },
                )
            }
            // ---------- Enviar ----------
            BotonVerde(
                when (n.modo) { "contra" -> "Enviar contraoferta"; "ofrecer" -> "Enviar oferta"; else -> "Enviar propuesta" },
                Icons.Rounded.Send, Modifier.entrada(6), habilitado = pc > 0,
            ) { onEnviar(cantidad, pc, mensaje.trim()) }
        }
    }
}

private fun pasoDe(c: Long) = when { c < 12 -> 1L; c < 100 -> 6L; else -> 12L }

@Composable
private fun Seccion(titulo: String, modifier: Modifier, contenido: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titulo, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = NeuColors.TextPrimary)
        contenido()
    }
}

@Composable
private fun Referencia(titulo: String, valor: String, modifier: Modifier, destacado: Boolean = false) {
    Column(
        modifier.background(if (destacado) Verde.copy(alpha = 0.1f) else Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, if (destacado) Verde.copy(alpha = 0.4f) else Color.Transparent, RoundedCornerShape(14.dp)).padding(10.dp),
    ) {
        Text(titulo, fontSize = 11.sp, color = NeuColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(valor, fontSize = 16.sp, fontWeight = FontWeight.Black, color = if (destacado) Verde else NeuColors.TextPrimary, maxLines = 1)
    }
}
