package com.eter.mitienda.feature.anaquel

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.components.NeumorphicInput
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.util.formatPrice
import kotlinx.coroutines.delay
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_add
import mitienda.shared.generated.resources.ic_arrow_back
import mitienda.shared.generated.resources.ic_phone
import mitienda.shared.generated.resources.ic_refresh
import mitienda.shared.generated.resources.ic_remove
import mitienda.shared.generated.resources.ic_send
import mitienda.shared.generated.resources.ic_store
import org.jetbrains.compose.resources.painterResource

// Chats con proveedores y tiendas: mensajes, mini catálogos y pedidos con cantidades.

@Composable
fun ChatsRoute(vm: AnaquelViewModel) {
    MarcoAnaquel(vm) { st ->
        val c = st.chat
        if (c == null) {
            LaunchedEffect(st.tienda?.id) { vm.cargarChats() }
            ListaChats(st, vm)
        } else {
            // Mientras está abierto, se refresca solo para ver lo que contesta la otra tienda.
            LaunchedEffect(c.id) { while (true) { delay(5_000); vm.refrescarChat() } }
            Conversacion(c, st, vm)
        }
        st.pidiendo?.let { DialogoPedido(it, st.ocupado == "pedido", vm) }
    }
}

// ---------------- Lista ----------------

private fun horaCorta(ms: Long): String {
    if (ms <= 0) return ""
    val min = (ahoraMs() - ms) / 60_000
    return when {
        min < 1 -> "ahora"
        min < 60 -> "hace $min min"
        min < 60 * 24 -> "hace ${min / 60} h"
        else -> "hace ${min / (60 * 24)} d"
    }
}

private fun ahoraMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

@Composable
private fun ListaChats(st: AnaquelUiState, vm: AnaquelViewModel) {
    val lista = st.chats
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = NeuDimens.SpaceLg, bottom = NeuDimens.SpaceXxl * 2),
        verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg),
    ) {
        item(key = "cab") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Chats", style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
                    Txt("Con tus proveedores y las tiendas de la red", secundario = true)
                }
                NeumorphicIconButton(painterResource(Res.drawable.ic_refresh), "Actualizar", onClick = vm::cargarChats)
            }
        }
        when {
            lista == null -> item(key = "carg") { if (st.error != null && st.ocupado != "chats") Txt(st.error, secundario = true) else TarjetasCargando("Abriendo tus chats…") }
            lista.isEmpty() -> item(key = "vacio") {
                NeumorphicCard(Modifier.fillMaxWidth()) {
                    Txt("Todavía no platicas con nadie", negritas = true)
                    Spacer(Modifier.height(NeuDimens.SpaceSm))
                    Txt("En Inicio toca «Chat» o «Catálogo» en un proveedor o una tienda de tu zona para empezar. Ahí les puedes pedir con cantidades y mandar tu propio catálogo.", secundario = true, chico = true)
                }
            }
            else -> itemsIndexed(lista, key = { _, c -> "c-" + c.id }) { i, c -> Box(Modifier.animateItem().entrada(i)) { FilaChat(c) { vm.abrirChat(c.id) } } }
        }
    }
}

@Composable
private fun Avatar(nombre: String, tam: Int = 44) {
    val ini = nombre.removeSuffix(" (demo)").split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2)
        .joinToString("") { it.first().uppercase() }
    Box(Modifier.size(tam.dp).background(NeuColors.ChipBackground, NeuShapes.Pill), contentAlignment = Alignment.Center) {
        Text(ini.ifEmpty { "?" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = NeuColors.OnChip)
    }
}

@Composable
private fun FilaChat(c: Chat, onClick: () -> Unit) {
    NeumorphicCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.otra.nombre)
            Spacer(Modifier.width(NeuDimens.SpaceMd))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(c.otra.nombre.removeSuffix(" (demo)"), Modifier.weight(1f), negritas = true, lineas = 1)
                    Txt(horaCorta(c.actualizado), secundario = true, chico = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt((if (c.ultimoMio) "Tú: " else "") + c.ultimo.ifBlank { "Sin mensajes" }, Modifier.weight(1f),
                        secundario = c.sinLeer == 0, negritas = c.sinLeer > 0, chico = true, lineas = 1)
                    if (c.pendientes > 0) { Spacer(Modifier.width(NeuDimens.SpaceSm)); Pastilla("Te toca") }
                    if (c.sinLeer > 0) { Spacer(Modifier.width(NeuDimens.SpaceSm)); Pastilla("${c.sinLeer}", NeuColors.Magenta, NeuColors.OnAccent) }
                }
            }
        }
    }
}

// ---------------- Conversación ----------------

@Composable
private fun Conversacion(c: Chat, st: AnaquelUiState, vm: AnaquelViewModel) {
    var texto by remember(c.id) { mutableStateOf("") }
    var mandarCatalogo by remember(c.id) { mutableStateOf(false) }
    val lista = rememberLazyListState()
    // Siempre al último mensaje cuando llega uno nuevo.
    LaunchedEffect(c.mensajes.size) { if (c.mensajes.isNotEmpty()) lista.animateScrollToItem(c.mensajes.size - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = NeuDimens.SpaceLg, vertical = NeuDimens.SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeumorphicIconButton(painterResource(Res.drawable.ic_arrow_back), "Volver a chats", onClick = vm::cerrarChat,
                size = NeuDimens.IconButtonSmallSize, iconSize = NeuDimens.IconSmallSize)
            Spacer(Modifier.width(NeuDimens.SpaceMd))
            Avatar(c.otra.nombre, 38)
            Spacer(Modifier.width(NeuDimens.SpaceSm))
            Column(Modifier.weight(1f)) {
                Txt(c.otra.nombre.removeSuffix(" (demo)"), negritas = true, lineas = 1)
                Txt(listOfNotNull(c.otra.giro.takeIf { it.isNotBlank() }, km(c.distanciaKm)).joinToString(" · "), secundario = true, chico = true, lineas = 1)
            }
            c.whatsapp?.takeIf { it.isNotBlank() }?.let { w ->
                NeumorphicIconButton(painterResource(Res.drawable.ic_phone), "WhatsApp", onClick = { vm.abrirWhatsApp(w) },
                    size = NeuDimens.IconButtonSmallSize, iconSize = NeuDimens.IconSmallSize)
            }
        }
        // Acciones rápidas: lo que más se hace en un chat de proveedor.
        Row(Modifier.fillMaxWidth().padding(horizontal = NeuDimens.SpaceLg), horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
            NeumorphicButton("Ver su catálogo", onClick = { vm.chatCon(c.otra.id, pedir = true) }, modifier = Modifier.weight(1f),
                elevation = NeuDimens.ElevationSmall)
            NeumorphicButton("Mandar el mío", onClick = { vm.cargarMiCatalogo(); mandarCatalogo = true }, containerColor = NeuColors.Blue,
                modifier = Modifier.weight(1f), elevation = NeuDimens.ElevationSmall)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = lista,
            contentPadding = PaddingValues(horizontal = NeuDimens.SpaceLg, vertical = NeuDimens.SpaceLg),
            verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd),
        ) {
            if (c.mensajes.isEmpty()) item(key = "vacio") {
                Txt("Escríbele a ${c.otra.nombre.removeSuffix(" (demo)")}: pregunta precios, pide su catálogo o mándale el tuyo.",
                    secundario = true, modifier = Modifier.fillMaxWidth().padding(top = NeuDimens.SpaceXl))
            }
            items(c.mensajes, key = { "m-" + it.id }) { m ->
                Box(Modifier.animateItem()) { when (m.tipo) {
                    "catalogo" -> TarjetaCatalogo(m, onPedir = { vm.pedirDeCatalogo(m) })
                    "pedido" -> TarjetaPedido(m, st.ocupado == "pedido${m.id}") { vm.accionPedidoChat(m, it) }
                    "sistema" -> Txt(m.texto, secundario = true, chico = true, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                    else -> Burbuja(m)
                } }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = NeuDimens.SpaceLg, vertical = NeuDimens.SpaceMd),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                NeumorphicInput(texto, { texto = it }, Modifier.fillMaxWidth().heightIn(max = 140.dp), placeholder = "Escribe un mensaje…", singleLine = false)
            }
            Spacer(Modifier.width(NeuDimens.SpaceMd))
            NeumorphicIconButton(
                painterResource(Res.drawable.ic_send), "Enviar",
                onClick = { if (texto.isNotBlank() && st.ocupado != "enviar") { vm.escribirChat(texto); texto = "" } },
                containerColor = if (texto.isBlank()) NeuColors.Background else NeuColors.Magenta,
            )
        }
    }
    if (mandarCatalogo) DialogoMiCatalogo(st.miCatalogo, onCerrar = { mandarCatalogo = false }) { sel ->
        mandarCatalogo = false; vm.mandarCatalogo(sel)
    }
}

@Composable
private fun Burbuja(m: MensajeChat) {
    val mio = m.de == "yo"
    // Cada burbuja entra deslizándose desde su lado, con un pequeño rebote.
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mio) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .graphicsLayer {
                    translationX = (1f - p.value) * (if (mio) 120f else -120f)
                    alpha = p.value.coerceIn(0f, 1f); scaleX = 0.85f + 0.15f * p.value; scaleY = scaleX
                    transformOrigin = TransformOrigin(if (mio) 1f else 0f, 1f)
                }
                .background(
                    if (mio) NeuColors.Magenta else NeuColors.ChipBackground,
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mio) 18.dp else 4.dp, bottomEnd = if (mio) 4.dp else 18.dp),
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(m.texto, style = MaterialTheme.typography.bodyMedium, color = if (mio) NeuColors.OnAccent else NeuColors.TextPrimary)
            Text(horaCorta(m.creado), style = MaterialTheme.typography.labelSmall,
                color = if (mio) NeuColors.OnAccent.copy(alpha = 0.75f) else NeuColors.TextSecondary,
                modifier = Modifier.align(Alignment.End))
        }
    }
}

private fun precioTxt(i: ItemCatalogo): String =
    formatPrice(i.precioC) + if (i.precioMayoreoC > 0 && i.minimoMayoreo > 0) " · ${formatPrice(i.precioMayoreoC)} desde ${i.minimoMayoreo}" else ""

@Composable
private fun TarjetaCatalogo(m: MensajeChat, onPedir: () -> Unit) {
    val mio = m.de == "yo"
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_store), null, Modifier.size(18.dp).padding(end = 2.dp), tint = NeuColors.Magenta)
            Spacer(Modifier.width(6.dp))
            Txt(if (mio) "Mandaste tu catálogo" else "Te mandó su catálogo", Modifier.weight(1f), negritas = true)
            Pastilla("${m.items.size} productos")
        }
        m.texto.takeIf { it.isNotBlank() }?.let { Txt(it, secundario = true, chico = true) }
        Spacer(Modifier.height(NeuDimens.SpaceSm))
        m.items.take(6).forEach { i ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(i.nombre, chico = true, negritas = true, lineas = 1, secundario = !i.sigue)
                    Txt(if (!i.sigue) "Ya no está en su vitrina" else precioTxt(i) + " /${i.unidad}", chico = true, secundario = true, lineas = 1)
                }
                i.disponible?.let { Txt("hay $it", secundario = true, chico = true) }
            }
        }
        if (m.items.size > 6) Txt("y ${m.items.size - 6} más…", secundario = true, chico = true)
        if (!mio && m.items.any { it.sigue }) {
            Spacer(Modifier.height(NeuDimens.SpaceMd))
            NeumorphicButton("Pedir de este catálogo", onClick = onPedir, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun estadoPedido(e: String) = when (e) {
    "negociando" -> "Por aceptar"; "aceptado" -> "Aceptado"; "entregado" -> "Entregado"
    "cancelado" -> "Cancelado"; "mixto" -> "En proceso"; else -> e
}

@Composable
private fun TarjetaPedido(m: MensajeChat, ocupado: Boolean, accion: (String) -> Unit) {
    val compro = m.rol == "compro"
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(if (compro) "Tu pedido" else "Te hicieron un pedido", Modifier.weight(1f), negritas = true)
            Pastilla(
                estadoPedido(m.estado),
                when (m.estado) { "cancelado" -> NeuColors.LowStockContainer; "entregado" -> NeuColors.Magenta; else -> NeuColors.ChipBackground },
                when (m.estado) { "cancelado" -> NeuColors.OnLowStock; "entregado" -> NeuColors.OnAccent; else -> NeuColors.OnChip },
            )
        }
        m.texto.takeIf { it.isNotBlank() }?.let { Txt("«$it»", secundario = true, chico = true) }
        Spacer(Modifier.height(NeuDimens.SpaceSm))
        m.tratos.forEach { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Txt("${t.cantidad} × ${t.titulo}", Modifier.weight(1f), chico = true, lineas = 2,
                    secundario = t.estado == "cancelado")
                Txt(formatPrice(t.cantidad * t.precioC), chico = true)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = NeuDimens.SpaceSm)) {
            Txt("Total", Modifier.weight(1f), negritas = true)
            Txt(formatPrice(m.total), negritas = true)
        }
        // Qué sigue, en palabras, para que nadie se quede esperando sin saber.
        val sigue = when {
            m.estado == "negociando" && !compro -> "Revisa que tengas todo y acéptalo."
            m.estado == "negociando" -> "Esperando a que lo acepten."
            m.estado == "aceptado" && !compro -> "Cuando lo entregues, márcalo: tu inventario baja solo."
            m.estado == "aceptado" -> "Aceptado: te lo llevan o pasas por él."
            m.estado == "entregado" && compro -> "Ya entró a tu inventario."
            else -> null
        }
        sigue?.let { Txt(it, secundario = true, chico = true, modifier = Modifier.padding(top = NeuDimens.SpaceSm)) }
        if (m.puedo.isNotEmpty()) {
            Spacer(Modifier.height(NeuDimens.SpaceMd))
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
                if ("aceptar" in m.puedo) NeumorphicButton("Aceptar", onClick = { accion("aceptar") }, enabled = !ocupado, modifier = Modifier.weight(1f))
                if ("entregar" in m.puedo) NeumorphicButton("Entregado", onClick = { accion("entregar") }, enabled = !ocupado, modifier = Modifier.weight(1f))
                if ("cancelar" in m.puedo) NeumorphicButton("Cancelar", onClick = { accion("cancelar") }, enabled = !ocupado,
                    containerColor = NeuColors.Background, modifier = Modifier.weight(1f))
            }
        }
    }
}

// ---------------- Diálogos ----------------

@Composable
private fun DialogoPedido(p: PedidoEnCurso, ocupado: Boolean, vm: AnaquelViewModel) {
    var nota by remember(p.chatId, p.catalogo) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = vm::cancelarPedido,
        title = { Text("Pedir a ${p.tienda.removeSuffix(" (demo)")}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
                item { Txt("Pon cuántas quieres de cada uno. El precio de mayoreo se aplica solo al llegar al mínimo.", secundario = true, chico = true) }
                items(p.items, key = { it.productoId }) { i ->
                    val n = p.cantidades[i.productoId] ?: 0
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Txt(i.nombre, chico = true, negritas = true, lineas = 2)
                                Txt("${formatPrice(i.precioPara(n.coerceAtLeast(1)))} c/u" + (i.disponible?.let { " · hay $it" } ?: ""), chico = true, secundario = true)
                            }
                            Contador(n, i.disponible, onCambio = { vm.cambiarCantidadPedido(i.productoId, it) })
                        }
                        if (i.minimoMayoreo > 0 && i.precioMayoreoC > 0 && n in 1 until i.minimoMayoreo) {
                            Txt("Con ${i.minimoMayoreo - n} más te sale a ${formatPrice(i.precioMayoreoC)}", chico = true, color = NeuColors.Magenta,
                                modifier = Modifier.clickable { vm.cambiarCantidadPedido(i.productoId, i.minimoMayoreo) })
                        }
                    }
                }
                item { NeumorphicInput(nota, { nota = it }, Modifier.fillMaxWidth(), placeholder = "Nota (opcional): cuándo pasas, cómo pagas…", singleLine = false) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.enviarPedido(nota) }, enabled = p.piezas > 0 && !ocupado) {
                Text(if (p.piezas > 0) "Enviar pedido · ${formatPrice(p.total)}" else "Enviar pedido", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = vm::cancelarPedido) { Text("Cancelar") } },
        containerColor = NeuColors.Background,
    )
}

@Composable
private fun Contador(n: Long, tope: Long?, onCambio: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeumorphicIconButton(painterResource(Res.drawable.ic_remove), "Menos", onClick = { onCambio(n - 1) },
            size = 34.dp, iconSize = 16.dp, elevation = NeuDimens.ElevationSmall)
        Text("$n", Modifier.width(40.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
            color = if (n > 0) NeuColors.TextPrimary else NeuColors.TextSecondary)
        NeumorphicIconButton(painterResource(Res.drawable.ic_add), "Más", onClick = { if (tope == null || n < tope) onCambio(n + 1) },
            size = 34.dp, iconSize = 16.dp, elevation = NeuDimens.ElevationSmall)
    }
}

@Composable
private fun DialogoMiCatalogo(items: List<ItemCatalogo>?, onCerrar: () -> Unit, onEnviar: (List<Long>?) -> Unit) {
    var sel by remember { mutableStateOf(setOf<Long>()) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Mandar mi catálogo") },
        text = {
            when {
                items == null -> Txt("Cargando tu vitrina…", secundario = true)
                items.isEmpty() -> Txt("No tienes productos publicados con existencia. Publícalos en tu inventario (Mi tienda › Vitrina) para poder mandarlos.", secundario = true)
                else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item { Txt("Elige cuáles mandar, o manda todos.", secundario = true, chico = true) }
                    items(items, key = { it.productoId }) { i ->
                        Row(
                            Modifier.fillMaxWidth().clickable { sel = if (i.productoId in sel) sel - i.productoId else sel + i.productoId },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(i.productoId in sel, null, colors = CheckboxDefaults.colors(checkedColor = NeuColors.Magenta))
                            Column(Modifier.weight(1f)) {
                                Txt(i.nombre, chico = true, negritas = true, lineas = 1)
                                Txt(precioTxt(i) + (i.disponible?.let { " · hay $it" } ?: ""), chico = true, secundario = true, lineas = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!items.isNullOrEmpty()) TextButton(onClick = { onEnviar(sel.toList().ifEmpty { null }) }) {
                Text(if (sel.isEmpty()) "Mandar todos (${items.size})" else "Mandar ${sel.size}", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cerrar") } },
        containerColor = NeuColors.Background,
    )
}
