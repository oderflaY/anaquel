package com.eter.mitienda.feature.mercado

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Ordenes(api: MercadoApi, ir: Ir, cuenta: Cuenta, ventas: Boolean) {
    val q = rememberConsulta(ventas) { api.ordenes(ventas) }
    Column(Modifier.fillMaxSize()) {
        if (cuenta.vende) SecondaryTabRow(selectedTabIndex = if (ventas) 1 else 0, containerColor = M.c.fondo, contentColor = M.c.marca) {
            Tab(!ventas, { if (ventas) ir.ordenes(false) }, text = { Text("Compras") }, selectedContentColor = M.c.marca, unselectedContentColor = M.c.texto2)
            Tab(ventas, { if (!ventas) ir.ordenes(true) }, text = { Text("Ventas") }, selectedContentColor = M.c.marca, unselectedContentColor = M.c.texto2)
        }
        Box(Modifier.fillMaxSize().padding(horizontal = E.x4)) {
            when {
                q.cargando -> Box(Modifier.padding(top = E.x4)) { EsqueletoRenglones() }
                q.error != null -> EstadoError(q.error!!, q::recargar)
                q.datos.isNullOrEmpty() -> EstadoVacio(
                    if (ventas) "Aún no tienes ventas" else "Aún no has apartado nada",
                    if (ventas) "Cuando alguien aparte uno de tus productos aparecerá aquí." else "Explora lo que venden los negocios de Durango.",
                    "recibo",
                ) { Boton(if (ventas) "Ir a mis publicaciones" else "Explorar", { if (ventas) ir.vender("publicaciones") else ir.explorar() }, variante = Variante.Secundario) }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(E.x2), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = E.x4)) {
                    items(q.datos!!, key = { it.id }) { o ->
                        Renglon({ ir.orden(o.id) }) {
                            Miniatura(api, o.foto)
                            Column(Modifier.weight(1f)) {
                                Text(o.titulo, style = MaterialTheme.typography.labelLarge, color = M.c.texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${cantidadTxt(o.cantidad, o.unidad)} · ${if (o.rol == "compra") "a" else "para"} ${o.contraparte}",
                                    style = MaterialTheme.typography.bodySmall, color = M.c.texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fecha(o.creada), style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(E.x1)) {
                                Text(pesos(o.totalC), style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                                estadoOrden(o.estado).let { Insignia(it.txt, it.tono) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val PASOS = listOf(
    Triple("pendiente", "Apartado", "El vendedor ya lo tiene reservado para ti."),
    Triple("por_cobrar", "Cobro en la terminal", "Se envió el monto a la terminal del vendedor."),
    Triple("pagada", "Pagado", "El pago se registró automáticamente."),
    Triple("entregada", "Entregado", "Pedido completado."),
)

@Composable
fun OrdenDetalle(api: MercadoApi, ir: Ir, id: Long) {
    val q = rememberConsulta(id) { api.orden(id) }
    var orden by remember(id) { mutableStateOf<Orden?>(null) }
    LaunchedEffect(q.datos) { q.datos?.let { orden = it } }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var cobrar by remember { mutableStateOf(false) }
    var vincular by remember { mutableStateOf(false) }
    var cancelar by remember { mutableStateOf(false) }
    var ocupado by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Mientras está en la terminal, la orden se actualiza sola.
    LaunchedEffect(orden?.estado) {
        while (orden?.estado == "por_cobrar") { delay(3000); runCatching { api.orden(id) }.onSuccess { orden = it } }
    }
    fun accion(a: String) {
        ocupado = a; error = null
        alcance.launch {
            try { orden = api.accionOrden(id, a); avisar(if (a == "cancelar") "Orden cancelada" else "Marcada como entregada", false); cancelar = false }
            catch (e: MercadoError) { error = e.message }
            ocupado = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        BarraAtras("Orden #$id", ir::atras)
        val o = orden
        when {
            o == null && q.error != null -> EstadoError(q.error!!, q::recargar)
            o == null -> Box(Modifier.padding(E.x4)) { Cargando("Cargando orden…") { Column(verticalArrangement = Arrangement.spacedBy(E.x3)) {
                Esqueleto(Modifier.fillMaxWidth().height(140.dp), RoundedCornerShape(E.radioCard)); Esqueleto(Modifier.fillMaxWidth().height(200.dp), RoundedCornerShape(E.radioCard)) } } }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = E.x4).padding(bottom = E.x8),
                verticalArrangement = Arrangement.spacedBy(E.x4)) {
                val vende = o.rol == "venta"
                Fila { Titulo("Orden #${o.id}", grande = false); estadoOrden(o.estado).let { Insignia(it.txt, it.tono) } }
                Tarjeta {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x3)) {
                        Miniatura(api, o.foto)
                        Column(Modifier.weight(1f)) {
                            Text(o.titulo, style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                            Text("${cantidadTxt(o.cantidad, o.unidad)} × ${pesos(o.precioC)}", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                        }
                        Text(pesos(o.totalC), style = MaterialTheme.typography.titleLarge, color = M.c.texto)
                    }
                    Separador()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x3)) {
                        Avatar(o.contraparte)
                        Column(Modifier.weight(1f)) {
                            Text(if (vende) "Comprador" else "Vendedor", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                            Text(o.contraparte, style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                        }
                        Boton("Ver perfil", { ir.vendedor(o.contraparteSlug) }, variante = Variante.Fantasma, chico = true)
                    }
                    if (o.nota.isNotBlank()) Alerta("Nota: ${o.nota}")
                }
                if (o.estado != "cancelada") Column(verticalArrangement = Arrangement.spacedBy(E.x2)) {
                    when {
                        vende && o.estado == "pendiente" -> {
                            Boton("Cobrar con terminal", { cobrar = true }, Modifier.fillMaxWidth(), icono = "tarjeta")
                            Boton("Ya me pagó directo en la terminal", { vincular = true }, Modifier.fillMaxWidth(), Variante.Fantasma)
                        }
                        vende && o.estado == "por_cobrar" -> Boton("Ver cobro en curso", { cobrar = true }, Modifier.fillMaxWidth(), Variante.Secundario, icono = "terminal")
                        vende && o.estado == "pagada" -> Boton("Marcar como entregado", { accion("entregar") }, Modifier.fillMaxWidth(), icono = "check", cargando = ocupado == "entregar")
                        !vende && o.estado == "pendiente" -> Alerta("Pasa con ${o.contraparte} y paga con tarjeta en su terminal. El pago se registra solo.")
                        !vende && o.estado == "por_cobrar" -> Alerta("Pasa o acerca tu tarjeta a la terminal del vendedor.", "aviso")
                        !vende && o.estado == "pagada" -> Alerta("Pago registrado. Recoge tu pedido con ${o.contraparte}.", "exito")
                    }
                    if (o.estado == "pendiente") Boton("Cancelar orden", { cancelar = true }, Modifier.fillMaxWidth(), Variante.Peligro)
                    error?.let { Alerta(it, "error") }
                }
                Tarjeta {
                    Titulo("Avance", grande = false)
                    if (o.estado == "cancelada") Linea(listOf("Cancelada" to "cancelado"), listOf(fecha(o.actualizada)))
                    else {
                        val indice = PASOS.indexOfFirst { it.first == o.estado }
                        Linea(
                            PASOS.mapIndexed { i, (e, t, _) ->
                                t to when {
                                    i < indice || o.estado == "entregada" || (i == indice && e == "pagada") -> "hecho"
                                    i == indice -> "actual"
                                    else -> ""
                                }
                            },
                            PASOS.mapIndexed { i, (e, _, d) ->
                                if (i > indice) null else (o.eventos?.lastOrNull { it.estado == e }?.let { (it.nota.ifBlank { d }) + " · " + fecha(it.creado) } ?: d)
                            },
                        )
                    }
                }
            }
        }
    }

    if (cancelar) AlertDialog(
        { cancelar = false }, containerColor = M.c.superficie, shape = RoundedCornerShape(E.radioModal),
        title = { Text("¿Cancelar esta orden?", color = M.c.texto) },
        text = { Text("El producto vuelve a estar disponible para otros negocios. No se puede deshacer.", color = M.c.texto2) },
        confirmButton = { Boton("Sí, cancelar", { accion("cancelar") }, variante = Variante.Peligro, cargando = ocupado == "cancelar") },
        dismissButton = { Boton("No, mantener", { cancelar = false }, variante = Variante.Secundario) },
    )
    val o = orden
    if (cobrar && o != null) CobroTerminal(api, ir, o) { cobrar = false; q.recargar() }
    if (vincular && o != null) VincularPago(api, o) { ok -> vincular = false; if (ok) q.recargar() }
}

/** Manda el total a la terminal y espera el resultado (llega por la notificación de la terminal). */
@Composable
private fun CobroTerminal(api: MercadoApi, ir: Ir, orden: Orden, cerrar: () -> Unit) {
    val terms = rememberConsulta { api.terminales() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var elegida by remember { mutableStateOf<Long?>(null) }
    var cobro by remember { mutableStateOf<Cobro?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(terms.datos) { if (elegida == null) elegida = terms.datos?.firstOrNull()?.id }
    LaunchedEffect(Unit) { if (orden.estado == "por_cobrar" && orden.cobroId != null) runCatching { api.cobro(orden.cobroId) }.onSuccess { cobro = it } }
    LaunchedEffect(cobro?.id, cobro?.estado) {
        val c = cobro ?: return@LaunchedEffect
        if (c.estado == "aprobado") avisar("Pago de ${pesos(c.montoC)} recibido", false)
        while (cobro?.estado == "enviado" || cobro?.estado == "creado") { delay(2000); runCatching { api.cobro(c.id) }.onSuccess { cobro = it } }
    }
    val esVirtual = (cobro?.proveedor ?: terms.datos?.firstOrNull { it.id == elegida }?.proveedor) == "simulador"

    AlertDialog(
        onDismissRequest = cerrar, containerColor = M.c.superficie, shape = RoundedCornerShape(E.radioModal),
        title = { Text("Cobrar con terminal", color = M.c.texto) },
        text = {
            val c = cobro
            Column(verticalArrangement = Arrangement.spacedBy(E.x3), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                when {
                    c == null -> {
                        Fila { Text("Total", color = M.c.texto2); Text(pesos(orden.totalC), style = MaterialTheme.typography.headlineMedium, color = M.c.texto) }
                        when {
                            terms.cargando -> Column(verticalArrangement = Arrangement.spacedBy(E.x2)) { repeat(2) { Esqueleto(Modifier.fillMaxWidth().height(56.dp), RoundedCornerShape(E.radioControl)) } }
                            terms.error != null -> Alerta(terms.error!!, "error")
                            terms.datos.isNullOrEmpty() -> EstadoVacio("No tienes terminales vinculadas", "Vincula tu terminal de Mercado Pago o Clip (o la terminal virtual de prueba).", "terminal") {
                                Boton("Vincular terminal", { cerrar(); ir.vender("terminales") })
                            }
                            else -> {
                                Text("¿En qué terminal?", style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                                terms.datos!!.forEach { t ->
                                    val sel = elegida == t.id
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(E.radioControl))
                                        .background(if (sel) M.c.marcaSuave else M.c.superficie)
                                        .selectable(sel, role = Role.RadioButton) { elegida = t.id }.padding(E.x2),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x2)) {
                                        RadioButton(sel, null, colors = RadioButtonDefaults.colors(selectedColor = M.c.marca))
                                        Column { Text(t.nombre, style = MaterialTheme.typography.labelLarge, color = M.c.texto); Text(t.marca, style = MaterialTheme.typography.bodySmall, color = M.c.texto3) }
                                    }
                                }
                            }
                        }
                        error?.let { Alerta(it, "error") }
                    }
                    c.estado == "enviado" || c.estado == "creado" -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(E.x3), modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(M.c.marcaSuave), contentAlignment = Alignment.Center) {
                            Icon(icono("tarjeta"), null, Modifier.size(32.dp), tint = M.c.marca)
                        }
                        Text("Esperando el pago en «${c.terminal}»", style = MaterialTheme.typography.titleMedium, color = M.c.texto)
                        Text("Pide al cliente que pase o acerque su tarjeta. Esta pantalla se actualiza sola.", style = MaterialTheme.typography.bodyMedium, color = M.c.texto2)
                        Text(pesos(c.montoC), style = MaterialTheme.typography.headlineMedium, color = M.c.texto)
                        if (esVirtual) Boton("Abrir terminal virtual", { cerrar(); ir.terminal() }, variante = Variante.Secundario, icono = "terminal")
                        error?.let { Alerta(it, "error") }
                    }
                    c.estado == "aprobado" -> EstadoVacio("Pago aprobado", "${pesos(c.montoC)} en ${c.terminal}. Quedó en tu registro de cobros.", "check")
                    else -> EstadoVacio(if (c.estado == "cancelado") "Cobro cancelado" else "No se completó el pago",
                        (c.detalle.ifBlank { "La terminal no aprobó el cobro." }) + " La orden sigue apartada.", "alerta")
                }
            }
        },
        confirmButton = {
            val c = cobro
            when {
                c == null -> Boton("Enviar ${pesos(orden.totalC)}", {
                    ocupado = true; error = null
                    alcance.launch {
                        try { cobro = api.cobrar(orden.id, elegida!!) } catch (e: MercadoError) { error = e.message }
                        ocupado = false
                    }
                }, cargando = ocupado, habilitado = elegida != null, icono = "tarjeta")
                c.estado == "enviado" -> Boton("Cancelar cobro", {
                    ocupado = true
                    alcance.launch { try { cobro = api.cancelarCobro(c.id) } catch (e: MercadoError) { error = e.message }; ocupado = false }
                }, variante = Variante.Peligro, cargando = ocupado)
                c.estado == "aprobado" -> Boton("Listo", cerrar)
                else -> Boton("Intentar de nuevo", { cobro = null; error = null })
            }
        },
        dismissButton = { if (cobro?.estado != "aprobado") Boton("Cerrar", cerrar, variante = Variante.Secundario) },
    )
}

/** Liga un pago que se hizo directo en la terminal (registrado por notificación) con esta orden. */
@Composable
private fun VincularPago(api: MercadoApi, orden: Orden, cerrar: (Boolean) -> Unit) {
    val q = rememberConsulta { api.registros() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var ocupado by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val iguales = q.datos?.items?.filter { it.origen == "terminal" && it.ordenId == null && it.montoC == orden.totalC }.orEmpty()
    AlertDialog(
        { cerrar(false) }, containerColor = M.c.superficie, shape = RoundedCornerShape(E.radioModal),
        title = { Text("Ligar un pago de la terminal", color = M.c.texto) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(E.x3)) {
                Text("Pagos de ${pesos(orden.totalC)} que tu terminal notificó y aún no están ligados a una orden.", color = M.c.texto2)
                when {
                    q.cargando -> EsqueletoRenglones(2)
                    q.error != null -> Alerta(q.error!!, "error")
                    iguales.isEmpty() -> EstadoVacio("No hay pagos por ese monto", "Cuando cobres en la terminal sin usar la app, el pago aparecerá aquí en segundos.", "tarjeta")
                    else -> iguales.forEach { r ->
                        Renglon(null) {
                            Column(Modifier.weight(1f)) {
                                Text(pesos(r.montoC), style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                                Text("${r.terminal} · ${fecha(r.pagadoEn)}", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                            }
                            Boton("Ligar", {
                                ocupado = r.id; error = null
                                alcance.launch {
                                    try { api.vincularRegistro(r.id, orden.id); avisar("Pago ligado a la orden", false); cerrar(true) }
                                    catch (e: MercadoError) { error = e.message }
                                    ocupado = null
                                }
                            }, chico = true, cargando = ocupado == r.id, habilitado = ocupado == null)
                        }
                    }
                }
                error?.let { Alerta(it, "error") }
            }
        },
        confirmButton = { Boton("Cerrar", { cerrar(false) }, variante = Variante.Secundario) },
    )
}
