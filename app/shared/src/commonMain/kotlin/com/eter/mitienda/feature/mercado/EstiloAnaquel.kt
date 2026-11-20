package com.eter.mitienda.feature.mercado

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.logo_anaquel
import org.jetbrains.compose.resources.painterResource

/*
 * Piezas del diseño de la app Anaquel (la versión nueva), traídas al Mercado para
 * que las dos se vean como un solo producto: encabezado tipo perfil, barra flotante
 * con el botón central «＋», hoja de accesos con mosaicos de color, secciones con su
 * ícono y las mismas animaciones (rebote, entrada en cascada, pop y latido).
 */

/** Esta compilación es la app de los clientes (tiendas pequeñas que compran): sin vender ni cobrar. */
const val SOLO_COMPRAS = true

// Colores de apoyo de la marca (los mismos de la app Anaquel).
val VerdeClaro = Color(0xFF12A374)
val VerdeOscuro = Color(0xFF0B3D2E)
val Menta = Color(0xFF4FD1A2)
val RojoBurbuja = Color(0xFFE5484D)
val TintaFuerte = Color(0xFF17251F)

// ---------------- Animaciones ----------------

/** Se encoge un poco al presionar y regresa con resorte. */
fun Modifier.rebote(fuente: MutableInteractionSource, cuanto: Float = 0.9f): Modifier = composed {
    val presionado by fuente.collectIsPressedAsState()
    val s by animateFloatAsState(if (presionado) cuanto else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Aparece desde abajo con desvanecido; con [indice] se escalona (cascada). */
fun Modifier.entrada(indice: Int = 0, desdeY: Float = 40f): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(indice.coerceAtMost(12) * 45L)
        p.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * desdeY
        val s = 0.94f + 0.06f * p.value
        scaleX = s; scaleY = s
    }
}

/** Entra con un «pop» (crece pasándose un poquito y se asienta). */
fun Modifier.pop(indice: Int = 0): Modifier = composed {
    val p = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        delay(indice.coerceAtMost(12) * 40L)
        p.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    graphicsLayer { scaleX = p.value; scaleY = p.value; alpha = ((p.value - 0.4f) / 0.6f).coerceIn(0f, 1f) }
}

/** Late suavemente (globos de pendientes). */
fun Modifier.latido(fuerza: Float = 0.14f): Modifier = composed {
    val t = rememberInfiniteTransition()
    val s by t.animateFloat(1f, 1f + fuerza, infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    graphicsLayer { scaleX = s; scaleY = s }
}

@Composable
fun Burbuja(n: Int, modifier: Modifier = Modifier) {
    if (n <= 0) return
    Text(
        if (n > 99) "99+" else "$n", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.latido().background(RojoBurbuja, CircleShape).border(1.5.dp, Color.White, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

// ---------------- Encabezado tipo perfil ----------------

/** Avatar redondo con el degradado de la marca y las iniciales del negocio. */
@Composable
fun AvatarMarca(nombre: String, tam: androidx.compose.ui.unit.Dp = 46.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(tam).shadow(6.dp, CircleShape, spotColor = M.c.marca)
            .background(Brush.linearGradient(listOf(M.c.marca, VerdeClaro)), CircleShape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(iniciales(nombre).ifEmpty { "T" }, color = Color.White, fontWeight = FontWeight.Black, fontSize = (tam.value * 0.37f).sp)
    }
}

/** Encabezado de una línea: avatar, nombre, @usuario y una acción. */
@Composable
fun EncabezadoMercado(cuenta: Cuenta, demo: Boolean, onBuscar: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AvatarMarca(cuenta.nombre, modifier = Modifier.pop())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(cuenta.nombre, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = M.c.texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("@${cuenta.slug}", fontSize = 13.sp, color = M.c.texto2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (demo) Text("DEMO", fontSize = 10.sp, fontWeight = FontWeight.Black, color = M.c.marca, letterSpacing = 0.8.sp,
                    modifier = Modifier.background(M.c.marcaSuave, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp))
            }
        }
        val fuente = remember { MutableInteractionSource() }
        Box(
            Modifier.size(48.dp).rebote(fuente, 0.85f).clip(CircleShape)
                .clickable(interactionSource = fuente, indication = null, onClick = onBuscar)
                .semantics { role = Role.Button; contentDescription = "Buscar en el mercado" },
            contentAlignment = Alignment.Center,
        ) { Icon(icono("buscar"), null, tint = M.c.texto, modifier = Modifier.size(24.dp)) }
    }
}

/** Título grande de una sección con su ícono en un cuadro de color suave. */
@Composable
fun TituloSeccion(icon: String, color: Color, titulo: String, descripcion: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().entrada(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icono(icon), null, tint = color, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(titulo, fontWeight = FontWeight.Black, fontSize = 24.sp, color = M.c.texto, letterSpacing = (-0.3).sp, modifier = Modifier.semantics { heading() })
            Text(descripcion, fontSize = 13.sp, color = M.c.texto2)
        }
    }
}

// ---------------- Barra flotante ----------------

data class PestanaMercado(val clave: String, val texto: String, val icon: String, val burbuja: Int = 0)

/** Dos pestañas, el botón central «＋» y dos pestañas más (como la barra de Anaquel). */
@Composable
fun BarraMercado(izquierda: List<PestanaMercado>, derecha: List<PestanaMercado>, activa: String?, onElegir: (String) -> Unit, onMas: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(14.dp, RoundedCornerShape(28.dp), clip = false)
            .background(M.c.fondo, RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        izquierda.forEach { PestanaBarra(it, it.clave == activa, Modifier.weight(1f)) { onElegir(it.clave) } }
        BotonMas(onMas, Modifier.weight(1f))
        derecha.forEach { PestanaBarra(it, it.clave == activa, Modifier.weight(1f)) { onElegir(it.clave) } }
    }
}

@Composable
private fun PestanaBarra(p: PestanaMercado, activa: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (activa) M.c.marca else M.c.texto2)
    val escala by animateFloatAsState(if (activa) 1.18f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    val giro by animateFloatAsState(if (activa) 0f else -8f, spring(dampingRatio = Spring.DampingRatioHighBouncy))
    val punto by animateDpAsState(if (activa) 16.dp else 0.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    val fuente = remember { MutableInteractionSource() }
    Column(
        modifier.heightIn(min = 52.dp).rebote(fuente, 0.85f).clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Tab; selected = activa
                contentDescription = p.texto + if (p.burbuja > 0) ", ${p.burbuja} pendientes" else ""
            }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icono(p.icon), null, tint = color, modifier = Modifier.size(24.dp).scale(escala).graphicsLayer { rotationZ = if (activa) 0f else giro + 8f })
            Burbuja(p.burbuja, Modifier.align(Alignment.TopEnd).offset(x = 11.dp, y = (-6).dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(p.texto, fontSize = 11.sp, fontWeight = if (activa) FontWeight.Bold else FontWeight.Medium, color = color, maxLines = 1, softWrap = false)
        Box(Modifier.padding(top = 2.dp).size(width = punto.coerceAtLeast(4.dp), height = 4.dp).background(if (activa) M.c.marca else Color.Transparent, CircleShape))
    }
}

/** Botón central: pastilla oscura con dos sombras de color que se abren al presionar. */
@Composable
private fun BotonMas(onClick: () -> Unit, modifier: Modifier) {
    val fuente = remember { MutableInteractionSource() }
    val presionado by fuente.collectIsPressedAsState()
    val abre by animateDpAsState(if (presionado) 6.dp else 3.dp, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMedium))
    val gira by animateFloatAsState(if (presionado) 90f else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    Box(modifier.heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 54.dp, height = 38.dp).rebote(fuente, 0.88f)
                .clickable(interactionSource = fuente, indication = null, onClick = onClick)
                .semantics { role = Role.Button; contentDescription = "Accesos rápidos: buscar, categorías y tus compras" },
        ) {
            Box(Modifier.fillMaxSize().offset(x = -abre).background(VerdeClaro, RoundedCornerShape(12.dp)))
            Box(Modifier.fillMaxSize().offset(x = abre).background(Menta, RoundedCornerShape(12.dp)))
            Box(Modifier.fillMaxSize().background(TintaFuerte, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icono("mas"), null, tint = Color.White, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = gira })
            }
        }
    }
}

// ---------------- Hoja de accesos ----------------

data class Atajo(val id: String, val icon: String, val color: Color, val titulo: String, val burbuja: Int = 0)
data class GrupoAtajos(val titulo: String, val atajos: List<Atajo>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaAccesos(grupos: List<GrupoAtajos>, onCerrar: () -> Unit, onElegir: (String) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = M.c.fondo, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        LazyVerticalGrid(
            GridCells.Fixed(4), Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoAnaquel(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("¿Qué necesitas hoy?", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = M.c.texto)
                        Text("Surte tu tienda con negocios de Durango", fontSize = 12.sp, color = M.c.texto2)
                    }
                }
            }
            var n = 0
            grupos.forEach { g ->
                val base = n; n += g.atajos.size + 1
                item(span = { GridItemSpan(maxLineSpan) }, key = "g-" + g.titulo) {
                    Text(g.titulo.uppercase(), Modifier.padding(top = 10.dp).entrada(base), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = M.c.texto2, letterSpacing = 1.sp)
                }
                items(g.atajos.size, key = { g.titulo + g.atajos[it].id }) { k -> val a = g.atajos[k]; Mosaico(a, base + 1 + k) { onElegir(a.id) } }
            }
        }
    }
}

@Composable
private fun Mosaico(a: Atajo, indice: Int, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().pop(indice).rebote(fuente, 0.86f)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .clearAndSetSemantics { role = Role.Button; contentDescription = a.titulo + if (a.burbuja > 0) ". ${a.burbuja} pendientes" else "" }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier.size(58.dp).shadow(8.dp, RoundedCornerShape(18.dp), spotColor = a.color)
                    .background(Brush.linearGradient(listOf(a.color.copy(alpha = 0.85f), a.color)), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icono(a.icon), null, tint = Color.White, modifier = Modifier.size(28.dp)) }
            Burbuja(a.burbuja, Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(a.titulo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = M.c.texto, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 14.sp)
    }
}

// ---------------- Categorías como secciones ----------------

/** Fila de categorías: ícono en cuadro de color y nombre; la activa se rellena de su color. */
@Composable
fun BarraCategorias(cats: List<Categoria>, activa: String, onElegir: (String) -> Unit) {
    val todas = listOf(Categoria("", "Todo", "todo")) + cats
    LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(todas, key = { _, c -> c.clave.ifEmpty { "todo" } }) { i, c ->
            val color = if (c.clave.isEmpty()) M.c.marca else TONOS[c.clave] ?: TONOS.getValue("otros")
            MosaicoCategoria(c.nombre, ICONO_CATEGORIA[c.clave] ?: if (c.clave.isEmpty()) "todo" else c.icono, color, c.clave == activa, Modifier.pop(i)) {
                onElegir(if (c.clave == activa) "" else c.clave)
            }
        }
    }
}

@Composable
private fun MosaicoCategoria(nombre: String, icon: String, color: Color, activa: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    val fondo by animateColorAsState(if (activa) color else Color.White)
    val tinta by animateColorAsState(if (activa) Color.White else color)
    val texto by animateColorAsState(if (activa) color else M.c.texto2)
    val escala by animateFloatAsState(if (activa) 1.08f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    val raya by animateDpAsState(if (activa) 22.dp else 0.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    Column(
        modifier.width(78.dp).heightIn(min = 76.dp).rebote(fuente, 0.88f).clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Tab; selected = activa; contentDescription = nombre }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(46.dp).graphicsLayer { scaleX = escala; scaleY = escala }
                .shadow(if (activa) 10.dp else 3.dp, RoundedCornerShape(15.dp), spotColor = color)
                .background(fondo, RoundedCornerShape(15.dp))
                .then(if (activa) Modifier else Modifier.border(1.dp, color.copy(alpha = 0.18f), RoundedCornerShape(15.dp))),
            contentAlignment = Alignment.Center,
        ) { Icon(icono(icon), null, tint = tinta, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(5.dp))
        Text(nombre, color = texto, fontSize = 12.sp, fontWeight = if (activa) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Box(Modifier.padding(top = 3.dp).size(width = raya, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
    }
}

// ---------------- Logo ----------------

/** Logo de Anaquel (anaqueles con productos) con la sombra verde de la marca. */
@Composable
fun LogoAnaquel(tam: androidx.compose.ui.unit.Dp = 48.dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painterResource(Res.drawable.logo_anaquel), contentDescription = "Anaquel",
        modifier = modifier.size(tam).shadow(10.dp, RoundedCornerShape(tam * 0.22f), spotColor = M.c.marca),
    )
}

/** Logo + «Anaquel Mercado», como en la portada de la app. */
@Composable
fun MarcaAnaquel(modifier: Modifier = Modifier, tam: androidx.compose.ui.unit.Dp = 48.dp) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Anaquel Mercado" }, verticalAlignment = Alignment.CenterVertically) {
        LogoAnaquel(tam, Modifier.pop())
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Anaquel", fontWeight = FontWeight.Black, fontSize = (tam.value * 0.5f).sp, color = M.c.texto, letterSpacing = (-0.5).sp, lineHeight = (tam.value * 0.55f).sp)
            Text("MERCADO", fontWeight = FontWeight.Black, fontSize = (tam.value * 0.22f).sp, color = M.c.marca, letterSpacing = 2.sp)
        }
    }
}
