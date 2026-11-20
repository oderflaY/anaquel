package com.eter.mitienda.feature.mercado

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ===================== Explorar =====================

@Composable
fun Explorar(api: MercadoApi, ir: Ir, q: String, categoria: String) {
    var texto by remember(q) { mutableStateOf(q) }
    val cats = rememberConsulta { api.categorias() }
    val pagina = rememberConsulta(q, categoria) { api.publicaciones(q, categoria) }
    // Anuncios: los decide el servidor (mismo orden que la web). Si fallan, el catálogo sigue igual.
    val anuncios = rememberConsulta(q, categoria) { runCatching { api.espacios(q, categoria) }.getOrDefault(Espacios(null, emptyList())) }
    val extra = remember(q, categoria) { mutableStateListOf<Publicacion>() }
    var siguiente by remember(q, categoria) { mutableStateOf<Long?>(null) }
    var cargandoMas by remember(q, categoria) { mutableStateOf(false) }
    val alcance = rememberCoroutineScope()
    LaunchedEffect(pagina.datos) { extra.clear(); siguiente = pagina.datos?.siguiente }

    // Búsqueda con pausa: no se pide al servidor por cada letra.
    LaunchedEffect(texto) {
        if (texto == q) return@LaunchedEffect
        delay(400)
        ir.explorar(texto, categoria)
    }
    val estado = rememberLazyGridState()
    val alFinal by remember { derivedStateOf { (estado.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= estado.layoutInfo.totalItemsCount - 4 } }
    fun cargarMas() {
        val s = siguiente ?: return
        if (cargandoMas) return
        cargandoMas = true
        alcance.launch {
            runCatching { api.publicaciones(q, categoria, antes = s) }.onSuccess { extra.addAll(it.items); siguiente = it.siguiente }
            cargandoMas = false
        }
    }
    LaunchedEffect(alFinal, siguiente) { if (alFinal && siguiente != null && !pagina.cargando) cargarMas() }

    val nombreCat = cats.datos?.firstOrNull { it.clave == categoria }?.nombre
    val items = intercalar((pagina.datos?.items ?: emptyList()) + extra, anuncios.datos?.productos.orEmpty())
    val banner = anuncios.datos?.banner
    LazyVerticalGrid(
        GridCells.Adaptive(160.dp), state = estado, contentPadding = PaddingValues(E.x4),
        horizontalArrangement = Arrangement.spacedBy(E.x3), verticalArrangement = Arrangement.spacedBy(E.x3), modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(E.x3)) {
                TituloSeccion(
                    if (q.isNotBlank()) "buscar" else ICONO_CATEGORIA[categoria] ?: "tienda",
                    if (categoria.isBlank()) M.c.marca else TONOS[categoria] ?: M.c.marca,
                    if (q.isNotBlank()) "Resultados" else nombreCat ?: "Mercado local",
                    if (q.isNotBlank()) "Para «$q»" else "Surte tu tienda con negocios de Durango",
                )
                OutlinedTextField(
                    texto, { texto = it.take(80) }, singleLine = true,
                    placeholder = { Text("Buscar gorras, queso, fletes…", color = M.c.texto3) },
                    leadingIcon = { Icon(icono("buscar"), null, Modifier.size(20.dp), tint = M.c.texto3) },
                    trailingIcon = if (texto.isNotEmpty()) { { BotonIcono("Borrar búsqueda", "cerrar", { texto = "" }) } } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { ir.explorar(texto, categoria) }),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = M.c.superficie, focusedContainerColor = M.c.superficie,
                        unfocusedBorderColor = M.c.bordeFuerte, focusedBorderColor = M.c.marca),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Buscar productos o negocios" },
                )
                if (cats.cargando) Row(horizontalArrangement = Arrangement.spacedBy(E.x2)) { repeat(4) { Esqueleto(Modifier.size(78.dp, 76.dp), RoundedCornerShape(18.dp)) } }
                else BarraCategorias(cats.datos.orEmpty(), categoria) { ir.explorar(q, it) }
            }
        }
        when {
            pagina.cargando -> items(6) { Cargando("Cargando productos…") { EsqueletoProducto() } }
            pagina.error != null -> item(span = { GridItemSpan(maxLineSpan) }) { EstadoError(pagina.error!!, pagina::recargar) }
            items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                EstadoVacio(
                    if (q.isNotBlank()) "No encontramos nada con esa búsqueda" else "Aún no hay productos aquí",
                    if (q.isNotBlank()) "Prueba con otra palabra o quita el filtro de categoría." else "Vuelve pronto: los negocios de Durango publican todos los días.",
                    "buscar",
                ) { if (q.isNotBlank() || categoria.isNotBlank()) Boton("Ver todo", { texto = ""; ir.explorar() }, variante = Variante.Secundario) }
            }
            else -> {
                if (banner != null) item(span = { GridItemSpan(maxLineSpan) }, key = "banner") {
                    BannerPatrocinado(api, banner) { alcance.launch { api.clicPromo(banner.promoId) }; ir.producto(banner.publicacion.id) }
                }
                items(items, key = { (p, a) -> (if (a != null) "a" else "o") + p.id }) { (p, a) ->
                    TarjetaProducto(api, p, {
                        if (a != null) alcance.launch { api.clicPromo(a.promoId) }
                        ir.producto(p.id)
                    }, patrocinado = a != null)
                }
                if (siguiente != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(E.x4), contentAlignment = Alignment.Center) {
                        Boton("Ver más productos", ::cargarMas, variante = Variante.Secundario, cargando = cargandoMas)
                    }
                }
            }
        }
    }
}

// ===================== Detalle =====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Detalle(api: MercadoApi, ir: Ir, id: Long) {
    val q = rememberConsulta(id) { api.publicacion(id) }
    val d = q.datos
    Column(Modifier.fillMaxSize()) {
        BarraAtras(d?.publicacion?.titulo ?: "Producto", ir::atras)
        when {
            q.cargando && d == null -> Cargando("Cargando producto…") {
                Column(Modifier.padding(E.x4), verticalArrangement = Arrangement.spacedBy(E.x3)) {
                    Esqueleto(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(E.radioCard))
                    Esqueleto(Modifier.fillMaxWidth(.8f).height(28.dp)); Esqueleto(Modifier.fillMaxWidth(.4f).height(36.dp))
                    Esqueleto(Modifier.fillMaxWidth().height(160.dp), RoundedCornerShape(E.radioCard))
                }
            }
            q.error != null -> EstadoError(q.error!!, q::recargar)
            d != null -> ContenidoDetalle(api, ir, d.publicacion, d.vendedor, q::recargar)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContenidoDetalle(api: MercadoApi, ir: Ir, p: Publicacion, v: Vendedor, recargar: () -> Unit) {
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    val fotos = p.fotos.orEmpty()
    var cantidad by remember(p.id) { mutableStateOf(p.minimo.coerceAtLeast(1).toString()) }
    var confirmar by remember { mutableStateOf(false) }
    var nota by remember { mutableStateOf("") }
    var ocupado by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val n = cantidad.toIntOrNull() ?: 0
    val max = p.existencias ?: 100_000
    val valida = n >= p.minimo && n <= max
    val total = n.toLong() * p.precioC

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = E.x4).padding(bottom = E.x8),
        verticalArrangement = Arrangement.spacedBy(E.x4)) {
        val pager = rememberPagerState { fotos.size.coerceAtLeast(1) }
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(E.radioCard))) {
            HorizontalPager(pager) { i -> ImagenMercado(api, fotos.getOrNull(i), p.categoria, Modifier.fillMaxSize()) }
            if (fotos.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(E.x2), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(fotos.size) { i -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == pager.currentPage) M.c.superficie else M.c.superficie.copy(alpha = .5f))) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(E.x1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(E.x2)) { InsigniaExistencias(p); if (p.estado == "pausada") Insignia("Pausada", "aviso") }
            Titulo(p.titulo)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(pesos(p.precioC), style = MaterialTheme.typography.headlineMedium.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize * 1.2f), color = M.c.texto)
                Text(" por ${unidad(p.unidad)}", style = MaterialTheme.typography.bodyMedium, color = M.c.texto3)
            }
            if (p.minimo > 1) Text("Compra mínima: ${cantidadTxt(p.minimo, p.unidad)}", style = MaterialTheme.typography.bodyMedium, color = M.c.texto2)
            if ((p.existencias ?: 0) > 0) Text("${cantidadTxt(p.existencias!!, p.unidad)} disponibles", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
        }
        when {
            p.esMia -> Tarjeta {
                Alerta("Esta es tu publicación. Así la ven los compradores.")
                Boton("Editar publicación", { ir.publicar(p.id) }, Modifier.fillMaxWidth(), Variante.Secundario, icono = "editar")
            }
            p.existencias == 0 -> Alerta("Se agotó. Revisa otros vendedores o vuelve después.", "aviso")
            else -> Tarjeta {
                Fila {
                    Text("Cantidad", style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                    Row(Modifier.clip(RoundedCornerShape(E.radioControl)).background(M.c.superficie), verticalAlignment = Alignment.CenterVertically) {
                        BotonIcono("Quitar uno", "menos", { cantidad = (n - 1).coerceAtLeast(p.minimo).toString() }, habilitado = n > p.minimo)
                        OutlinedTextField(cantidad, { t -> cantidad = t.filter { it.isDigit() }.take(6) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = MaterialTheme.typography.labelLarge.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                            modifier = Modifier.width(88.dp).semantics { contentDescription = "Cantidad" })
                        BotonIcono("Agregar uno", "mas", { cantidad = (n + 1).coerceAtMost(max).toString() }, habilitado = n < max)
                    }
                }
                if (!valida) Text(if (n < p.minimo) "El mínimo es ${cantidadTxt(p.minimo, p.unidad)}" else "Solo hay ${cantidadTxt(max, p.unidad)}",
                    style = MaterialTheme.typography.bodySmall, color = M.c.error)
                Fila { Text("Total", color = M.c.texto2); Text(pesos(total), style = MaterialTheme.typography.titleLarge, color = M.c.texto) }
                Boton("Apartar", { error = null; confirmar = true }, Modifier.fillMaxWidth(), icono = "check", habilitado = valida)
                Text("Pagas con tarjeta en la terminal del vendedor al recogerlo.", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
            }
        }
        p.descripcion?.takeIf { it.isNotBlank() }?.let {
            Column(verticalArrangement = Arrangement.spacedBy(E.x1)) {
                Titulo("Descripción", grande = false)
                Text(it, style = MaterialTheme.typography.bodyMedium, color = M.c.texto2)
            }
        }
        Tarjeta {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x3)) {
                Avatar(v.nombre)
                Column(Modifier.weight(1f)) {
                    Text(v.nombre, style = MaterialTheme.typography.titleMedium, color = M.c.texto)
                    Text(v.colonia + (p.distanciaKm?.let { " · a $it km" } ?: ""), style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                }
            }
            Boton("Ver sus productos", { ir.vendedor(v.slug) }, variante = Variante.Secundario, chico = true)
        }
    }

    if (confirmar) AlertDialog(
        onDismissRequest = { if (!ocupado) confirmar = false },
        containerColor = M.c.superficie, shape = RoundedCornerShape(E.radioModal),
        title = { Text("¿Apartar este producto?", color = M.c.texto) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(E.x3)) {
                Text("${p.titulo}\n${cantidadTxt(n, p.unidad)} · ${v.nombre}", color = M.c.texto2)
                Fila { Text("Total a pagar al recoger", color = M.c.texto2); Text(pesos(total), style = MaterialTheme.typography.titleLarge, color = M.c.texto) }
                Campo("Nota para el vendedor", nota, { nota = it.take(300) }, opcional = true, placeholder = "Ej. paso el jueves en la tarde", lineas = 2)
                error?.let { Alerta(it, "error") }
            }
        },
        confirmButton = {
            Boton("Confirmar", {
                ocupado = true; error = null
                alcance.launch {
                    try {
                        val oid = api.apartar(p.id, n, nota)
                        confirmar = false
                        avisar("Listo, lo apartaste. Paga con tarjeta al recogerlo.", false)
                        ir.orden(oid)
                    } catch (e: MercadoError) {
                        error = e.message
                        if (e.clave == "sin_existencias" || e.clave == "agotado") recargar()
                    }
                    ocupado = false
                }
            }, cargando = ocupado)
        },
        dismissButton = { Boton("Cancelar", { confirmar = false }, variante = Variante.Secundario, habilitado = !ocupado) },
    )
}

// ===================== Vendedor =====================

@Composable
fun PaginaVendedor(api: MercadoApi, ir: Ir, slug: String) {
    val perfil = rememberConsulta(slug) { api.vendedor(slug) }
    val pubs = rememberConsulta(slug) { api.publicaciones(vendedor = slug) }
    Column(Modifier.fillMaxSize()) {
        BarraAtras(perfil.datos?.vendedor?.nombre ?: "Vendedor", ir::atras)
        LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(E.x4),
            horizontalArrangement = Arrangement.spacedBy(E.x3), verticalArrangement = Arrangement.spacedBy(E.x3)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val pv = perfil.datos
                when {
                    perfil.error != null -> EstadoError(perfil.error!!, perfil::recargar)
                    pv == null -> Cargando { Row(horizontalArrangement = Arrangement.spacedBy(E.x3)) { Esqueleto(Modifier.size(72.dp), CircleShape); Column { Esqueleto(Modifier.width(180.dp).height(24.dp)) } } }
                    else -> Tarjeta {
                        Row(horizontalArrangement = Arrangement.spacedBy(E.x3), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(pv.vendedor.nombre, 72.dp)
                            Column(Modifier.weight(1f)) {
                                Titulo(pv.vendedor.nombre, grande = false)
                                Text("${pv.vendedor.colonia} · ${pv.publicaciones} ${if (pv.publicaciones == 1) "producto" else "productos"}",
                                    style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                            }
                        }
                        if (pv.vendedor.descripcion.isNotBlank()) Text(pv.vendedor.descripcion, style = MaterialTheme.typography.bodyMedium, color = M.c.texto2)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Titulo("Productos", grande = false) }
            when {
                pubs.cargando -> items(4) { EsqueletoProducto() }
                pubs.error != null -> item(span = { GridItemSpan(maxLineSpan) }) { EstadoError(pubs.error!!, pubs::recargar) }
                pubs.datos?.items.isNullOrEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { EstadoVacio("Este negocio aún no publica productos", icono = "etiqueta") }
                else -> items(pubs.datos!!.items, key = { it.id }) { p -> TarjetaProducto(api, p, { ir.producto(p.id) }) }
            }
        }
    }
}

@Composable
internal fun Miniatura(api: MercadoApi, foto: Foto?, categoria: String = "otros", tam: androidx.compose.ui.unit.Dp = 56.dp) =
    ImagenMercado(api, foto, categoria, Modifier.size(tam).clip(RoundedCornerShape(E.radioControl)), conAlt = false)

@Composable
internal fun Renglon(onClick: (() -> Unit)?, contenido: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val mod = Modifier.fillMaxWidth()
    if (onClick != null) Surface(onClick = onClick, color = M.c.superficie, shape = RoundedCornerShape(E.radioCard), border = BorderStroke(1.dp, M.c.borde), modifier = mod) {
        Row(Modifier.padding(E.x3).height(androidx.compose.foundation.layout.IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x3), content = contenido)
    } else Surface(color = M.c.superficie, shape = RoundedCornerShape(E.radioCard), border = BorderStroke(1.dp, M.c.borde), modifier = mod) {
        Row(Modifier.padding(E.x3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x3), content = contenido)
    }
}
