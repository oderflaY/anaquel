package com.eter.mitienda.feature.anaquel

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.theme.NeuColors
import kotlinx.coroutines.delay

// Chat con «Tu socio» como un chatbot normal: lo último abajo, la caja para
// escribir fija abajo y para recordar lo de antes se desliza hacia arriba.

private val Morado = Color(0xFF7B3FC4)

private val sugerenciasSocio = listOf(
    "¿Cómo va mi negocio?", "¿Qué me conviene surtir?", "¿Qué no se está moviendo?", "¿Qué compro para la temporada?",
)

@Composable
fun ChatSocio(st: AnaquelUiState, vm: AnaquelViewModel, modifier: Modifier, negociar: (ResultadoRed) -> Unit) {
    val mensajes = st.asesor.orEmpty()
    val lista = rememberLazyListState()
    var texto by remember { mutableStateOf("") }
    // Lista al revés: el índice 0 es lo de abajo (lo más nuevo); siempre se vuelve ahí al llegar algo.
    LaunchedEffect(mensajes.size, st.pensando) { lista.animateScrollToItem(0) }
    Column(modifier.fillMaxWidth().imePadding()) {
        // ---------- Encabezado ----------
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarSocio(40)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Tu socio", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
                Text(
                    if (st.pensando != null) "escribiendo…" else when (st.motorAsesor) {
                        "ia" -> "en línea · IA (Claude)"; "local" -> "en línea · IA de Anaquel"; else -> "en línea · asesor básico"
                    },
                    fontSize = 12.sp, color = if (st.pensando != null) Morado else NeuColors.TextSecondary,
                )
            }
            if (mensajes.isNotEmpty()) BotonCircular(Icons.Rounded.Refresh, "Conversación nueva", 38) { vm.conversacionNueva() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(NeuColors.Hairline))
        // ---------- Mensajes ----------
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = lista, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            st.pensando?.let { q ->
                item(key = "escribiendo") { Escribiendo() }
                item(key = "q-pendiente") { BurbujaMia(q) }
            }
            items(mensajes.asReversed(), key = { "m-" + it.id + it.rol }) { m ->
                if (m.rol == "user") BurbujaMia(m.texto) else BurbujaSocio(m, negociar)
            }
            item(key = "bienvenida") { Bienvenida(st) }
        }
        // ---------- Sugerencias y caja de texto ----------
        if (st.pensando == null) {
            val sug = st.plan?.preguntas?.takeIf { it.isNotEmpty() } ?: sugerenciasSocio
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sug) { s ->
                    Text(
                        s, fontSize = 13.sp, color = Morado, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Morado.copy(alpha = 0.08f))
                            .clickable { vm.preguntar(s) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(Color.White)
                    .border(1.dp, NeuColors.Hairline, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 13.dp),
            ) {
                BasicTextField(
                    texto, { texto = it }, Modifier.fillMaxWidth().heightIn(max = 120.dp),
                    textStyle = TextStyle(fontSize = 15.sp, color = NeuColors.TextPrimary), cursorBrush = SolidColor(Morado),
                    decorationBox = { campo -> if (texto.isEmpty()) Text("Pregúntale a tu socio…", fontSize = 15.sp, color = NeuColors.TextSecondary); campo() },
                )
            }
            Spacer(Modifier.width(8.dp))
            val puede = texto.isNotBlank() && st.pensando == null
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (puede) Morado else NeuColors.Hairline)
                    .clickable(enabled = puede) { vm.preguntar(texto); texto = "" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.Send, "Enviar", tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
    }
}

@Composable
private fun AvatarSocio(tam: Int) {
    Box(Modifier.size(tam.dp).clip(CircleShape).background(Morado), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size((tam * 0.55f).dp))
    }
}

/** Entrada suave de cada burbuja (desde su lado). */
@Composable
private fun Modifier.entraDesde(derecha: Boolean): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }
    return graphicsLayer { alpha = p.value.coerceIn(0f, 1f); translationX = (1f - p.value) * (if (derecha) 60f else -60f) }
}

@Composable
private fun BurbujaMia(t: String) {
    Row(Modifier.fillMaxWidth().entraDesde(true), horizontalArrangement = Arrangement.End) {
        Text(
            t, fontSize = 15.sp, color = Color.White, lineHeight = 20.sp,
            modifier = Modifier.padding(start = 56.dp)
                .background(Morado, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun FilaSocio(contenido: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().entraDesde(false), verticalAlignment = Alignment.Bottom) {
        AvatarSocio(28)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f, fill = false).padding(end = 28.dp)) { contenido() }
    }
}

private val formaSocio = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)

@Composable
private fun BurbujaSocio(m: MensajeAsesor, negociar: (ResultadoRed) -> Unit) {
    Column {
        FilaSocio {
            Text(
                limpiarMarkdown(m.texto), fontSize = 15.sp, color = NeuColors.TextPrimary, lineHeight = 21.sp,
                modifier = Modifier.background(Color.White, formaSocio).border(1.dp, NeuColors.Hairline, formaSocio)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (m.tarjetas.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            LazyRow(contentPadding = PaddingValues(start = 34.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(m.tarjetas.take(6)) { r -> TarjetaChica(r) { negociar(r) } }
            }
        }
    }
}

/** El modelo a veces manda **negritas** o viñetas con *: se ven como texto normal. */
private fun limpiarMarkdown(t: String) = t.replace("**", "").replace(Regex("(?m)^\\s*[*-] "), "• ").trim()

@Composable
private fun TarjetaChica(r: ResultadoRed, onNegociar: () -> Unit) {
    Column(
        Modifier.width(210.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
            .border(1.dp, NeuColors.Hairline, RoundedCornerShape(16.dp)).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Miniatura(r.foto, r.titulo, 40)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(r.titulo, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                Text(r.tienda?.nombre?.removeSuffix(" (demo)") ?: "", fontSize = 11.sp, color = NeuColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val precio = r.precioMayoreoC?.takeIf { it > 0 } ?: r.precioC
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(precio?.let { com.eter.mitienda.core.util.formatPrice(it) }, km(r.distanciaKm)).joinToString(" · "),
                Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NeuColors.TextPrimary, maxLines = 1,
            )
            if (r.tienda != null) Text(
                "Negociar", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(VerdeMarca).clickable(onClick = onNegociar)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** Tres puntitos brincando mientras contesta (y cuánto lleva). */
@Composable
private fun Escribiendo() {
    var seg by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); seg++ } }
    val t = rememberInfiniteTransition()
    FilaSocio {
        Column {
            Row(
                Modifier.background(Color.White, formaSocio).border(1.dp, NeuColors.Hairline, formaSocio).padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                repeat(3) { i ->
                    val y by t.animateFloat(0f, -6f, infiniteRepeatable(tween(380, delayMillis = i * 130), RepeatMode.Reverse))
                    Box(Modifier.offset(y = y.dp).size(8.dp).background(Morado.copy(alpha = 0.7f), CircleShape))
                }
            }
            if (seg >= 3) Text(
                if (seg < 15) "Revisando tus números y la red…" else "Ya casi, pensando bien la respuesta… (${seg} s)",
                fontSize = 11.sp, color = NeuColors.TextSecondary, modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

/** Primer mensaje: saludo y lo urgente del día (lo que antes era el tablero de arriba). */
@Composable
private fun Bienvenida(st: AnaquelUiState) {
    val p = st.plan
    FilaSocio {
        Column(
            Modifier.widthIn(max = 320.dp).background(Color.White, formaSocio).border(1.dp, NeuColors.Hairline, formaSocio)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(p?.saludo?.takeIf { it.isNotBlank() } ?: "¡Hola! Soy tu socio.", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
            Text("Conozco tu inventario, tus ventas y la red. Pregúntame lo que quieras.", fontSize = 14.sp, color = NeuColors.TextSecondary, lineHeight = 19.sp)
            p?.alertas?.take(3)?.forEach { a ->
                Row {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).background(if (a.tipo == "urgente") Color(0xFFE5484D) else Morado, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(a.titulo + if (a.texto.isNotBlank()) ": " + a.texto else "", fontSize = 13.sp, color = NeuColors.TextPrimary, lineHeight = 18.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
