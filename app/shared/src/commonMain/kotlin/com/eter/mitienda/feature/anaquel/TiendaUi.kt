package com.eter.mitienda.feature.anaquel

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.absoluteValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.util.formatPrice
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_add
import mitienda.shared.generated.resources.ic_cart
import mitienda.shared.generated.resources.ic_chat
import mitienda.shared.generated.resources.ic_store
import org.jetbrains.compose.resources.painterResource

// Piezas visuales tipo app de videos cortos: barra abajo con botón central «＋»,
// hoja de acciones que sube desde abajo y feed vertical a pantalla completa.
// Todo con botones grandes (≥ 48 dp) y descripciones para lector de pantalla.

// ---------------- Barra inferior ----------------

data class PestanaTienda(val clave: String, val texto: String, val icono: Painter, val burbuja: Int = 0)

/**
 * Barra flotante: dos pestañas, el botón «＋» (todas las herramientas) y dos
 * pestañas más. La activa se marca con color y un punto.
 */
@Composable
fun BarraTienda(
    izquierda: List<PestanaTienda>, derecha: List<PestanaTienda>, activa: String?,
    onElegir: (String) -> Unit, onMas: () -> Unit, modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(14.dp, RoundedCornerShape(28.dp), clip = false)
            .background(NeuColors.Background, RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        izquierda.forEach { Pestana(it, it.clave == activa, Modifier.weight(1f)) { onElegir(it.clave) } }
        BotonMas(onMas, Modifier.weight(1f))
        derecha.forEach { Pestana(it, it.clave == activa, Modifier.weight(1f)) { onElegir(it.clave) } }
    }
}

@Composable
private fun Pestana(p: PestanaTienda, activa: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (activa) NeuColors.Magenta else NeuColors.TextSecondary)
    val escala by animateFloatAsState(if (activa) 1.18f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    val giro by animateFloatAsState(if (activa) 0f else -8f, spring(dampingRatio = Spring.DampingRatioHighBouncy))
    val punto by animateDpAsState(if (activa) 16.dp else 0.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    val fuente = remember { MutableInteractionSource() }
    Column(
        modifier
            .heightIn(min = 52.dp)
            .rebote(fuente, 0.85f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Tab; selected = activa
                contentDescription = p.texto + if (p.burbuja > 0) ", ${p.burbuja} pendientes" else ""
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(p.icono, null, tint = color, modifier = Modifier.size(24.dp).scale(escala).graphicsLayer { rotationZ = if (activa) 0f else giro + 8f })
            if (p.burbuja > 0) AnimatedContent(
                p.burbuja, Modifier.align(Alignment.TopEnd).offset(x = 11.dp, y = (-6).dp).latido(),
                transitionSpec = { (slideInVertically { -it } + fadeIn()) togetherWith (slideOutVertically { it } + fadeOut()) },
            ) { n ->
                Text(
                    if (n > 99) "99+" else "$n", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.background(Color(0xFFE5484D), CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(p.texto, fontSize = 11.sp, fontWeight = if (activa) FontWeight.Bold else FontWeight.Medium, color = color, maxLines = 1, softWrap = false)
        Box(Modifier.padding(top = 2.dp).size(width = punto.coerceAtLeast(4.dp), height = 4.dp).background(if (activa) NeuColors.Magenta else Color.Transparent, CircleShape))
    }
}

/** Botón central: una pastilla con el verde de la marca y una sombra azul desplazada. */
@Composable
private fun BotonMas(onClick: () -> Unit, modifier: Modifier) {
    val fuente = remember { MutableInteractionSource() }
    val presionado by fuente.collectIsPressedAsState()
    // Al presionar, las dos sombras de color se abren y la cruz gira.
    val abre by animateDpAsState(if (presionado) 6.dp else 3.dp, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium))
    val gira by animateFloatAsState(if (presionado) 90f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    Box(modifier.heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 54.dp, height = 38.dp)
                .rebote(fuente, 0.88f)
                .clickable(interactionSource = fuente, indication = null, onClick = onClick)
                .semantics { role = Role.Button; contentDescription = "Todas tus herramientas: vender, surtir, foto, asesor" },
        ) {
            Box(Modifier.fillMaxSize().offset(x = -abre).background(NeuColors.Blue, RoundedCornerShape(12.dp)))
            Box(Modifier.fillMaxSize().offset(x = abre).background(Color(0xFF4FD1A2), RoundedCornerShape(12.dp)))
            Box(
                Modifier.fillMaxSize().background(NeuColors.TextPrimary, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(Res.drawable.ic_add), null, tint = Color.White, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = gira }) }
        }
    }
}

// ---------------- Hoja de acciones ----------------

/** Una herramienta del vendedor. */
data class Herramienta(val id: String, val emoji: String, val titulo: String, val detalle: String, val burbuja: Int = 0)

data class GrupoHerramientas(val titulo: String, val herramientas: List<Herramienta>)

/** Todas las herramientas del vendedor, agrupadas por lo que quiere lograr. */
fun herramientasDe(st: AnaquelUiState, urgentes: Int): List<GrupoHerramientas> {
    val r = st.resumen
    return listOf(
        GrupoHerramientas("Vender", listOf(
            Herramienta("vender", "🧾", "Cobrar", "Caja: escanea y cobra"),
            Herramienta("agregar", "➕", "Nuevo producto", "Alta con foto y código"),
            Herramienta("inventario", "📦", "Inventario", "Existencias y precios"),
            Herramienta("pedidos_clientes", "📋", "Encargos", "Pedidos de tus clientes", urgentes),
        )),
        GrupoHerramientas("Surtir", listOf(
            Herramienta("hoy", "🛒", "Qué pedir hoy", "Lo que se te acaba", r?.sinPedir ?: 0),
            Herramienta("foto", "📸", "Foto del anaquel", "Revisa qué falta", r?.sinFotoHoy ?: 0),
            Herramienta("pedidos", "🚚", "Pedidos a proveedor", "Lo que ya pediste", r?.atrasados ?: 0),
            Herramienta("bodega", "🧊", "Bodega 3D", "Dónde está cada cosa"),
        )),
        GrupoHerramientas("Red de comercio", listOf(
            Herramienta("red", "🔎", "Buscar en la red", "Quién lo tiene más barato", r?.avisos ?: 0),
            Herramienta("chats", "💬", "Chats", "Proveedores y tiendas", r?.chatsSinLeer ?: 0),
            Herramienta("tratos", "🤝", "Tratos", "Negociaciones abiertas", r?.tratosPendientes ?: 0),
            Herramienta("asesor", "✨", "Asesor", "Pregúntale a tu negocio"),
        )),
        GrupoHerramientas("Mi negocio", listOf(
            Herramienta("graficas", "📈", "Gráficas", "Ventas y ganancias"),
            Herramienta("categorias", "🗂️", "Categorías", "Ordena tus productos"),
            Herramienta("sincronizar", "☁️", "Respaldar", "Sube tu inventario"),
            Herramienta("bluetooth", "📶", "Otro teléfono", "Pasa datos por Bluetooth"),
        )),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaHerramientas(grupos: List<GrupoHerramientas>, onCerrar: () -> Unit, onElegir: (String) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = NeuColors.Background, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("¿Qué quieres hacer?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
                    Text("Todo tu negocio a un toque", style = MaterialTheme.typography.bodySmall, color = NeuColors.TextSecondary)
                }
            }
            var n = 0
            grupos.forEach { g ->
                val base = n; n += g.herramientas.size + 1
                item(span = { GridItemSpan(maxLineSpan) }, key = "g-" + g.titulo) {
                    Text(g.titulo.uppercase(), Modifier.padding(top = 10.dp).entrada(base), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold, color = NeuColors.TextSecondary, letterSpacing = 1.sp)
                }
                items(g.herramientas.size, key = { g.herramientas[it].id }) { k -> val h = g.herramientas[k]; Mosaico(h, base + 1 + k) { onElegir(h.id) } }
            }
        }
    }
}

@Composable
private fun Mosaico(h: Herramienta, indice: Int, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth()
            .pop(indice)
            .rebote(fuente, 0.86f)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = "${h.titulo}. ${h.detalle}" + if (h.burbuja > 0) ". ${h.burbuja} pendientes" else ""
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier.size(58.dp).shadow(6.dp, RoundedCornerShape(18.dp)).background(Color.White, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(h.emoji, fontSize = 26.sp) }
            if (h.burbuja > 0) Text(
                "${h.burbuja}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).latido()
                    .background(Color(0xFFE5484D), CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(h.titulo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NeuColors.TextPrimary, textAlign = TextAlign.Center,
            maxLines = 2, lineHeight = 14.sp)
    }
}

// ---------------- Feed vertical «Para ti» ----------------

/** Lo que sale en el feed: una oferta de la red o un proveedor grande. */
sealed class Publicacion(val clave: String) {
    class Oferta(val r: ResultadoRed, val etiqueta: String, val motivo: String?) : Publicacion("o-${r.clave}-$etiqueta")
    class Proveedor(val p: ProveedorGrande) : Publicacion("p-${p.id}")
}

/** Arma el feed mezclando lo que más le conviene primero. */
fun feedDe(ini: Inicio): List<Publicacion> {
    val out = mutableListOf<Publicacion>()
    val vistos = mutableSetOf<String>()
    fun oferta(r: ResultadoRed, etiqueta: String, motivo: String? = r.motivo) {
        if (vistos.add(r.clave)) out += Publicacion.Oferta(r, etiqueta, motivo)
    }
    ini.paraSurtir.forEach { s -> s.ofertas.firstOrNull()?.let { oferta(it, "Se te acaba", "Te quedan ${s.stock ?: 0} de ${s.nombre}; mínimo ${s.stockMin}") } }
    val provs = ini.proveedores.map { Publicacion.Proveedor(it) }.iterator()
    ini.convienen.forEachIndexed { i, r ->
        oferta(r, "Te conviene")
        if (i % 2 == 1 && provs.hasNext()) out += provs.next()
    }
    provs.forEach { out += it }
    ini.promociones.forEach { oferta(it, "Promoción") }
    ini.buscan.forEach { oferta(it, "Te buscan", "Quiere comprar ${it.cantidad ?: ""} ${it.unidad.orEmpty()}".trim()) }
    return out
}

private val tonos = listOf(
    listOf(Color(0xFF0E7C5A), Color(0xFF0B3D2E)), listOf(Color(0xFF2F5DA8), Color(0xFF14284D)),
    listOf(Color(0xFF8E44AD), Color(0xFF3B1C4A)), listOf(Color(0xFFD35400), Color(0xFF5A2300)),
    listOf(Color(0xFFC0392B), Color(0xFF4D1610)), listOf(Color(0xFF16A085), Color(0xFF0A3F35)),
)

/** Un dibujo para cada producto (no hay fotos en la red todavía). */
fun emojiDe(t: String, tipo: String = "producto"): String {
    val n = t.lowercase()
    val mapa = listOf(
        "leche" to "🥛", "yogurt" to "🥛", "queso" to "🧀", "mantequilla" to "🧈", "jamón" to "🥓", "salchicha" to "🌭",
        "agua" to "💧", "refresco" to "🥤", "jugo" to "🧃", "cerveza" to "🍺", "café" to "☕", "azúcar" to "🍚", "arroz" to "🍚",
        "frijol" to "🫘", "huevo" to "🥚", "atún" to "🐟", "sardina" to "🐟", "aceite" to "🫒", "pan" to "🍞", "galleta" to "🍪",
        "papas" to "🥔", "cacahuate" to "🥜", "botana" to "🥨", "chocolate" to "🍫", "dulce" to "🍬", "paleta" to "🍭",
        "pasta" to "🍝", "sopa" to "🍜", "harina" to "🌽", "maíz" to "🌽", "sal " to "🧂", "papel higiénico" to "🧻",
        "jabón" to "🧼", "cloro" to "🧴", "detergente" to "🧺", "cuaderno" to "📓", "pluma" to "🖊️", "lápiz" to "✏️",
        "cinta" to "📎", "tijera" to "✂️", "mochila" to "🎒", "papel" to "📄", "hojas" to "📄", "gorra" to "🧢",
        "playera" to "👕", "sudadera" to "🧥", "pantalón" to "👖", "vestido" to "👗", "zapato" to "👟", "calcet" to "🧦",
        "piñata" to "🪅", "flete" to "🚚", "impres" to "🖨️", "foco" to "💡", "pintura" to "🎨", "martillo" to "🔨",
    )
    return mapa.firstOrNull { n.contains(it.first) }?.second ?: when (tipo) {
        "solicitud" -> "🔎"; "anuncio" -> "🏷️"; "productor" -> "🌾"; else -> "📦"
    }
}

@Composable
fun FeedParaTi(
    feed: List<Publicacion>, modifier: Modifier = Modifier,
    onPedir: (Publicacion) -> Unit, onChat: (Long) -> Unit, onNegociar: (ResultadoRed) -> Unit,
) {
    val pager = rememberPagerState { feed.size }
    VerticalPager(pager, modifier.fillMaxSize(), key = { feed[it].clave }, beyondViewportPageCount = 1) { i ->
        val pub = feed[i]
        // Al deslizar, la página que se va se aleja y se oscurece; la que llega crece.
        Box(Modifier.graphicsLayer {
            val d = pager.desfase(i)
            val s = 1f - 0.08f * d.absoluteValue.coerceAtMost(1f)
            scaleX = s; scaleY = s
            alpha = 1f - 0.35f * d.absoluteValue.coerceAtMost(1f)
        }) {
            PaginaFeed(pub, i, feed.size, activa = pager.currentPage == i, onPedir = { onPedir(pub) }, onChat = onChat, onNegociar = onNegociar)
        }
    }
}

private fun PagerState.desfase(pagina: Int): Float = (currentPage - pagina) + currentPageOffsetFraction

@Composable
private fun PaginaFeed(pub: Publicacion, i: Int, total: Int, activa: Boolean, onPedir: () -> Unit, onChat: (Long) -> Unit, onNegociar: (ResultadoRed) -> Unit) {
    val tono = tonos[(pub.clave.hashCode() and 0x7fffffff) % tonos.size]
    val tienda: TiendaCorta?; val titulo: String; val emoji: String; val etiqueta: String
    val precio: String?; val detalle: String?; val km: Double?
    when (pub) {
        is Publicacion.Oferta -> {
            val r = pub.r
            tienda = r.tienda; titulo = r.titulo; emoji = emojiDe(r.titulo, r.tipo); etiqueta = pub.etiqueta
            val costo = if ((r.precioMayoreoC ?: 0) > 0) r.precioMayoreoC!! else r.precioC ?: 0
            precio = when {
                costo > 0 -> "${formatPrice(costo)} c/u" + ((r.minimoMayoreo ?: 0).takeIf { it > 0 }?.let { " desde $it ${r.unidad ?: "pza"}" } ?: "")
                !r.precio.isNullOrBlank() -> r.precio
                else -> null
            }
            detalle = listOfNotNull(pub.motivo?.takeIf { it.isNotBlank() }, r.detalle.takeIf { it.isNotBlank() },
                r.disponible?.let { "Hay $it disponibles" }).joinToString(" · ").ifBlank { null }
            km = r.distanciaKm
        }
        is Publicacion.Proveedor -> {
            val p = pub.p
            tienda = TiendaCorta(p.id, p.nombre, p.slug, p.giro, p.telefono); titulo = p.nombre.removeSuffix(" (demo)")
            emoji = "🏭"; etiqueta = "Proveedor grande"
            precio = p.promocion?.takeIf { it.isNotBlank() }
            detalle = "${p.productos} productos · ${p.afines} de lo que tú vendes" +
                p.destacados.take(3).joinToString("") { d -> "\n• ${d.titulo} ${formatPrice(if ((d.precioMayoreoC ?: 0) > 0) d.precioMayoreoC!! else d.precioC ?: 0)}" }
            km = p.distanciaKm
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(tono))) {
        // Dibujo grande al centro, como la «foto» de la publicación.
        Text(emoji, fontSize = 140.sp, modifier = Modifier.align(Alignment.Center).offset(y = (-60).dp).pop().flotar().clearAndSetSemantics { })
        // Contador y etiqueta arriba.
        Row(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(etiqueta, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp))
            Spacer(Modifier.width(8.dp))
            Text("${i + 1} de $total", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
        // Texto abajo a la izquierda, sobre un velo oscuro para que se lea.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                .padding(start = 18.dp, end = 96.dp, top = 40.dp, bottom = 22.dp),
        ) {
            tienda?.let {
                Text("@${it.slug.ifBlank { it.nombre }}" + (km(km)?.let { k -> " · $k" } ?: ""), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Text(titulo, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 28.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.entrada(1))
            precio?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFF0B3D2E), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.entrada(2).latido(activa, 0.04f).background(Color(0xFFB8F5DC), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            detalle?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.entrada(3))
            }
            if (i == 0 && total > 1) {
                Spacer(Modifier.height(10.dp))
                Text("Desliza hacia arriba para ver más ↑", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.flotar(5f, 900))
            }
        }
        // Barra de acciones a la derecha.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            tienda?.let { t ->
                Box(
                    Modifier.size(52.dp).pop(1).latido(activa, 0.06f).border(2.dp, Color.White, CircleShape).background(Color.White.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t.nombre.removeSuffix(" (demo)").split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() },
                        color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            val pedirTxt = if (pub is Publicacion.Oferta && pub.r.tipo == "solicitud") "Ofrecer" else if (pub is Publicacion.Proveedor) "Catálogo" else "Pedir"
            AccionLateral(painterResource(Res.drawable.ic_cart), pedirTxt, onPedir)
            tienda?.let { t -> AccionLateral(painterResource(Res.drawable.ic_chat), "Chat") { onChat(t.id) } }
            if (pub is Publicacion.Oferta && pub.r.negociable) AccionLateral(null, "Negociar", emoji = "🤝") { onNegociar(pub.r) }
            if (pub is Publicacion.Proveedor) AccionLateral(painterResource(Res.drawable.ic_store), "Tienda") { onChat(pub.p.id) }
        }
    }
}

@Composable
private fun AccionLateral(icono: Painter?, texto: String, onClick: () -> Unit) = AccionLateral(icono, texto, null, onClick)

@Composable
private fun AccionLateral(icono: Painter?, texto: String, emoji: String?, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Column(
        Modifier.pop(2).rebote(fuente, 0.8f).clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = texto },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(50.dp).background(Color.Black.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            if (icono != null) Icon(icono, null, tint = Color.White, modifier = Modifier.size(26.dp))
            else Text(emoji.orEmpty(), fontSize = 24.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(texto, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Pestañas de texto arriba, centradas, con rayita bajo la activa. */
@Composable
fun PestanasArriba(opciones: List<String>, activa: Int, onElegir: (Int) -> Unit, claro: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        opciones.forEachIndexed { i, t ->
            val on = i == activa
            val base = if (claro) Color.White else NeuColors.TextPrimary
            Column(
                Modifier.clickable { onElegir(i) }.semantics { role = Role.Tab; selected = on }.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val ancho by animateDpAsState(if (on) 28.dp else 0.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                val tam by animateFloatAsState(if (on) 1.08f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                Text(t, color = if (on) base else base.copy(alpha = 0.6f), fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, fontSize = 17.sp,
                    modifier = Modifier.scale(tam))
                Box(Modifier.padding(top = 4.dp).size(width = ancho, height = 3.dp).background(base, RoundedCornerShape(2.dp)))
            }
        }
    }
}
