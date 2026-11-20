package com.eter.mitienda.feature.anaquel

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.components.NeumorphicInput
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.util.formatPrice
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_chat
import mitienda.shared.generated.resources.ic_close
import mitienda.shared.generated.resources.ic_refresh
import mitienda.shared.generated.resources.ic_search
import org.jetbrains.compose.resources.painterResource

// Inicio: lo que le conviene hoy a tu negocio (qué surtir y con quién, los
// proveedores grandes, promociones y quién busca lo que vendes).

/**
 * Marco común de las pestañas en la nube: espera la sesión, pide entrar si no
 * hay, y muestra los avisos (snackbar) y enlaces de WhatsApp del ViewModel.
 */
@Composable
internal fun MarcoAnaquel(vm: AnaquelViewModel, contenido: @Composable (AnaquelUiState) -> Unit) {
    val st by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val uri = LocalUriHandler.current
    LaunchedEffect(vm.mensaje) {
        vm.mensaje?.let { snackbar.showSnackbar(it, withDismissAction = true); vm.onMensajeMostrado() }
    }
    LaunchedEffect(vm.abrirEnlace) {
        vm.abrirEnlace?.let { runCatching { uri.openUri(it) }; vm.onEnlaceAbierto() }
    }
    Box(Modifier.fillMaxSize().background(NeuColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        when {
            st.revisandoSesion -> Txt("Cargando…", secundario = true, modifier = Modifier.align(Alignment.Center))
            st.tienda == null -> Entrar(st, vm)
            else -> contenido(st)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(NeuDimens.ScreenPadding)) {
            Snackbar(it, shape = NeuShapes.Large, containerColor = NeuColors.TextPrimary, contentColor = NeuColors.Background)
        }
    }
}

@Composable
internal fun Txt(
    t: String, modifier: Modifier = Modifier, secundario: Boolean = false, negritas: Boolean = false,
    chico: Boolean = false, color: androidx.compose.ui.graphics.Color? = null, lineas: Int = Int.MAX_VALUE,
) = Text(
    t, modifier = modifier,
    style = if (chico) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    fontWeight = if (negritas) FontWeight.Bold else null,
    color = color ?: if (secundario) NeuColors.TextSecondary else NeuColors.TextPrimary,
    maxLines = lineas, overflow = TextOverflow.Ellipsis,
)

@Composable
internal fun Pastilla(t: String, fondo: androidx.compose.ui.graphics.Color = NeuColors.ChipBackground, texto: androidx.compose.ui.graphics.Color = NeuColors.OnChip) = Text(
    t, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = texto, maxLines = 1,
    modifier = Modifier.background(fondo, NeuShapes.Pill).padding(horizontal = 8.dp, vertical = 2.dp),
)

@Composable
private fun Seccion(titulo: String, sub: String? = null) {
    Column(Modifier.padding(top = NeuDimens.SpaceSm)) {
        Text(titulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
        sub?.let { Txt(it, secundario = true, chico = true) }
    }
}

internal fun km(d: Double?): String? = d?.let { if (it < 1) "a ${(it * 1000).toInt()} m" else "a ${(it * 10).toInt() / 10.0} km" }

@Composable
fun InicioRoute(vm: AnaquelViewModel, onAbrirChats: () -> Unit) {
    MarcoAnaquel(vm) { st ->
        LaunchedEffect(st.tienda?.id) { vm.cargarInicio() }
        // Pedimos permiso de notificaciones una sola vez (Android 13+); si lo niega no insistimos.
        val permiso = com.eter.mitienda.core.notifications.rememberNotificationPermissionState()
        LaunchedEffect(st.tienda?.id) { if (st.tienda != null && !permiso.granted && vm.tocaPedirPermiso()) permiso.request() }
        Inicio(st, vm, onAbrirChats)
    }
}

/** Inicio con dos pestañas arriba: «Para ti» (feed vertical) y «Resumen» (todo en lista). */
@Composable
private fun Inicio(st: AnaquelUiState, vm: AnaquelViewModel, onAbrirChats: () -> Unit) {
    var pestana by rememberSaveable { mutableStateOf(0) }
    var negociando by remember { mutableStateOf<ResultadoRed?>(null) }
    val ini = st.inicio
    val feed = remember(ini) { ini?.let(::feedDe).orEmpty() }
    val chat = { tienda: Long, pedir: Boolean, producto: Long?, cantidad: Long ->
        vm.chatCon(tienda, pedir, producto, cantidad); onAbrirChats()
    }
    Box(Modifier.fillMaxSize()) {
        if (pestana == 0 && feed.isNotEmpty()) {
            FeedParaTi(
                feed,
                onPedir = { pub ->
                    when (pub) {
                        is Publicacion.Proveedor -> chat(pub.p.id, true, null, 0)
                        is Publicacion.Oferta -> {
                            val r = pub.r
                            val t = r.tienda
                            if (r.tipo == "solicitud" || t == null) negociando = r
                            else chat(t.id, true, r.id, (r.minimoMayoreo ?: 1).coerceAtLeast(1))
                        }
                    }
                },
                onChat = { chat(it, false, null, 0) },
                onNegociar = { negociando = it },
                onPerfil = vm::verPerfil,
            )
        } else {
            Resumen(st, vm, onAbrirChats, arriba = 56.dp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(56.dp))
            PestanasArriba(listOf("Para ti", "Resumen"), pestana, { pestana = it }, claro = pestana == 0 && feed.isNotEmpty(), modifier = Modifier.weight(1f))
            val avisos = (ini?.chatsSinLeer ?: 0) + (ini?.pendientes ?: 0)
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                NeumorphicIconButton(painterResource(Res.drawable.ic_search), "Buscar en la red", onClick = { pestana = 1 },
                    size = NeuDimens.IconButtonSmallSize, iconSize = NeuDimens.IconSmallSize)
                if (avisos > 0) Box(Modifier.align(Alignment.TopEnd)) { Pastilla("$avisos", NeuColors.Magenta, NeuColors.OnAccent) }
            }
        }
        if (pestana == 0 && feed.isEmpty() && ini == null) Txt("Cargando lo que te conviene…", secundario = true, modifier = Modifier.align(Alignment.Center))
    }
    negociando?.let { r ->
        DialogoNegociar(r, onCerrar = { negociando = null }) { n, pc, msg -> negociando = null; vm.negociar(r, n, pc, msg) }
    }
    st.perfil?.let { p ->
        HojaPerfil(p, onCerrar = vm::cerrarPerfil,
            onChat = { vm.cerrarPerfil(); chat(p.id, false, null, 0) },
            onPedir = { vm.cerrarPerfil(); chat(p.id, true, null, 0) },
            onProducto = { it2 -> vm.cerrarPerfil(); chat(p.id, true, it2.productoId, it2.minimoMayoreo.coerceAtLeast(1)) })
    }
}

@Composable
private fun Resumen(st: AnaquelUiState, vm: AnaquelViewModel, onAbrirChats: () -> Unit, arriba: androidx.compose.ui.unit.Dp) {
    val ini = st.inicio
    var q by remember { mutableStateOf(st.consultaRed) }
    var negociando by remember { mutableStateOf<ResultadoRed?>(null) }
    // Para abrir el chat y saltar a la pestaña Chats.
    val chat = { tienda: Long, pedir: Boolean, producto: Long?, cantidad: Long ->
        vm.chatCon(tienda, pedir, producto, cantidad); onAbrirChats()
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = arriba, bottom = NeuDimens.SpaceXxl * 2),
        verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg),
    ) {
        item(key = "cabeza") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(ini?.saludo?.takeIf { it.isNotBlank() } ?: st.tienda?.nombre.orEmpty(), Modifier.weight(1f), negritas = true, lineas = 2)
                NeumorphicIconButton(painterResource(Res.drawable.ic_refresh), "Actualizar", onClick = vm::cargarInicio,
                    size = NeuDimens.IconButtonSmallSize, iconSize = NeuDimens.IconSmallSize)
            }
        }
        item(key = "buscar") {
            NeumorphicInput(
                q, { q = it }, Modifier.fillMaxWidth(), placeholder = "¿Qué buscas? Leche, gorras, papel…",
                leadingIcon = { Icon(painterResource(Res.drawable.ic_search), null, tint = NeuColors.TextSecondary) },
                trailingIcon = if (q.isNotEmpty()) ({
                    NeumorphicIconButton(painterResource(Res.drawable.ic_close), "Borrar búsqueda", onClick = { q = ""; vm.buscarDesdeInicio("") },
                        size = NeuDimens.IconButtonSmallSize, iconSize = NeuDimens.IconSmallSize)
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.buscarDesdeInicio(q) }),
            )
        }
        // Resultados de la búsqueda arriba de todo.
        if (st.consultaRed.isNotBlank()) {
            val r = st.red
            item(key = "res-t") { Seccion("En la red: «${st.consultaRed}»") }
            when {
                r == null || st.ocupado == "red" -> item(key = "res-c") { Txt("Buscando…", secundario = true) }
                r.isEmpty() -> item(key = "res-v") {
                    Txt("Nadie lo tiene publicado todavía. Pídele al asesor (en Mi tienda) «publica que busco ${st.consultaRed}» y les avisamos a todas las tiendas.", secundario = true)
                }
                else -> items(r, key = { "res-" + it.clave }) { Box(Modifier.animateItem().entrada()) { TarjetaRed(it, { x -> negociando = x }, compacta = true) } }
            }
        }
        if (ini == null) {
            item(key = "cargando") { if (st.error != null) Txt(st.error, secundario = true) else TarjetasCargando("Revisando qué le conviene a tu tienda…", 4) }
            return@LazyColumn
        }
        if (ini.recomendados.isNotEmpty()) {
            item(key = "r-t") { Seccion("Tu socio te recomienda", "Salió de comparar tu tienda con toda la red") }
            items(ini.recomendados.take(6), key = { "r-" + it.clave }) { r ->
                Box(Modifier.animateItem().entrada()) {
                    TarjetaRecomendacion(
                        r, pedir = { t, p, n -> chat(t, true, p, n) }, negociar = { negociando = it },
                        escribir = { t -> chat(t, true, null, 0) },
                    )
                }
            }
        }
        if (ini.paraSurtir.isNotEmpty()) {
            item(key = "s-t") { Seccion("Se te está acabando", "Lo más barato de la red para surtirlo") }
            items(ini.paraSurtir, key = { "s-" + it.productoId }) { s -> Box(Modifier.animateItem().entrada()) { TarjetaSurtir(s, chat) } }
        }
        if (ini.proveedores.isNotEmpty()) {
            item(key = "p-t") { Seccion("Proveedores grandes", "Mayoristas de tu ciudad con lo que tú vendes") }
            item(key = "p-l") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg), contentPadding = PaddingValues(vertical = NeuDimens.SpaceSm)) {
                    items(ini.proveedores, key = { "p-" + it.id }) { p -> Box(Modifier.animateItem().entrada()) { TarjetaProveedor(p, chat) } }
                }
            }
        }
        if (ini.convienen.isNotEmpty()) {
            item(key = "c-t") { Seccion("Te conviene", "Ofertas de productos que manejas") }
            items(ini.convienen.take(8), key = { "c-" + it.clave }) { r -> Box(Modifier.animateItem().entrada()) { TarjetaOferta(r, chat, negociar = { negociando = it }) } }
        }
        if (ini.promociones.isNotEmpty()) {
            item(key = "pr-t") { Seccion("Promociones cerca") }
            items(ini.promociones.take(6), key = { "pr-" + it.clave }) { r -> Box(Modifier.animateItem().entrada()) { TarjetaRed(r, { negociando = it }, compacta = true) } }
        }
        if (ini.buscan.isNotEmpty()) {
            item(key = "b-t") { Seccion("Buscan lo que vendes", "Otras tiendas quieren comprarte") }
            items(ini.buscan.take(6), key = { "b-" + it.clave }) { r -> Box(Modifier.animateItem().entrada()) { TarjetaRed(r, { negociando = it }, compacta = true) } }
        }
        if (ini.tiendas.isNotEmpty()) {
            item(key = "t-t") { Seccion("Tiendas de tu zona") }
            items(ini.tiendas.take(12), key = { "t-" + it.id }) { t -> Box(Modifier.animateItem().entrada()) { TarjetaTienda(t, chat) } }
        }
        if (ini.paraSurtir.isEmpty() && ini.convienen.isEmpty() && ini.proveedores.isEmpty() && ini.recomendados.isEmpty()) {
            item(key = "vacio") { Txt("Todo en orden por ahora. Toma la foto de tus anaqueles en Mi tienda y aquí verás qué surtir y con quién.", secundario = true) }
        }
    }
    negociando?.let { r ->
        DialogoNegociar(r, onCerrar = { negociando = null }) { n, pc, msg -> negociando = null; vm.negociar(r, n, pc, msg) }
    }
}

private fun cantidadTxt(n: Long?, unidad: String) = if (n == null) "sin conteo" else "quedan $n $unidad"

@Composable
private fun TarjetaSurtir(s: SurtirInicio, chat: (Long, Boolean, Long?, Long) -> Unit) {
    val o = s.ofertas.firstOrNull()
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(s.nombre, Modifier.weight(1f), negritas = true, lineas = 2)
            Pastilla(
                if (s.stock == 0L) "Agotado" else cantidadTxt(s.stock, s.unidad),
                if (s.stock == 0L) NeuColors.LowStockContainer else NeuColors.ChipBackground,
                if (s.stock == 0L) NeuColors.OnLowStock else NeuColors.OnChip,
            )
        }
        Txt("Mínimo ${s.stockMin} · lo vendes a ${formatPrice(s.miPrecioC)}", secundario = true, chico = true)
        if (o == null) {
            Spacer(Modifier.height(NeuDimens.SpaceSm))
            Txt("Nadie de la red lo tiene en su vitrina. Pídeselo a tu proveedor de siempre en Mi tienda › Hoy.", secundario = true, chico = true)
            return@NeumorphicCard
        }
        Spacer(Modifier.height(NeuDimens.SpaceMd))
        val costo = if ((o.precioMayoreoC ?: 0) > 0) o.precioMayoreoC!! else o.precioC ?: 0
        Txt("${o.tienda?.nombre}", negritas = true, lineas = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm)) {
            Txt("${formatPrice(costo)} c/u" + ((o.minimoMayoreo ?: 0).takeIf { it > 0 }?.let { " desde $it" } ?: ""), chico = true)
            km(o.distanciaKm)?.let { Txt("· $it", secundario = true, chico = true) }
            o.gananciaPct?.takeIf { it > 0 }?.let { Pastilla("ganas $it%") }
        }
        Spacer(Modifier.height(NeuDimens.SpaceMd))
        val tienda = o.tienda ?: return@NeumorphicCard
        // Lo que falta para volver al mínimo, pero al menos el mínimo de mayoreo.
        val sugerido = maxOf((s.stockMin * 2 - (s.stock ?: 0)).coerceAtLeast(1), o.minimoMayoreo ?: 0)
        Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
            NeumorphicButton("Pedir", onClick = { chat(tienda.id, true, o.id, sugerido) }, modifier = Modifier.weight(1f))
            NeumorphicButton("Chat", onClick = { chat(tienda.id, false, null, 0) }, containerColor = NeuColors.Blue, modifier = Modifier.weight(1f))
        }
        if (s.ofertas.size > 1) Txt("+${s.ofertas.size - 1} opciones más en la red", secundario = true, chico = true, modifier = Modifier.padding(top = NeuDimens.SpaceSm))
    }
}

@Composable
private fun TarjetaProveedor(p: ProveedorGrande, chat: (Long, Boolean, Long?, Long) -> Unit) {
    NeumorphicCard(Modifier.width(280.dp)) {
        Txt(p.nombre.removeSuffix(" (demo)"), negritas = true, lineas = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm), verticalAlignment = Alignment.CenterVertically) {
            Txt("${p.productos} productos", secundario = true, chico = true)
            km(p.distanciaKm)?.let { Txt("· $it", secundario = true, chico = true) }
        }
        p.promocion?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(NeuDimens.SpaceSm))
            Txt(it, chico = true, negritas = true, color = NeuColors.Magenta, lineas = 2)
        }
        Spacer(Modifier.height(NeuDimens.SpaceSm))
        p.destacados.take(3).forEach { d ->
            val costo = if ((d.precioMayoreoC ?: 0) > 0) d.precioMayoreoC!! else d.precioC ?: 0
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Txt(d.titulo, Modifier.weight(1f), chico = true, lineas = 1)
                Txt(formatPrice(costo), chico = true, negritas = true)
            }
        }
        Spacer(Modifier.height(NeuDimens.SpaceMd))
        Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
            NeumorphicButton("Catálogo", onClick = { chat(p.id, true, null, 0) }, modifier = Modifier.weight(1f))
            NeumorphicIconButton(painterResource(Res.drawable.ic_chat), "Escribirle", onClick = { chat(p.id, false, null, 0) })
        }
    }
}

@Composable
private fun TarjetaOferta(r: ResultadoRed, chat: (Long, Boolean, Long?, Long) -> Unit, negociar: (ResultadoRed) -> Unit) {
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(r.titulo, Modifier.weight(1f), negritas = true, lineas = 2)
            r.gananciaPct?.takeIf { it > 0 }?.let { Pastilla("ganas $it%") }
        }
        r.motivo?.takeIf { it.isNotBlank() }?.let { Txt(it, secundario = true, chico = true) }
        val costo = if ((r.precioMayoreoC ?: 0) > 0) r.precioMayoreoC!! else r.precioC ?: 0
        Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceSm)) {
            Txt("${formatPrice(costo)} c/u" + ((r.minimoMayoreo ?: 0).takeIf { it > 0 }?.let { " desde $it" } ?: ""), chico = true, negritas = true)
            Txt(listOfNotNull(r.tienda?.nombre, km(r.distanciaKm)).joinToString(" · "), secundario = true, chico = true, lineas = 1)
        }
        val t = r.tienda ?: return@NeumorphicCard
        Spacer(Modifier.height(NeuDimens.SpaceMd))
        Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
            NeumorphicButton("Pedir", onClick = { chat(t.id, true, r.id, (r.minimoMayoreo ?: 1).coerceAtLeast(1)) }, modifier = Modifier.weight(1f))
            NeumorphicButton("Negociar", onClick = { negociar(r) }, containerColor = NeuColors.Blue, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TarjetaTienda(t: TiendaZona, chat: (Long, Boolean, Long?, Long) -> Unit) {
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(t.nombre, negritas = true, lineas = 1)
                Txt(listOfNotNull(t.giro.takeIf { it.isNotBlank() }, "${t.productos} en vitrina", km(t.distanciaKm)).joinToString(" · "), secundario = true, chico = true, lineas = 1)
                t.descripcion.takeIf { it.isNotBlank() }?.let { Txt(it, secundario = true, chico = true, lineas = 2) }
            }
            Spacer(Modifier.width(NeuDimens.SpaceMd))
            if (t.productos > 0) {
                NeumorphicButton("Catálogo", onClick = { chat(t.id, true, null, 0) })
                Spacer(Modifier.width(NeuDimens.SpaceSm))
            }
            NeumorphicIconButton(painterResource(Res.drawable.ic_chat), "Escribirle", onClick = { chat(t.id, false, null, 0) })
        }
    }
}
