package com.eter.mitienda.feature.anaquel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicChip
import com.eter.mitienda.core.designsystem.components.NeumorphicInput
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.util.centsToInput
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.core.util.parseMoneyToCents

// Red de comercio local en la app: asesor, búsqueda en la red y tratos.

internal fun miles(n: Long): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

@Composable
private fun T(t: String, secundario: Boolean = false, negritas: Boolean = false, chico: Boolean = false) = Text(
    t,
    style = if (chico) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    fontWeight = if (negritas) FontWeight.Bold else null,
    color = if (secundario) NeuColors.TextSecondary else NeuColors.TextPrimary,
)

@Composable
private fun Etiqueta(t: String) = Text(
    t, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = NeuColors.OnChip,
    modifier = Modifier.background(NeuColors.ChipBackground, NeuShapes.Large).padding(horizontal = 8.dp, vertical = 2.dp),
)

// ---------------- Asesor ----------------

private val sugerenciasAsesor = listOf(
    "¿Qué compro para la temporada que viene?", "¿Cómo va mi negocio?", "¿Qué no se está moviendo?", "Busco gorras de mayoreo",
)

internal fun LazyListScope.asesor(st: AnaquelUiState, vm: AnaquelViewModel, negociar: (ResultadoRed) -> Unit) {
    item(key = "asesor-cab") {
        Column(verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm)) {
            T("Conoce tu inventario, tus ventas y la temporada de tu giro, y busca por ti en la red.", secundario = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Etiqueta(if (st.motorAsesor == "ia") "Con IA (Claude)" else "Asesor básico, sin IA")
                Spacer(Modifier.weight(1f))
                if (!st.asesor.isNullOrEmpty()) TextButton(onClick = vm::conversacionNueva) { Text("Conversación nueva", color = NeuColors.TextSecondary) }
            }
        }
    }
    if (st.asesor == null) item(key = "asesor-cargando") { T("Cargando…", secundario = true) }
    if (st.asesor?.isEmpty() == true && st.pensando == null) {
        item(key = "asesor-vacio") {
            NeumorphicCard(Modifier.fillMaxWidth()) {
                T("¿En qué te ayudo hoy?", negritas = true)
                T("Pregúntame qué comprar para la temporada, cómo va tu negocio o dime qué buscas y lo encuentro en la red.", secundario = true)
            }
        }
    }
    items(st.asesor.orEmpty(), key = { "am-" + it.id }) { m -> BurbujaAsesor(m, negociar) }
    st.pensando?.let { q ->
        item(key = "asesor-q") { BurbujaUsuario(q) }
        item(key = "asesor-pensando") { NeumorphicCard(Modifier.fillMaxWidth()) { T("Revisando tu negocio y la red…", secundario = true) } }
    }
    item(key = "asesor-sug") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
            items(sugerenciasAsesor) { s -> NeumorphicChip(s, selected = false, onClick = { vm.preguntar(s) }) }
        }
    }
    item(key = "asesor-input") {
        var texto by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeumorphicInput(texto, { texto = it }, Modifier.weight(1f), placeholder = "Escribe tu pregunta…", singleLine = false)
            Spacer(Modifier.width(NeuDimens.SpaceMd))
            NeumorphicButton("Preguntar", enabled = st.pensando == null && texto.isNotBlank(), onClick = { vm.preguntar(texto); texto = "" })
        }
    }
}

@Composable
private fun BurbujaUsuario(t: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            t, color = NeuColors.OnAccent,
            modifier = Modifier.padding(start = 48.dp).background(NeuColors.Magenta, NeuShapes.Large).padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun BurbujaAsesor(m: MensajeAsesor, negociar: (ResultadoRed) -> Unit) {
    if (m.rol == "user") return BurbujaUsuario(m.texto)
    var verPasos by remember { mutableStateOf(false) }
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Text(m.texto.replace("**", ""), color = NeuColors.TextPrimary, style = MaterialTheme.typography.bodyMedium)
        if (m.pasos.isNotEmpty()) {
            TextButton(onClick = { verPasos = !verPasos }) { Text("Lo que hizo tu asesor (${m.pasos.size})", color = NeuColors.TextSecondary) }
            if (verPasos) m.pasos.forEach { T("• $it", secundario = true, chico = true) }
        }
        m.tarjetas.forEach { r ->
            Spacer(Modifier.height(NeuDimens.SpaceMd))
            TarjetaRed(r, negociar, compacta = true)
        }
    }
}

// ---------------- Red ----------------

internal fun LazyListScope.red(st: AnaquelUiState, vm: AnaquelViewModel, negociar: (ResultadoRed) -> Unit) {
    item(key = "red-buscar") {
        var q by remember(st.consultaRed) { mutableStateOf(st.consultaRed) }
        Column(verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm)) {
            T("Lo que venden, anuncian y buscan las tiendas, proveedores y productores de tu ciudad.", secundario = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeumorphicInput(q, { q = it }, Modifier.weight(1f), placeholder = "Gorras, chamarras, bordado…")
                Spacer(Modifier.width(NeuDimens.SpaceMd))
                NeumorphicButton("Buscar", onClick = { vm.buscarRed(q) })
            }
        }
    }
    val lista = st.red
    if (lista == null) item(key = "red-cargando") { T("Buscando…", secundario = true) }
    else if (lista.isEmpty()) item(key = "red-vacia") { T("Nadie lo tiene publicado todavía. Pídele a tu asesor «publica que busco …» y les avisamos a todas las tiendas.", secundario = true) }
    items(lista.orEmpty(), key = { "r-" + it.clave }) { r -> TarjetaRed(r, negociar) }
}

@Composable
internal fun TarjetaRed(r: ResultadoRed, negociar: (ResultadoRed) -> Unit, compacta: Boolean = false) {
    val tipo = if (r.tipo == "anuncio") r.subtipo ?: "anuncio" else r.tipo
    val etiqueta = when (tipo) {
        "producto" -> "En vitrina"; "solicitud" -> "Busca"; "servicio" -> "Servicio"
        "promocion" -> "Promoción"; "productor" -> "Productor local"; else -> "Anuncio"
    }
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Etiqueta(etiqueta)
            Spacer(Modifier.weight(1f))
            r.distanciaKm?.let { T("a $it km", secundario = true, chico = true) }
        }
        Spacer(Modifier.height(NeuDimens.SpaceSm))
        T(if (r.tipo == "solicitud" && (r.cantidad ?: 0) > 0) "${miles(r.cantidad!!)} ${r.titulo}" else r.titulo, negritas = true)
        r.tienda?.let { T(it.nombre, secundario = true, chico = true) }
        r.lugar?.takeIf { r.tipo == "productor" }?.let { T(it, secundario = true, chico = true) }
        if (!compacta && r.detalle.isNotBlank()) T(r.detalle, secundario = true, chico = true)
        if (r.tipo == "producto") {
            r.disponible?.let { T("${miles(it)} ${r.unidad ?: "pza"} disponibles") }
            r.precioMayoreoC?.takeIf { it > 0 }?.let { T("${formatPrice(it)} mayoreo desde ${miles(r.minimoMayoreo ?: 0)}", negritas = true) }
            r.precioC?.takeIf { it > 0 }?.let { T("${formatPrice(it)} pieza suelta", secundario = true, chico = true) }
        }
        r.precio?.takeIf { it.isNotBlank() }?.let { T(it, negritas = true) }
        if (r.negociable) {
            Spacer(Modifier.height(NeuDimens.SpaceMd))
            NeumorphicButton(
                when { r.tipo == "solicitud" -> "Ofrecer"; r.subtipo == "servicio" -> "Contratar"; else -> "Negociar" },
                onClick = { negociar(r) },
            )
        }
    }
}

/** Diálogo para abrir un trato desde un resultado de la red. */
@Composable
internal fun DialogoNegociar(r: ResultadoRed, onCerrar: () -> Unit, onEnviar: (Long, Long, String) -> Unit) {
    val ofrezco = r.tipo == "solicitud"
    val inicial = if (ofrezco) (r.cantidad ?: 1).coerceAtLeast(1) else (r.minimoMayoreo ?: 1).coerceAtLeast(1)
    val mayoreo = (r.precioMayoreoC ?: 0) > 0 && inicial >= (r.minimoMayoreo ?: 0)
    HojaNegociacion(
        Negociacion(
            titulo = r.titulo, tienda = r.tienda?.nombre, foto = r.foto, icono = iconoDe(r.titulo, r.tipo),
            precioLista = r.precioC ?: 0, precioMayoreo = r.precioMayoreoC, minimoMayoreo = r.minimoMayoreo,
            disponible = r.disponible, cantidadInicial = inicial,
            precioInicial = if (mayoreo) r.precioMayoreoC!! else r.precioC ?: 0, modo = if (ofrezco) "ofrecer" else "proponer",
        ),
        onCerrar, onEnviar,
    )
}

// ---------------- Tratos ----------------

private fun estadoTxt(e: String) = when (e) {
    "negociando" -> "Negociando"; "aceptado" -> "Aceptado"; "entregado" -> "Entregado"; else -> "Cancelado"
}

internal fun LazyListScope.tratos(st: AnaquelUiState, vm: AnaquelViewModel, contraoferta: () -> Unit) {
    val t = st.trato
    if (t != null) return detalleTrato(t, st, vm, contraoferta)
    item(key = "tr-cab") { T("Tus negociaciones con otras tiendas. Empiezan con «Negociar» en la Red o desde tu asesor.", secundario = true) }
    val lista = st.tratos
    if (lista == null) item(key = "tr-cargando") { T("Cargando…", secundario = true) }
    else if (lista.isEmpty()) item(key = "tr-vacio") { T("No tienes tratos todavía.", secundario = true) }
    items(lista.orEmpty(), key = { "t-" + it.id }) { tr ->
        NeumorphicCard(Modifier.fillMaxWidth(), onClick = { vm.abrirTrato(tr) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Etiqueta(estadoTxt(tr.estado))
                Spacer(Modifier.weight(1f))
                if (tr.teToca) T("Te toca responder", negritas = true, chico = true)
                else if (tr.estado == "aceptado" && tr.rol == "vendo") T("Entrega pendiente", negritas = true, chico = true)
            }
            Spacer(Modifier.height(NeuDimens.SpaceSm))
            T("${if (tr.rol == "compro") "Compras" else "Vendes"} ${miles(tr.cantidad)} ${tr.titulo}", negritas = true)
            T("${if (tr.rol == "compro") "a" else "para"} ${tr.otra.nombre} · ${formatPrice(tr.precioC)} c/u", secundario = true, chico = true)
            tr.ultimo?.let { T(it, secundario = true, chico = true) }
        }
    }
}

private fun LazyListScope.detalleTrato(t: Trato, st: AnaquelUiState, vm: AnaquelViewModel, contraoferta: () -> Unit) {
    val ocupado = st.ocupado == "trato"
    item(key = "td-volver") { TextButton(onClick = vm::cerrarTrato) { Text("← Tratos", color = NeuColors.TextSecondary) } }
    item(key = "td-cab") {
        NeumorphicCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(t.titulo, negritas = true)
                    T("${if (t.rol == "compro") "Le compras a" else "Le vendes a"} ${t.otra.nombre}", secundario = true)
                }
                Etiqueta(estadoTxt(t.estado))
            }
            Spacer(Modifier.height(NeuDimens.SpaceMd))
            T("${miles(t.cantidad)} × ${formatPrice(t.precioC)} = ${formatPrice(t.cantidad * t.precioC)}", negritas = true)
            Spacer(Modifier.height(NeuDimens.SpaceSm))
            when {
                t.estado == "negociando" && t.teToca -> T("Te toca: acepta esta propuesta o haz una contraoferta.")
                t.estado == "negociando" -> T("Esperando que ${t.otra.nombre} responda tu propuesta.", secundario = true)
                t.estado == "aceptado" && t.rol == "vendo" -> T("Trato cerrado. Cuando entregues, márcalo: se descuenta de tu inventario y se suma al de ${t.otra.nombre}.")
                t.estado == "aceptado" -> T("Trato cerrado. Cuando ${t.otra.nombre} entregue, se sumará a tu inventario.", secundario = true)
                else -> Unit
            }
            Spacer(Modifier.height(NeuDimens.SpaceMd))
            Column(verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm)) {
                if (t.estado == "negociando" && t.teToca) NeumorphicButton("Aceptar trato", enabled = !ocupado, onClick = { vm.accionTrato("aceptar") }, modifier = Modifier.fillMaxWidth())
                if (t.estado == "negociando") NeumorphicButton("Contraoferta", onClick = contraoferta, containerColor = NeuColors.Blue, modifier = Modifier.fillMaxWidth())
                if (t.estado == "aceptado" && t.rol == "vendo") NeumorphicButton("Marcar entregado", enabled = !ocupado, onClick = { vm.accionTrato("entregar") }, modifier = Modifier.fillMaxWidth())
                t.whatsapp?.takeIf { it.isNotBlank() }?.let { w -> NeumorphicButton("WhatsApp", onClick = { vm.abrirWhatsApp(w) }, containerColor = NeuColors.Blue, modifier = Modifier.fillMaxWidth()) }
                if (t.abierto) TextButton(onClick = { vm.accionTrato("cancelar") }, enabled = !ocupado) { Text("Cancelar trato", color = NeuColors.OnLowStock) }
            }
        }
    }
    items(t.mensajes, key = { "tm-" + it.id }) { m ->
        val oferta = m.cantidad?.takeIf { m.de != "sistema" }?.let { "\nPropuesta: ${miles(it)} × ${formatPrice(m.precioC ?: 0)}" }.orEmpty()
        when (m.de) {
            "yo" -> BurbujaUsuario(m.texto + oferta)
            "sistema" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { T(m.texto, secundario = true, chico = true) }
            else -> NeumorphicCard(Modifier.fillMaxWidth().padding(end = 48.dp)) { T(m.texto + oferta) }
        }
    }
    if (t.abierto) {
        item(key = "td-input") {
            var texto by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeumorphicInput(texto, { texto = it }, Modifier.weight(1f), placeholder = "Escribe un mensaje…", singleLine = false)
                Spacer(Modifier.width(NeuDimens.SpaceMd))
                NeumorphicButton("Enviar", enabled = texto.isNotBlank() && !ocupado, onClick = { vm.escribirTrato(texto); texto = "" })
            }
        }
    }
}

@Composable
internal fun DialogoContraoferta(t: Trato, onCerrar: () -> Unit, onEnviar: (Long, Long) -> Unit) {
    HojaNegociacion(
        Negociacion(
            titulo = t.titulo, tienda = t.otra.nombre, foto = null, icono = iconoDe(t.titulo),
            precioLista = t.precioC, precioMayoreo = null, minimoMayoreo = null, disponible = t.disponible,
            cantidadInicial = t.cantidad, precioInicial = t.precioC, modo = "contra",
        ),
        onCerrar,
    ) { n, pc, _ -> onEnviar(n, pc) }
}
