package com.eter.mitienda.feature.mercado

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.eter.mitienda.core.image.ImagePickResult
import com.eter.mitienda.core.image.rememberImagePickerLauncher
import com.eter.mitienda.core.util.readFileBytes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap

private val PESTANAS = listOf("publicaciones" to "Publicaciones", "publicidad" to "Publicidad", "reels" to "Reels", "terminales" to "Terminales", "ingresos" to "Cobros recibidos")

@Composable
fun Vender(api: MercadoApi, ir: Ir, tab: String) {
    val i = PESTANAS.indexOfFirst { it.first == tab }.coerceAtLeast(0)
    Column(Modifier.fillMaxSize()) {
        SecondaryScrollableTabRow(selectedTabIndex = i, containerColor = M.c.fondo, contentColor = M.c.marca, edgePadding = E.x2) {
            PESTANAS.forEachIndexed { j, (k, t) ->
                Tab(i == j, { if (i != j) ir.vender(k) }, text = { Text(t) }, selectedContentColor = M.c.marca, unselectedContentColor = M.c.texto2)
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (PESTANAS[i].first) {
                "publicaciones" -> MisPublicaciones(api, ir)
                "publicidad" -> Publicidad(api)
                "reels" -> MisReels(api)
                "terminales" -> MisTerminales(api, ir)
                else -> Ingresos(api, ir)
            }
        }
    }
}

@Composable
private fun Desplazable(contenido: @Composable () -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(E.x4), verticalArrangement = Arrangement.spacedBy(E.x3)) { contenido() }

@Composable
private fun MisPublicaciones(api: MercadoApi, ir: Ir) {
    val q = rememberConsulta { api.mias() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var borrar by remember { mutableStateOf<Publicacion?>(null) }
    var ocupado by remember { mutableStateOf<Long?>(null) }
    Desplazable {
        Boton("Publicar producto", { ir.publicar() }, Modifier.fillMaxWidth(), icono = "mas")
        when {
            q.cargando -> EsqueletoRenglones()
            q.error != null -> EstadoError(q.error!!, q::recargar)
            q.datos.isNullOrEmpty() -> EstadoVacio("Aún no publicas productos", "Publica lo que vendes y los negocios de Durango podrán apartarlo.", "etiqueta")
            else -> q.datos!!.forEach { p ->
                Renglon({ ir.producto(p.id) }) {
                    Miniatura(api, p.foto, p.categoria)
                    Column(Modifier.weight(1f)) {
                        Text(p.titulo, style = MaterialTheme.typography.labelLarge, color = M.c.texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${pesos(p.precioC)} / ${unidad(p.unidad)}" + (p.existencias?.let { " · $it disponibles" } ?: ""), style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                        if (p.estado == "activa") Insignia("Visible", "exito") else Insignia("Pausada", "aviso")
                    }
                    BotonIcono(if (p.estado == "activa") "Pausar ${p.titulo}" else "Activar ${p.titulo}", if (p.estado == "activa") "pausa" else "play", {
                        ocupado = p.id
                        alcance.launch {
                            val nuevo = if (p.estado == "activa") "pausada" else "activa"
                            try { api.estadoPublicacion(p.id, nuevo); q.datos = q.datos?.map { if (it.id == p.id) it.copy(estado = nuevo) else it }
                                avisar(if (nuevo == "pausada") "Publicación pausada: ya no aparece en el mercado" else "Publicación activa", false)
                            } catch (e: MercadoError) { avisar(e.message ?: "", true) }
                            ocupado = null
                        }
                    }, habilitado = ocupado != p.id)
                    BotonIcono("Editar ${p.titulo}", "editar", { ir.publicar(p.id) })
                    BotonIcono("Eliminar ${p.titulo}", "basura", { borrar = p })
                }
            }
        }
    }
    borrar?.let { p ->
        AlertDialog({ borrar = null }, containerColor = M.c.superficie, shape = RoundedCornerShape(E.radioModal),
            title = { Text("¿Eliminar publicación?", color = M.c.texto) },
            text = { Text("«${p.titulo}» desaparecerá del mercado con sus fotos. Las órdenes que ya existen se conservan. Si solo quieres ocultarla, mejor páusala.", color = M.c.texto2) },
            confirmButton = { Boton("Eliminar", {
                ocupado = p.id
                alcance.launch {
                    try { api.borrarPublicacion(p.id); q.datos = q.datos?.filterNot { it.id == p.id }; avisar("Publicación eliminada", false) }
                    catch (e: MercadoError) { avisar(e.message ?: "", true) }
                    ocupado = null; borrar = null
                }
            }, variante = Variante.Peligro, cargando = ocupado == p.id) },
            dismissButton = { Boton("Cancelar", { borrar = null }, variante = Variante.Secundario) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MisReels(api: MercadoApi) {
    val q = rememberConsulta { api.misReels() }
    val pubs = rememberConsulta { api.mias() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var video by remember { mutableStateOf<ByteArray?>(null) }
    var desc by remember { mutableStateOf("") }
    var pub by remember { mutableStateOf<Publicacion?>(null) }
    var errores by remember { mutableStateOf(mapOf<String, String>()) }
    var subiendo by remember { mutableStateOf(false) }
    val elegir = rememberSelectorVideo { datos, err ->
        if (err != null) errores = mapOf("video" to err) else { video = datos; errores = errores - "video" }
    }
    Desplazable {
        Tarjeta {
            Titulo("Subir un video", grande = false)
            if (!SUBIR_VIDEO_DISPONIBLE) Alerta("Subir videos desde el teléfono está disponible en Android y en la web.", "info")
            else {
                Boton(if (video == null) "Elegir video" else "Video listo (${(video!!.size / 1024 / 1024.0).toString().take(4)} MB) · cambiar", elegir,
                    Modifier.fillMaxWidth(), Variante.Secundario, icono = "video")
                errores["video"]?.let { Text(it, color = M.c.error, style = MaterialTheme.typography.bodySmall) }
                Text("MP4 vertical, de 1 a 60 segundos, máximo 50 MB.", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                Campo("¿Qué se ve en el video?", desc, { desc = it.take(300); errores = errores - "desc" }, lineas = 2, error = errores["desc"],
                    ayuda = "Lo escuchan quienes usan lector de pantalla y aparece sobre el video.")
                var abierto by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(abierto, { abierto = it }) {
                    OutlinedTextField(pub?.titulo ?: "Ninguno", {}, readOnly = true, label = { Text("Producto que se muestra (opcional)") },
                        shape = RoundedCornerShape(E.radioControl), modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(abierto, { abierto = false }) {
                        DropdownMenuItem({ Text("Ninguno") }, { pub = null; abierto = false })
                        pubs.datos.orEmpty().forEach { p -> DropdownMenuItem({ Text(p.titulo) }, { pub = p; abierto = false }) }
                    }
                }
                Boton(if (subiendo) "Subiendo…" else "Publicar video", {
                    val e = buildMap {
                        if (video == null) put("video", "Elige un video MP4")
                        if (desc.trim().length < 10) put("desc", "Describe el video en al menos 10 letras")
                    }
                    errores = e
                    if (e.isEmpty()) {
                        subiendo = true
                        alcance.launch {
                            try { api.subirReel(video!!, desc.trim(), pub?.id); avisar("Video publicado", false); video = null; desc = ""; pub = null; q.recargar() }
                            catch (ex: MercadoError) { errores = mapOf("general" to (ex.message ?: "")) }
                            subiendo = false
                        }
                    }
                }, Modifier.fillMaxWidth(), icono = "video", cargando = subiendo)
                errores["general"]?.let { Alerta(it, "error") }
            }
        }
        Titulo("Mis videos", grande = false)
        when {
            q.cargando -> EsqueletoRenglones(2)
            q.error != null -> EstadoError(q.error!!, q::recargar)
            q.datos.isNullOrEmpty() -> EstadoVacio("Aún no subes videos", "Un video corto de tu producto ayuda a que lo descubran.", "video")
            else -> q.datos!!.forEach { r ->
                Renglon(null) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(E.radioControl)).background(M.c.superficie2), contentAlignment = Alignment.Center) {
                        Icon(icono("video"), null, tint = M.c.texto2)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(r.descripcion, style = MaterialTheme.typography.labelLarge, color = M.c.texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${r.duracionMs / 1000} s · ${r.publicacion?.titulo ?: "Sin producto"}", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                    }
                    BotonIcono("Eliminar video", "basura", {
                        alcance.launch { try { api.borrarReel(r.id); q.datos = q.datos?.filterNot { it.id == r.id }; avisar("Video eliminado", false) } catch (e: MercadoError) { avisar(e.message ?: "", true) } }
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MisTerminales(api: MercadoApi, ir: Ir) {
    val q = rememberConsulta { api.terminales() }
    val provs = rememberConsulta { api.proveedores() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var proveedor by remember { mutableStateOf<String?>(null) }
    var nombre by remember { mutableStateOf("") }
    var disp by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    LaunchedEffect(provs.datos) { if (proveedor == null) proveedor = provs.datos?.firstOrNull()?.clave }
    Desplazable {
        Alerta("Cuando cobras en tu terminal, la marca le avisa a Anaquel y el pago se registra solo, aunque no lo hayas iniciado desde la app.")
        when {
            q.cargando -> EsqueletoRenglones(2)
            q.error != null -> EstadoError(q.error!!, q::recargar)
            q.datos.isNullOrEmpty() -> EstadoVacio("Sin terminales vinculadas", "Vincula tu terminal para cobrar las órdenes con tarjeta.", "terminal")
            else -> q.datos!!.forEach { t ->
                Renglon(null) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(E.radioControl)).background(M.c.marcaSuave), contentAlignment = Alignment.Center) {
                        Icon(icono("terminal"), null, tint = M.c.marca)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.nombre, style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                        Text("${t.marca} · ${t.dispositivo}", style = MaterialTheme.typography.bodySmall, color = M.c.texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (t.proveedor == "simulador") Boton("Abrir", { ir.terminal() }, variante = Variante.Fantasma, chico = true)
                    BotonIcono("Desvincular ${t.nombre}", "basura", {
                        alcance.launch { try { api.quitarTerminal(t.id); q.datos = q.datos?.filterNot { it.id == t.id }; avisar("Terminal desvinculada", false) } catch (e: MercadoError) { avisar(e.message ?: "", true) } }
                    })
                }
            }
        }
        Tarjeta {
            Titulo("Vincular terminal", grande = false)
            if (provs.cargando) Esqueleto(Modifier.fillMaxWidth().height(56.dp)) else {
                var abierto by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(abierto, { abierto = it }) {
                    OutlinedTextField(provs.datos?.firstOrNull { it.clave == proveedor }?.nombre ?: "", {}, readOnly = true, label = { Text("Marca") },
                        shape = RoundedCornerShape(E.radioControl), modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                    ExposedDropdownMenu(abierto, { abierto = false }) {
                        provs.datos.orEmpty().forEach { p -> DropdownMenuItem({ Text(p.nombre) }, { proveedor = p.clave; abierto = false }) }
                    }
                }
                Campo("Nombre", nombre, { nombre = it.take(40) }, ayuda = "Para reconocerla, ej. «Mostrador».")
                if (proveedor != "simulador") Campo(if (proveedor == "clip") "Número de serie" else "ID de la terminal", disp, { disp = it.take(64) },
                    ayuda = if (proveedor == "mercadopago") "Lo ves en tu cuenta de Mercado Pago. La terminal debe estar en modo PDV." else "Viene en la etiqueta de la terminal.")
            }
            error?.let { Alerta(it, "error") }
            Boton("Vincular", {
                ocupado = true; error = null
                alcance.launch {
                    try { val t = api.vincularTerminal(proveedor!!, nombre, disp); q.datos = q.datos.orEmpty() + t; nombre = ""; disp = ""; avisar("Terminal vinculada", false) }
                    catch (e: MercadoError) { error = e.message }
                    ocupado = false
                }
            }, Modifier.fillMaxWidth(), Variante.Secundario, icono = "mas", cargando = ocupado, habilitado = proveedor != null)
        }
    }
}

@Composable
private fun Ingresos(api: MercadoApi, ir: Ir) {
    val q = rememberConsulta { api.registros() }
    Desplazable {
        when {
            q.cargando -> Cargando { Row(horizontalArrangement = Arrangement.spacedBy(E.x3)) { repeat(2) { Esqueleto(Modifier.weight(1f).height(84.dp), RoundedCornerShape(E.radioCard)) } } }
            q.error != null -> EstadoError(q.error!!, q::recargar)
            else -> {
                val d = q.datos!!
                Row(horizontalArrangement = Arrangement.spacedBy(E.x3)) {
                    Tarjeta(Modifier.weight(1f)) { Text("Cobrado hoy", style = MaterialTheme.typography.bodySmall, color = M.c.texto3); Text(pesos(d.hoyC), style = MaterialTheme.typography.headlineMedium, color = M.c.texto) }
                    Tarjeta(Modifier.weight(1f)) { Text("Cobros de hoy", style = MaterialTheme.typography.bodySmall, color = M.c.texto3); Text("${d.hoyCobros}", style = MaterialTheme.typography.headlineMedium, color = M.c.texto) }
                }
                Fila { Titulo("Registro de cobros", grande = false); Boton("Actualizar", q::recargar, variante = Variante.Fantasma, chico = true) }
                if (d.items.isEmpty()) EstadoVacio("Aún no hay cobros", "Los pagos que notifique tu terminal aparecerán aquí automáticamente.", "tarjeta")
                else d.items.forEach { r ->
                    Renglon(r.ordenId?.let { { ir.orden(it) } }) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(E.radioControl)).background(M.c.exitoSuave), contentAlignment = Alignment.Center) {
                            Icon(icono("tarjeta"), null, tint = M.c.exito)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(pesos(r.montoC), style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                            Text("${r.terminal.ifBlank { r.marca }} · ${fecha(r.pagadoEn)}", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(E.x1)) {
                            if (r.origen == "app") Insignia("Desde la app", "info") else Insignia("Directo en terminal", "acento")
                            Text(r.ordenId?.let { "Orden #$it" } ?: "Sin orden", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                        }
                    }
                }
            }
        }
    }
}

// ===================== Publicar / editar =====================

private data class FotoNueva(val bytes: ByteArray, val vista: ImageBitmap?, var alt: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Publicar(api: MercadoApi, ir: Ir, id: Long?) {
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    val cats = rememberConsulta { api.categorias() }
    val existente = rememberConsulta(id) { id?.let { api.publicacion(it) } }
    var titulo by remember { mutableStateOf("") }
    var descripcion by remember { mutableStateOf("") }
    var categoria by remember { mutableStateOf("") }
    var precio by remember { mutableStateOf("") }
    var unidadSel by remember { mutableStateOf("pieza") }
    var existencias by remember { mutableStateOf("") }
    var sinConteo by remember { mutableStateOf(false) }
    var minimo by remember { mutableStateOf("1") }
    var fotos by remember { mutableStateOf<List<Foto>>(emptyList()) }
    val nuevas = remember { mutableStateListOf<FotoNueva>() }
    var errores by remember { mutableStateOf(mapOf<String, String>()) }
    var guardando by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(existente.datos) {
        existente.datos?.publicacion?.let { p ->
            titulo = p.titulo; descripcion = p.descripcion.orEmpty(); categoria = p.categoria; precio = (p.precioC / 100.0).toString().removeSuffix(".0")
            unidadSel = p.unidad; existencias = p.existencias?.toString() ?: ""; sinConteo = p.existencias == null; minimo = p.minimo.toString(); fotos = p.fotos.orEmpty()
        }
    }
    val selector = rememberImagePickerLauncher { r ->
        when (r) {
            is ImagePickResult.Success -> runCatching { readFileBytes(r.tempPath) }.onSuccess { b ->
                nuevas.add(FotoNueva(b, runCatching { b.decodeToImageBitmap() }.getOrNull(), titulo))
            }.onFailure { avisar("No se pudo leer la foto", true) }
            is ImagePickResult.Error -> avisar(r.message, true)
            ImagePickResult.Cancelled -> {}
        }
    }

    Column(Modifier.fillMaxSize()) {
        BarraAtras(if (id != null) "Editar publicación" else "Publicar producto", ir::atras)
        if (id != null && existente.cargando) { Box(Modifier.padding(E.x4)) { EsqueletoRenglones(4) }; return@Column }
        if (id != null && (existente.error != null || existente.datos?.publicacion?.esMia == false)) { EstadoError(existente.error ?: "Solo puedes editar tus publicaciones."); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = E.x4).padding(bottom = E.x8), verticalArrangement = Arrangement.spacedBy(E.x3)) {
            Tarjeta {
                Titulo("Fotos", grande = false)
                Text("Hasta 6. La primera es la portada. Quitamos la ubicación y los datos ocultos de cada foto.", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(E.x2)) {
                    fotos.forEach { f ->
                        Box { Miniatura(api, f, tam = 96.dp)
                            BotonIcono("Quitar foto: ${f.alt}", "cerrar", {
                                alcance.launch { try { api.borrarFoto(id!!, f.id!!); fotos = fotos - f } catch (e: MercadoError) { avisar(e.message ?: "", true) } }
                            }, Modifier.align(Alignment.TopEnd), tinte = M.c.texto) }
                    }
                    nuevas.forEachIndexed { i, n ->
                        Column(Modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(E.x1)) {
                            Box {
                                if (n.vista != null) Image(n.vista, null, Modifier.size(96.dp).clip(RoundedCornerShape(E.radioControl)), contentScale = ContentScale.Crop)
                                BotonIcono("Quitar esta foto", "cerrar", { nuevas.removeAt(i) }, Modifier.align(Alignment.TopEnd), tinte = M.c.texto)
                            }
                            var alt by remember(n) { mutableStateOf(n.alt) }
                            OutlinedTextField(alt, { alt = it.take(140); n.alt = alt }, label = { Text("Describe la foto") }, singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (fotos.size + nuevas.size < 6) Row(horizontalArrangement = Arrangement.spacedBy(E.x2)) {
                    Boton("Tomar foto", selector::takePhoto, variante = Variante.Secundario, icono = "camara", chico = true, cargando = selector.isProcessing)
                    Boton("Galería", selector::pickFromGallery, variante = Variante.Secundario, chico = true, habilitado = !selector.isProcessing)
                }
            }
            Tarjeta {
                Campo("Título", titulo, { titulo = it.take(80); errores = errores - "titulo" }, error = errores["titulo"], ayuda = "Lo que buscaría un cliente, ej. «Gorra bordada con tu logo».")
                Campo("Precio (MXN)", precio, { precio = it; errores = errores - "precio" }, teclado = KeyboardType.Decimal, error = errores["precio"], placeholder = "65.00")
                Menu("Se vende por", UNIDADES.map { it to unidad(it) }, unidadSel) { unidadSel = it }
                Menu("Categoría", cats.datos.orEmpty().map { it.clave to it.nombre }, categoria, errores["categoria"]) { categoria = it; errores = errores - "categoria" }
                Campo("Compra mínima", minimo, { minimo = it.filter(Char::isDigit).take(6); errores = errores - "minimo" }, teclado = KeyboardType.Number, error = errores["minimo"], ayuda = "Para mayoreo, ej. 50 piezas.")
                Campo("Existencias", if (sinConteo) "" else existencias, { existencias = it.filter(Char::isDigit).take(7); errores = errores - "existencias" },
                    teclado = KeyboardType.Number, habilitado = !sinConteo, error = errores["existencias"])
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(sinConteo, { sinConteo = it }, colors = CheckboxDefaults.colors(checkedColor = M.c.marca))
                    Text("Sin conteo (servicios o bajo pedido)", color = M.c.texto2, style = MaterialTheme.typography.bodyMedium)
                }
                Campo("Descripción", descripcion, { descripcion = it.take(1000) }, lineas = 3, opcional = true, placeholder = "Material, tallas, tiempo de entrega…")
            }
            errores["general"]?.let { Alerta(it, "error") }
            Boton(when { guardando == null -> if (id != null) "Guardar cambios" else "Publicar"; guardando == "datos" -> "Guardando…"; else -> "Subiendo $guardando…" }, {
                val c = aCentavos(precio)
                val e = buildMap {
                    if (titulo.trim().length < 3) put("titulo", "Escribe al menos 3 letras")
                    if (c == null || c < 100) put("precio", "Escribe un precio válido, desde $1 (ej. 65 o 65.50)")
                    if (categoria.isBlank()) put("categoria", "Elige una categoría")
                    if (!sinConteo && existencias.isBlank()) put("existencias", "Escribe cuántas tienes, o marca «sin conteo»")
                    if ((minimo.toIntOrNull() ?: 0) < 1) put("minimo", "Mínimo 1")
                }
                errores = e
                if (e.isNotEmpty()) return@Boton
                val datos = DatosPublicacion(titulo.trim(), descripcion.trim(), categoria, c!!, unidadSel, if (sinConteo) null else existencias.toInt(), minimo.toInt())
                guardando = "datos"
                alcance.launch {
                    try {
                        val pid = id ?: api.crearPublicacion(datos)
                        if (id != null) api.editarPublicacion(id, datos)
                        nuevas.forEachIndexed { i, n ->
                            guardando = "foto ${i + 1} de ${nuevas.size}"
                            runCatching { api.subirFoto(pid, n.bytes, n.alt.ifBlank { datos.titulo }) }.onFailure { avisar("Una foto no se subió: ${it.message}", true) }
                        }
                        avisar(if (id != null) "Cambios guardados" else "Producto publicado", false)
                        ir.atras(); ir.producto(pid)
                    } catch (ex: MercadoError) { errores = mapOf("general" to (ex.message ?: "")) }
                    guardando = null
                }
            }, Modifier.fillMaxWidth(), cargando = guardando != null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Menu(etiqueta: String, opciones: List<Pair<String, String>>, valor: String, error: String? = null, vacio: String = "Elige una", alElegir: (String) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(abierto, { abierto = it }) {
        OutlinedTextField(opciones.firstOrNull { it.first == valor }?.second ?: vacio, {}, readOnly = true, label = { Text(etiqueta) }, isError = error != null,
            supportingText = error?.let { { Text(it, color = M.c.error) } },
            shape = RoundedCornerShape(E.radioControl), modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(abierto, { abierto = false }) {
            opciones.forEach { (k, n) -> DropdownMenuItem({ Text(n) }, { alElegir(k); abierto = false }) }
        }
    }
}

// ===================== Terminal virtual =====================

@Composable
fun TerminalVirtual(api: MercadoApi, ir: Ir) {
    val terms = rememberConsulta { api.terminales() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var pendientes by remember { mutableStateOf<List<TransaccionSim>?>(null) }
    var ocupado by remember { mutableStateOf<String?>(null) }
    var monto by remember { mutableStateOf("") }
    var errMonto by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { runCatching { api.simPendientes() }.onSuccess { pendientes = it }; delay(2000) } }
    val sims = terms.datos.orEmpty().filter { it.proveedor == "simulador" }
    Column(Modifier.fillMaxSize()) {
        BarraAtras("Terminal virtual", ir::atras)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = E.x4).padding(bottom = E.x8), verticalArrangement = Arrangement.spacedBy(E.x4)) {
            when {
                terms.cargando -> Esqueleto(Modifier.fillMaxWidth().height(360.dp), RoundedCornerShape(28.dp))
                sims.isEmpty() -> EstadoVacio("No tienes una terminal virtual", "Vincúlala en Vender → Terminales para probar cobros sin un aparato real.", "terminal") {
                    Boton("Vincular terminal virtual", { ir.vender("terminales") })
                }
                else -> {
                    Text("Simula la terminal «${sims[0].nombre}». Funciona como una real: avisa al sistema de pagos por notificación.", color = M.c.texto2, style = MaterialTheme.typography.bodyMedium)
                    val tx = pendientes?.firstOrNull()
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(androidx.compose.ui.graphics.Color(0xFF1B1A18)).padding(E.x6),
                        verticalArrangement = Arrangement.spacedBy(E.x4)) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(E.radioCard)).background(ColoresClaros.fondo).padding(E.x6).height(180.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            when {
                                pendientes == null -> Esqueleto(Modifier.fillMaxWidth().height(100.dp))
                                tx != null -> {
                                    Text("${tx.concepto.ifBlank { "Cobro" }} · ${tx.terminal}", color = ColoresClaros.texto2, style = MaterialTheme.typography.bodySmall)
                                    Text(pesos(tx.montoC), color = ColoresClaros.texto, style = MaterialTheme.typography.headlineMedium)
                                    Text("Acerca o inserta la tarjeta", color = ColoresClaros.marca, style = MaterialTheme.typography.labelLarge)
                                }
                                else -> { Text("Lista para cobrar", color = ColoresClaros.texto, style = MaterialTheme.typography.titleMedium)
                                    Text("Envía un cobro desde una orden, o cobra directo abajo.", color = ColoresClaros.texto2, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (tx != null) Row(horizontalArrangement = Arrangement.spacedBy(E.x2), modifier = Modifier.fillMaxWidth()) {
                            Boton("Rechazar", {
                                ocupado = tx.id + "r"
                                alcance.launch { runCatching { api.simResolver(tx.id, "rechazar") }; pendientes = pendientes?.drop(1); avisar("Tarjeta rechazada", true); ocupado = null }
                            }, Modifier.weight(1f), Variante.Peligro, cargando = ocupado == tx.id + "r", habilitado = ocupado == null)
                            Boton("Pasar tarjeta", {
                                ocupado = tx.id + "a"
                                alcance.launch { runCatching { api.simResolver(tx.id, "aprobar") }; pendientes = pendientes?.drop(1); avisar("Tarjeta aprobada. La terminal notificó al sistema.", false); ocupado = null }
                            }, Modifier.weight(1f), icono = "tarjeta", cargando = ocupado == tx.id + "a", habilitado = ocupado == null)
                        }
                    }
                    Tarjeta {
                        Titulo("Cobro directo en la terminal", grande = false)
                        Text("Como cuando cobras en el aparato sin usar la app. El sistema lo detecta por la notificación y lo registra solo.", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                        Campo("Monto (MXN)", monto, { monto = it; errMonto = null }, teclado = KeyboardType.Decimal, error = errMonto, placeholder = "350.00")
                        Boton("Cobrar y aprobar", {
                            val c = aCentavos(monto)
                            if (c == null || c < 100) { errMonto = "Escribe un monto desde $1"; return@Boton }
                            ocupado = "directo"
                            alcance.launch {
                                try { api.simDirecto(sims[0].id, c); monto = ""; avisar("Cobro directo de ${pesos(c)} aprobado. Revisa «Cobros recibidos».", false) }
                                catch (e: MercadoError) { errMonto = e.message }
                                ocupado = null
                            }
                        }, Modifier.fillMaxWidth(), Variante.Secundario, cargando = ocupado == "directo")
                    }
                }
            }
        }
    }
}

// ===================== Publicidad =====================

@Composable
private fun Publicidad(api: MercadoApi) {
    val planes = rememberConsulta { api.planes() }
    val pubs = rememberConsulta { api.mias() }
    val mias = rememberConsulta { api.misPromos() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var plan by remember { mutableStateOf("destacado") }
    var pub by remember { mutableStateOf<String>("") }
    var semanas by remember { mutableStateOf("2") }
    var mensaje by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf<String?>(null) }
    val activas = pubs.datos.orEmpty().filter { it.estado == "activa" }
    val elegido = planes.datos?.firstOrNull { it.clave == plan }
    Desplazable {
        Tarjeta {
            Titulo("Anuncia tu negocio", grande = false)
            Text("Tu producto aparece como «Patrocinado» en lugares fijos del mercado, sin tapar nada. Pagas por semana.",
                style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
            when {
                planes.cargando -> Column(verticalArrangement = Arrangement.spacedBy(E.x2)) { repeat(3) { Esqueleto(Modifier.fillMaxWidth().height(110.dp), RoundedCornerShape(E.radioCard)) } }
                planes.error != null -> EstadoError(planes.error!!, planes::recargar)
                else -> planes.datos!!.forEach { p ->
                    val sel = plan == p.clave
                    androidx.compose.material3.Surface(
                        onClick = { plan = p.clave }, shape = RoundedCornerShape(E.radioCard),
                        color = if (sel) M.c.marcaSuave else M.c.superficie,
                        border = androidx.compose.foundation.BorderStroke(if (sel) 2.dp else 1.dp, if (sel) M.c.marca else M.c.bordeFuerte),
                        modifier = Modifier.fillMaxWidth().androidx_compose_semantics_sel(sel),
                    ) {
                        Column(Modifier.padding(E.x4), verticalArrangement = Arrangement.spacedBy(E.x1)) {
                            Fila { Text(p.nombre, style = MaterialTheme.typography.titleMedium, color = M.c.texto); if (p.banner) Insignia("Banner", "acento") }
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(pesos(p.precioSemanaC), style = MaterialTheme.typography.headlineMedium, color = M.c.texto)
                                Text(" / semana", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                            }
                            Text(p.descripcion, style = MaterialTheme.typography.bodySmall, color = M.c.texto2)
                        }
                    }
                }
            }
            Menu("Producto a anunciar", activas.map { it.id.toString() to it.titulo }, pub, vacio = if (activas.isEmpty()) "No tienes productos activos" else "Elige uno") { pub = it }
            Menu("Duración", listOf(1, 2, 4, 8, 12).map { it.toString() to "$it ${if (it == 1) "semana" else "semanas"}" }, semanas) { semanas = it }
            Campo("Mensaje del anuncio", mensaje, { mensaje = it.take(90) }, opcional = true, ayuda = "${mensaje.length}/90 · Ej. «Gorras para tu evento desde 50 piezas»")
            elegido?.let { Fila { Text("Total", color = M.c.texto2); Text(pesos(it.precioSemanaC * semanas.toInt()), style = MaterialTheme.typography.titleLarge, color = M.c.texto) } }
            Text("Prototipo: el cobro del plan aún no se realiza; el anuncio se activa al momento.", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
            error?.let { Alerta(it, "error") }
            Boton("Anunciar", {
                if (pub.isBlank()) { error = "Elige el producto que quieres anunciar"; return@Boton }
                ocupado = "crear"; error = null
                alcance.launch {
                    try {
                        api.crearPromo(pub.toLong(), plan, semanas.toInt(), mensaje.trim())
                        avisar("Anuncio activo por $semanas ${if (semanas == "1") "semana" else "semanas"}", false)
                        mensaje = ""; pub = ""; mias.recargar()
                    } catch (e: MercadoError) { error = e.message }
                    ocupado = null
                }
            }, Modifier.fillMaxWidth(), icono = "etiqueta", cargando = ocupado == "crear", habilitado = activas.isNotEmpty())
        }
        Titulo("Mis anuncios", grande = false)
        when {
            mias.cargando -> EsqueletoRenglones(2)
            mias.error != null -> EstadoError(mias.error!!, mias::recargar)
            mias.datos.isNullOrEmpty() -> EstadoVacio("Aún no anuncias nada", "Elige un plan arriba para que más negocios de Durango vean tus productos.", "etiqueta")
            else -> mias.datos!!.forEach { m ->
                val ctr = if (m.impresiones > 0) ((m.clics * 1000 / m.impresiones) / 10.0).toString() else "0.0"
                Renglon(null) {
                    Miniatura(api, m.publicacion.foto, m.publicacion.categoria)
                    Column(Modifier.weight(1f)) {
                        Text(m.publicacion.titulo, style = MaterialTheme.typography.labelLarge, color = M.c.texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Plan ${m.planNombre} · hasta ${fecha(m.fin)}", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                        Text("${m.impresiones} vistas · ${m.clics} clics · $ctr % de clics", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                        when { !m.vigente -> Insignia("Terminó"); m.estado == "activa" -> Insignia("Activo", "exito"); else -> Insignia("Pausado", "aviso") }
                    }
                    if (m.vigente) BotonIcono(if (m.estado == "activa") "Pausar anuncio" else "Reanudar anuncio", if (m.estado == "activa") "pausa" else "play", {
                        ocupado = "e${m.id}"
                        alcance.launch {
                            try { api.estadoPromo(m.id, if (m.estado == "activa") "pausada" else "activa"); mias.recargar() } catch (e: MercadoError) { avisar(e.message ?: "", true) }
                            ocupado = null
                        }
                    }, habilitado = ocupado != "e${m.id}")
                }
            }
        }
    }
}

private fun Modifier.androidx_compose_semantics_sel(sel: Boolean) = this.then(
    Modifier.semantics { stateDescription = if (sel) "Seleccionado" else "No seleccionado" },
)
