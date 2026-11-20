package com.eter.mitienda.feature.anaquel

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.theme.NeuColors

// Navegación de «Mi tienda»: encabezado compacto, barra de secciones con íconos
// (la activa con pastilla de su color) y un título grande por sección.

/** Cómo se ve cada sección: ícono, color y para qué sirve. */
data class EstiloSeccion(val icono: ImageVector, val color: Color, val titulo: String, val descripcion: String)

fun estiloDe(s: SeccionAnaquel): EstiloSeccion = when (s) {
    SeccionAnaquel.HOY -> EstiloSeccion(Ico.PedirHoy, Color(0xFFE5484D), "Hoy", "Lo que se te acaba y a quién pedírselo")
    SeccionAnaquel.BODEGA -> EstiloSeccion(Ico.Bodega, Color(0xFF0097A7), "Mi bodega", "Dónde está cada producto")
    SeccionAnaquel.ASESOR -> EstiloSeccion(Ico.Asesor, Color(0xFF8E44AD), "Tu socio", "Tu amigo y asesor de negocios")
    SeccionAnaquel.RED -> EstiloSeccion(Ico.Buscar, Color(0xFF3F51B5), "Red", "Quién lo tiene más barato cerca de ti")
    SeccionAnaquel.TRATOS -> EstiloSeccion(Ico.Trato, Color(0xFFE0871B), "Tratos", "Tus negociaciones con otras tiendas")
    SeccionAnaquel.ANAQUELES -> EstiloSeccion(Ico.Foto, Color(0xFF00897B), "Anaqueles", "Una foto al día y sabes qué falta")
    SeccionAnaquel.PEDIDOS -> EstiloSeccion(Ico.Camion, Color(0xFF2F5DA8), "Pedidos", "Lo que pediste a tus proveedores")
    SeccionAnaquel.MAYOREO -> EstiloSeccion(Ico.Mayoreo, Color(0xFF0E7C5A), "Vender al mayoreo", "Publica tus precios y elige quién los ve")
}

fun pendientesDe(s: SeccionAnaquel, st: AnaquelUiState): Int = when (s) {
    SeccionAnaquel.HOY -> st.resumen?.sinPedir ?: 0
    SeccionAnaquel.PEDIDOS -> st.resumen?.atrasados ?: 0
    SeccionAnaquel.ANAQUELES -> st.resumen?.sinFotoHoy ?: 0
    SeccionAnaquel.TRATOS -> st.resumen?.tratosPendientes ?: 0
    SeccionAnaquel.RED -> st.resumen?.avisos ?: 0
    SeccionAnaquel.ASESOR, SeccionAnaquel.BODEGA, SeccionAnaquel.MAYOREO -> 0
}

/** Encabezado de una sola línea: avatar, nombre y acciones (respaldar, herramientas, menú). */
@Composable
fun EncabezadoTienda(
    st: AnaquelUiState, onRespaldar: () -> Unit, onHerramientas: () -> Unit, onSalir: () -> Unit, onReiniciarDemo: (() -> Unit)?,
) {
    val nombre = st.tienda?.nombre.orEmpty().removeSuffix(" (demo)")
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(46.dp).pop().shadow(6.dp, CircleShape, spotColor = NeuColors.Magenta)
                .background(Brush.linearGradient(listOf(NeuColors.Magenta, NeuColors.Blue)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(nombre.split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "T" },
                color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(nombre, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = NeuColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            st.tienda?.slug?.takeIf { it.isNotBlank() }?.let {
                Text("@$it", fontSize = 13.sp, color = NeuColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BotonIcono(Ico.Nube, "Respaldar inventario en la nube", onRespaldar)
        BotonIcono(Icons.Rounded.Apps, "Todas mis herramientas", onHerramientas)
        Box {
            BotonIcono(Icons.Rounded.MoreVert, "Más opciones") { menu = true }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                onReiniciarDemo?.let { r ->
                    DropdownMenuItem(text = { Text("Reiniciar demo") }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) }, onClick = { menu = false; r() })
                }
                DropdownMenuItem(text = { Text("Cerrar sesión") }, leadingIcon = { Icon(Icons.Rounded.Logout, null) }, onClick = { menu = false; onSalir() })
            }
        }
    }
}

@Composable
private fun BotonIcono(icono: ImageVector, descripcion: String, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp).rebote(fuente, 0.85f).clip(CircleShape)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = descripcion },
        contentAlignment = Alignment.Center,
    ) { Icon(icono, null, tint = NeuColors.TextPrimary, modifier = Modifier.size(24.dp)) }
}

/**
 * Barra de secciones: cada una con su ícono en un círculo de color y su nombre.
 * La activa se rellena de su color y se centra sola en la pantalla.
 */
@Composable
fun BarraSecciones(secciones: List<SeccionAnaquel>, activa: SeccionAnaquel, st: AnaquelUiState, onElegir: (SeccionAnaquel) -> Unit) {
    val lista = rememberLazyListState()
    LaunchedEffect(activa) {
        val i = secciones.indexOf(activa)
        if (i >= 0) lista.animateScrollToItem((i - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = lista,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(secciones, key = { _, s -> s.name }) { i, s ->
            PestanaSeccion(estiloDe(s), s == activa, pendientesDe(s, st), Modifier.pop(i)) { onElegir(s) }
        }
    }
}

@Composable
private fun PestanaSeccion(e: EstiloSeccion, activa: Boolean, pendientes: Int, modifier: Modifier, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    val fondo by animateColorAsState(if (activa) e.color else Color.White)
    val tinta by animateColorAsState(if (activa) Color.White else e.color)
    val texto by animateColorAsState(if (activa) e.color else NeuColors.TextSecondary)
    val escala by animateFloatAsState(if (activa) 1.08f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    val raya by animateDpAsState(if (activa) 22.dp else 0.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    Column(
        modifier.width(78.dp).heightIn(min = 76.dp).rebote(fuente, 0.88f).clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Tab; selected = activa
                contentDescription = e.titulo + if (pendientes > 0) ", $pendientes pendientes" else ""
            }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier.size(46.dp).graphicsLayer { scaleX = escala; scaleY = escala }
                    .shadow(if (activa) 10.dp else 3.dp, RoundedCornerShape(15.dp), spotColor = e.color)
                    .background(fondo, RoundedCornerShape(15.dp))
                    .then(if (activa) Modifier else Modifier.border(1.dp, e.color.copy(alpha = 0.18f), RoundedCornerShape(15.dp))),
                contentAlignment = Alignment.Center,
            ) { Icon(e.icono, null, tint = tinta, modifier = Modifier.size(24.dp)) }
            if (pendientes > 0) Text(
                if (pendientes > 99) "99+" else "$pendientes", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-5).dp).latido()
                    .background(Color(0xFFE5484D), CircleShape).border(1.5.dp, Color.White, CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(e.titulo, color = texto, fontSize = 12.sp, fontWeight = if (activa) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1)
        Box(Modifier.padding(top = 3.dp).size(width = raya, height = 3.dp).background(e.color, RoundedCornerShape(2.dp)))
    }
}

/** Título grande de la sección con su ícono y descripción. */
@Composable
fun TituloSeccion(s: SeccionAnaquel) {
    val e = estiloDe(s)
    Row(Modifier.fillMaxWidth().entrada(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(e.color.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(e.icono, null, tint = e.color, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(e.titulo, fontWeight = FontWeight.Black, fontSize = 24.sp, color = NeuColors.TextPrimary, letterSpacing = (-0.3).sp)
            Text(e.descripcion, fontSize = 13.sp, color = NeuColors.TextSecondary)
        }
    }
}

/** Cifras del negocio como tarjetas de color que llevan a su sección. */
@Composable
fun TarjetasCifras(st: AnaquelUiState, onElegir: (SeccionAnaquel) -> Unit) {
    val r = st.resumen
    val datos = listOf(
        Triple(r?.productos ?: 0, "Productos", SeccionAnaquel.BODEGA),
        Triple(r?.sinPedir ?: 0, "Por pedir", SeccionAnaquel.HOY),
        Triple(r?.pedidosAbiertos ?: 0, "Pedidos", SeccionAnaquel.PEDIDOS),
        Triple(r?.tratosPendientes ?: 0, "Tratos", SeccionAnaquel.TRATOS),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        datos.forEachIndexed { i, (n, t, s) ->
            val e = estiloDe(s)
            val fuente = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).entrada(i).rebote(fuente, 0.92f)
                    .shadow(6.dp, RoundedCornerShape(16.dp), spotColor = e.color)
                    .background(Brush.linearGradient(listOf(e.color.copy(alpha = 0.92f), e.color)), RoundedCornerShape(16.dp))
                    .clickable(interactionSource = fuente, indication = null) { onElegir(s) }
                    .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = "$n $t" }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Icon(e.icono, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.height(4.dp))
                NumeroAnimado(n, Color.White, 22.sp, FontWeight.Black)
                Text(t, color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
