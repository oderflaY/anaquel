package com.eter.mitienda.feature.anaquel

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.util.centsToInput
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.core.util.parseMoneyToCents

// «Vender al mayoreo»: la tiendita también publica sus precios de mayoreo,
// como los distribuidores, y decide si los ve toda la red o solo quien ella elija.

private val Morado = Color(0xFF6D4AFF)

/** Lista de la sección. [filtro]: todos | publicados | solo | sin */
@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.mayoreo(
    st: AnaquelUiState, filtro: String, busqueda: String,
    onFiltro: (String) -> Unit, onBuscar: (String) -> Unit, onEditar: (ProductoMayoreo) -> Unit,
) {
    val m = st.mayoreo
    if (m == null) {
        item(key = "may-cargando") { TarjetasCargando("Cargando tus productos…") }
        return
    }
    val publicados = m.productos.count { it.publicado }
    val soloElegidos = m.productos.count { it.publicado && it.audiencia == "elegidos" }
    item(key = "may-resumen") { ResumenMayoreo(publicados, soloElegidos, m.productos.size) }
    if (m.productos.isEmpty()) {
        item(key = "may-vacio") {
            Txt("Aún no hay productos en tu inventario de la nube. Da de alta productos y toca «Respaldar» arriba para poder publicarlos.", secundario = true)
        }
        return
    }
    item(key = "may-filtros") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CampoBusqueda(busqueda, onBuscar)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ChipVerde("Todos", filtro == "todos") { onFiltro("todos") }
                ChipVerde("Publicados $publicados", filtro == "publicados") { onFiltro("publicados") }
                ChipVerde("Solo elegidos $soloElegidos", filtro == "solo") { onFiltro("solo") }
                ChipVerde("Sin publicar", filtro == "sin") { onFiltro("sin") }
            }
        }
    }
    val q = busqueda.trim().lowercase()
    val lista = m.productos.filter { p ->
        (q.isEmpty() || p.nombre.lowercase().contains(q) || p.categoria.lowercase().contains(q)) && when (filtro) {
            "publicados" -> p.publicado
            "solo" -> p.publicado && p.audiencia == "elegidos"
            "sin" -> !p.publicado
            else -> true
        }
    }
    if (lista.isEmpty()) item(key = "may-nada") { Txt("No hay productos con ese filtro.", secundario = true) }
    items(lista, key = { "may-${it.id}" }) { p -> FilaMayoreo(p, m.tiendas, onClick = { onEditar(p) }) }
}

@Composable
private fun ResumenMayoreo(publicados: Int, solo: Int, total: Int) {
    Column(
        Modifier.fillMaxWidth().entrada().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF12A374), Color(0xFF0B3D2E)))).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vende como distribuidor", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text("Pon precio de mayoreo a lo que te sobra o te traen los productores. Tú decides si lo ve toda la red o solo las tiendas que elijas.",
            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Cifra("$publicados", "publicados", Modifier.weight(1f))
            Cifra("${publicados - solo}", "toda la red", Modifier.weight(1f))
            Cifra("$solo", "solo elegidos", Modifier.weight(1f))
        }
        if (total > 0 && publicados == 0) Text("Toca un producto para publicarlo.", color = Color(0xFFB8F5DC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Cifra(valor: String, etiqueta: String, modifier: Modifier) {
    Column(modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(vertical = 10.dp, horizontal = 12.dp)) {
        Text(valor, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text(etiqueta, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun CampoBusqueda(texto: String, onCambio: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, NeuColors.Hairline, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = NeuColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            texto, onCambio, Modifier.weight(1f), singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = NeuColors.TextPrimary), cursorBrush = SolidColor(VerdeMarca),
            decorationBox = { campo -> if (texto.isEmpty()) Text("Buscar producto o categoría", color = NeuColors.TextSecondary, fontSize = 15.sp); campo() },
        )
    }
}

@Composable
private fun FilaMayoreo(p: ProductoMayoreo, tiendas: List<TiendaElegible>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White)
            .border(1.dp, if (p.publicado) VerdeMarca.copy(alpha = 0.35f) else NeuColors.Hairline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Miniatura(p.foto, p.nombre, 56)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.nombre, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val precios = if (p.publicado && p.precioMayoreoC > 0)
                "${formatPrice(p.precioMayoreoC)} desde ${p.minimoMayoreo} · menudeo ${formatPrice(p.precioC)}"
            else "Menudeo ${formatPrice(p.precioC)}" + (p.stock?.let { " · tienes $it" } ?: "")
            Text(precios, fontSize = 12.sp, color = NeuColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.publicado) {
                val (icono, texto, color) = if (p.audiencia == "elegidos") {
                    val nombres = p.elegidos.mapNotNull { id -> tiendas.firstOrNull { it.id == id }?.nombre?.removeSuffix(" (demo)") }
                    Triple(Ico.Candado, if (nombres.size <= 1) "Solo ${nombres.firstOrNull() ?: "1 tienda"}" else "Solo ${nombres.size} tiendas", Morado)
                } else Triple(Ico.Mundo, "Toda la red", VerdeMarca)
                Etiqueta(icono, texto, color)
            } else Etiqueta(null, "Sin publicar", NeuColors.TextSecondary)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = NeuColors.TextSecondary)
    }
}

@Composable
private fun Etiqueta(icono: ImageVector?, texto: String, color: Color) {
    Row(
        Modifier.background(color.copy(alpha = 0.1f), CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icono?.let { Icon(it, null, tint = color, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
        Text(texto, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- Hoja para publicar ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HojaMayoreo(
    p: ProductoMayoreo, tiendas: List<TiendaElegible>, ocupado: Boolean, onCerrar: () -> Unit, onGuardar: (CambioMayoreo) -> Unit,
) {
    val sugerido = if (p.precioMayoreoC > 0) p.precioMayoreoC else p.precioC * 85 / 100
    var precioTxt by remember { mutableStateOf(sugerido.takeIf { it > 0 }?.let(::centsToInput).orEmpty()) }
    var minimo by remember { mutableStateOf(p.minimoMayoreo.takeIf { it > 0 } ?: 6L) }
    var audiencia by remember { mutableStateOf(p.audiencia) }
    var elegidos by remember { mutableStateOf(p.elegidos.toSet()) }
    var filtroTiendas by remember { mutableStateOf("") }
    val pc = parseMoneyToCents(precioTxt) ?: 0
    val ganancia = if (p.precioC > 0 && pc > 0) ((p.precioC - pc) * 100 / p.precioC).toInt() else 0
    ModalBottomSheet(
        onDismissRequest = onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NeuColors.Background, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Miniatura(p.foto, p.nombre, 72)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (p.publicado) "EDITAR PUBLICACIÓN" else "PUBLICAR AL MAYOREO", color = VerdeMarca, fontSize = 11.sp,
                        fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
                    Text(p.nombre, fontSize = 20.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
                    Text("Menudeo ${formatPrice(p.precioC)}" + (p.stock?.let { " · tienes ${miles(it)} ${p.unidad}" } ?: ""),
                        fontSize = 13.sp, color = NeuColors.TextSecondary)
                }
            }

            Bloque("Precio de mayoreo") {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).background(Color.White, RoundedCornerShape(16.dp))
                        .border(1.5.dp, VerdeMarca.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextSecondary)
                    Spacer(Modifier.width(6.dp))
                    BasicTextField(
                        precioTxt, { precioTxt = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, cursorBrush = SolidColor(VerdeMarca),
                    )
                    Text("c/u", fontSize = 14.sp, color = NeuColors.TextSecondary)
                }
                if (p.precioC > 0) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 20, 25).forEach { d ->
                        val v = p.precioC * (100 - d) / 100
                        ChipVerde("−$d%", pc == v) { precioTxt = centsToInput(v) }
                    }
                }
                if (ganancia > 0) Txt("Quien te compre ahorra $ganancia% contra tu precio de menudeo.", secundario = true, chico = true)
            }

            Bloque("¿Desde cuántas piezas?") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BotonCircular(Icons.Rounded.Remove, "Menos") { minimo = (minimo - if (minimo > 12) 6 else 1).coerceAtLeast(1) }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        NumeroAnimado(minimo.toInt(), NeuColors.TextPrimary, 34.sp, FontWeight.Black)
                    }
                    BotonCircular(Icons.Rounded.Add, "Más") { minimo += if (minimo >= 12) 6 else 1 }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3L, 6L, 12L, 24L, 50L).forEach { c -> ChipVerde("$c ${p.unidad}", minimo == c) { minimo = c } }
                }
            }

            Bloque("¿Quién lo puede ver?") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OpcionAudiencia(Ico.Mundo, "Toda la red", "Cualquier tienda lo encuentra", audiencia == "todos", VerdeMarca, Modifier.weight(1f)) { audiencia = "todos" }
                    OpcionAudiencia(Ico.Candado, "Solo los que elija", "Precio exclusivo", audiencia == "elegidos", Morado, Modifier.weight(1f)) { audiencia = "elegidos" }
                }
            }

            if (audiencia == "elegidos") Bloque("Elige las tiendas (${elegidos.size})") {
                if (tiendas.size > 6) CampoBusqueda(filtroTiendas) { filtroTiendas = it }
                val q = filtroTiendas.trim().lowercase()
                val visibles = tiendas.filter { q.isEmpty() || it.nombre.lowercase().contains(q) || it.giro.lowercase().contains(q) }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, NeuColors.Hairline, RoundedCornerShape(18.dp)),
                ) {
                    visibles.forEachIndexed { i, t ->
                        val on = t.id in elegidos
                        Row(
                            Modifier.fillMaxWidth().clickable { elegidos = if (on) elegidos - t.id else elegidos + t.id }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(38.dp).background(Morado.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                                Text(iniciales(t.nombre), color = Morado, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.nombre.removeSuffix(" (demo)"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(t.giro.takeIf { it.isNotBlank() }, "ya tratan".takeIf { t.contacto }).joinToString(" · "),
                                    fontSize = 12.sp, color = if (t.contacto) VerdeMarca else NeuColors.TextSecondary)
                            }
                            Marca(on)
                        }
                        if (i < visibles.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 64.dp).height(1.dp).background(NeuColors.Hairline))
                    }
                }
            }

            val valido = pc > 0 && (p.precioC <= 0 || pc <= p.precioC) && (audiencia == "todos" || elegidos.isNotEmpty())
            val aviso = when {
                pc <= 0 -> "Pon el precio de mayoreo"
                p.precioC in 1 until pc -> "El mayoreo debe ser más barato que tu menudeo (${formatPrice(p.precioC)})"
                audiencia == "elegidos" && elegidos.isEmpty() -> "Elige al menos una tienda"
                else -> null
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                aviso?.let { Text(it, color = Color(0xFFE5484D), fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                BotonVerde(
                    when {
                        ocupado -> "Guardando…"
                        audiencia == "elegidos" -> "Publicar solo para ${elegidos.size} ${if (elegidos.size == 1) "tienda" else "tiendas"}"
                        p.publicado -> "Guardar cambios"
                        else -> "Publicar en toda la red"
                    },
                    if (audiencia == "elegidos") Ico.Candado else Ico.Mundo, habilitado = valido && !ocupado,
                ) { onGuardar(CambioMayoreo(true, pc, minimo, audiencia, if (audiencia == "elegidos") elegidos.toList() else emptyList())) }
                if (p.publicado) Text(
                    "Quitar de la red", color = Color(0xFFE5484D), fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !ocupado) {
                        onGuardar(CambioMayoreo(false, p.precioMayoreoC, p.minimoMayoreo, p.audiencia, p.elegidos))
                    }.padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun Bloque(titulo: String, contenido: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(titulo, fontSize = 15.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
        contenido()
    }
}

@Composable
private fun OpcionAudiencia(icono: ImageVector, titulo: String, sub: String, activo: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val borde by animateColorAsState(if (activo) color else NeuColors.Hairline)
    val fondo by animateColorAsState(if (activo) color.copy(alpha = 0.08f) else Color.White)
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(fondo).border(if (activo) 2.dp else 1.dp, borde, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(color.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icono, null, tint = color, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Marca(activo, color)
        }
        Text(titulo, fontSize = 14.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
        Text(sub, fontSize = 12.sp, color = NeuColors.TextSecondary, lineHeight = 15.sp)
    }
}

@Composable
private fun Marca(on: Boolean, color: Color = Morado) {
    Box(
        Modifier.size(24.dp).background(if (on) color else Color.Transparent, CircleShape).border(2.dp, if (on) color else Color(0x33000000), CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) }
}

private fun iniciales(n: String) = n.removeSuffix(" (demo)").split(" ").filter { it.length > 2 || (it.isNotEmpty() && it.first().isUpperCase()) }
    .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "T" }
