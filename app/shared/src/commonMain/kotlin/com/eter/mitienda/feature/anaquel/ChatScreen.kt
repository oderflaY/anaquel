package com.eter.mitienda.feature.anaquel

import androidx.compose.foundation.border
import com.eter.mitienda.core.web.VistaWeb
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.lazy.LazyRow
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
        st.pago?.let { HojaPago(it, onCerrar = vm::cerrarPago) }
        st.pagado?.let { Pagado(it, onListo = vm::vistoPagado) }
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
    // Contraoferta: tarjeta y trato que se van a cambiar (con varios productos, primero se elige cuál).
    var contra by remember(c.id) { mutableStateOf<Pair<MensajeChat, TratoChat?>?>(null) }
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
                    "pedido", "oferta" -> TarjetaPedido(m, c.otra.nombre, st.ocupado == "pedido${m.id}") { accion ->
                        when (accion) {
                            "pagar" -> vm.pagar(m)
                            "contra" -> contra = m to m.tratos.singleOrNull { it.estado == "negociando" }
                            else -> vm.accionPedidoChat(m, accion)
                        }
                    }
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
    contra?.let { (m, t) ->
        if (t == null) ElegirProducto(m.tratos.filter { it.estado == "negociando" }, onCerrar = { contra = null }) { contra = m to it }
        else HojaNegociacion(
            Negociacion(
                titulo = t.titulo, tienda = c.otra.nombre, foto = t.foto, icono = iconoDe(t.titulo),
                precioLista = t.precioC, precioMayoreo = null, minimoMayoreo = null, disponible = null,
                cantidadInicial = t.cantidad, precioInicial = t.precioC, modo = "contra",
            ),
            onCerrar = { contra = null },
        ) { n, pc, msg -> contra = null; vm.contraoferta(m, t.id, n, pc, msg) }
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
                    if (mio) NeuColors.Magenta else Color.White,
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
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Miniatura(i.foto, i.nombre, 42)
                Spacer(Modifier.width(10.dp))
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

/**
 * Tarjeta de un pedido (varios productos) o de una oferta (un producto con la
 * propuesta de ese momento). Lo que se puede hacer sale como botones; el más
 * importante («Pagar») va grande y late para que no se pierda.
 */
@Composable
private fun TarjetaPedido(m: MensajeChat, otra: String, ocupado: Boolean, accion: (String) -> Unit) {
    val compro = m.rol == "compro"
    val mia = m.de == "yo"
    val oferta = m.tipo == "oferta"
    val vieja = oferta && !m.vigente
    val otraCorta = otra.removeSuffix(" (demo)")
    val titulo = when {
        !oferta -> if (compro) "Tu pedido" else "Te hicieron un pedido"
        mia -> if (compro) "Tu oferta" else "Le ofreciste"
        else -> if (compro) "$otraCorta te ofrece" else "$otraCorta te propone"
    }
    val entrada = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrada.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    Column(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = entrada.value.coerceIn(0f, 1f) * if (vieja) 0.55f else 1f
                translationY = (1f - entrada.value) * 60f
                scaleX = 0.94f + 0.06f * entrada.value; scaleY = scaleX
            }
            .shadow(if (vieja) 0.dp else 10.dp, RoundedCornerShape(22.dp), spotColor = VerdeMarca.copy(alpha = 0.4f))
            .background(Color.White, RoundedCornerShape(22.dp))
            .border(if (m.puedo.isNotEmpty()) 1.5.dp else 1.dp, if (m.puedo.isNotEmpty()) VerdeMarca.copy(alpha = 0.5f) else NeuColors.Hairline, RoundedCornerShape(22.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(titulo.uppercase(), Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp,
                color = VerdeMarca, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                vieja -> Pastilla("Propuesta anterior")
                m.pagado && m.estado != "entregado" -> Pastilla("✓ Pagado", VerdeMarca, Color.White)
                else -> Pastilla(
                    estadoPedido(m.estado),
                    when (m.estado) { "cancelado" -> NeuColors.LowStockContainer; "entregado" -> VerdeMarca; else -> NeuColors.ChipBackground },
                    when (m.estado) { "cancelado" -> NeuColors.OnLowStock; "entregado" -> Color.White; else -> NeuColors.OnChip },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (oferta && m.tratos.size == 1) {
            val t = m.tratos.first()
            val cant = if (m.cantidad > 0) m.cantidad else t.cantidad
            val precio = if (m.precioC > 0) m.precioC else t.precioC
            Row(verticalAlignment = Alignment.CenterVertically) {
                Miniatura(t.foto, t.titulo, 58)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.titulo, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 2, lineHeight = 19.sp)
                    Text("${miles(cant)} × ${formatPrice(precio)}", fontSize = 14.sp, color = NeuColors.TextSecondary)
                }
                Text(formatPrice(cant * precio), fontSize = 20.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
            }
        } else {
            m.tratos.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Miniatura(t.foto, t.titulo, 34)
                    Spacer(Modifier.width(8.dp))
                    Txt("${t.cantidad} × ${t.titulo}", Modifier.weight(1f), chico = true, lineas = 2, secundario = t.estado == "cancelado")
                    Txt(formatPrice(t.cantidad * t.precioC), chico = true)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = NeuDimens.SpaceSm)) {
                Txt("Total", Modifier.weight(1f), negritas = true)
                Txt(formatPrice(m.total), negritas = true)
            }
        }
        m.texto.takeIf { it.isNotBlank() && (oferta || !it.startsWith("Pedido de")) }?.let {
            Text("«$it»", fontSize = 13.sp, color = NeuColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        }
        // Qué sigue, en palabras, para que nadie se quede esperando sin saber.
        val sigue = when {
            vieja -> null
            "pagar" in m.puedo && m.estado == "negociando" -> "Te conviene: págala ya y queda cerrada, o haz una contraoferta."
            m.estado == "negociando" && "aceptar" in m.puedo -> "Te toca: acéptala o contraoferta."
            m.estado == "negociando" -> "Esperando a que $otraCorta responda…"
            m.estado == "aceptado" && compro && !m.pagado -> "Aceptado. Págalo en línea o al recibir."
            m.estado == "aceptado" && compro -> "Pagado. Te avisamos cuando lo entreguen."
            m.estado == "aceptado" && m.pagado -> "Ya te pagaron en línea. Entrégalo y márcalo: tu inventario baja solo."
            m.estado == "aceptado" -> "Cuando lo entregues, márcalo: tu inventario baja solo."
            m.estado == "entregado" && compro -> "Ya entró a tu inventario."
            else -> null
        }
        sigue?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NeuColors.TextPrimary, modifier = Modifier.padding(top = 10.dp)) }
        if ("pagar" in m.puedo) {
            Spacer(Modifier.height(12.dp))
            BotonVerde("Pagar ${formatPrice(m.porPagarC)}", Icons.Rounded.Check, Modifier.latido(fuerza = 0.025f), habilitado = !ocupado) { accion("pagar") }
        }
        val secundarios = m.puedo.filter { it != "pagar" }
        if (secundarios.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                secundarios.forEach { a ->
                    val (texto, fuerte) = when (a) {
                        "aceptar" -> (if ("pagar" in m.puedo) "Al recibir" else "Aceptar") to ("pagar" !in m.puedo)
                        "contra" -> "Contraoferta" to false
                        "entregar" -> "Entregado" to true
                        else -> "Cancelar" to false
                    }
                    BotonChico(texto, fuerte, !ocupado, Modifier.weight(if (a == "cancelar") 0.8f else 1f)) { accion(a) }
                }
            }
        }
    }
}

@Composable
private fun BotonChico(texto: String, fuerte: Boolean, habilitado: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp))
            .background(if (fuerte) VerdeMarca else VerdeMarca.copy(alpha = 0.09f))
            .clickable(enabled = habilitado, onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (fuerte) Color.White else VerdeMarca, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Con varios productos en el pedido: ¿cuál quieres contraofertar? */
@Composable
private fun ElegirProducto(tratos: List<TratoChat>, onCerrar: () -> Unit, onElegir: (TratoChat) -> Unit) {
    HojaAnaquel("¿Qué producto?", "Contraoferta", onCerrar = onCerrar) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tratos.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White)
                        .border(1.dp, NeuColors.Hairline, RoundedCornerShape(16.dp)).clickable { onElegir(t) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Miniatura(t.foto, t.titulo, 44)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.titulo, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.cantidad} × ${formatPrice(t.precioC)}", fontSize = 12.sp, color = NeuColors.TextSecondary)
                    }
                }
            }
        }
    }
}

// ---------------- Pago ----------------

/** Página de pago (Stripe Checkout o la de prueba) dentro de la app; al volver se cierra sola. */
@Composable
private fun HojaPago(c: Cobro, onCerrar: () -> Unit) {
    Dialog(onDismissRequest = onCerrar, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        val p = remember { Animatable(0f) }
        LaunchedEffect(Unit) { p.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
        Column(
            Modifier.fillMaxSize().graphicsLayer { translationY = (1f - p.value) * 900f }
                .background(NeuColors.Background).systemBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (c.modo == "stripe") "PAGO SEGURO · STRIPE" else "PAGO DE PRUEBA", fontSize = 11.sp, fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp, color = VerdeMarca)
                    Text(formatPrice(c.montoC), fontSize = 22.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
                }
                BotonCircular(Icons.Rounded.Close, "Cerrar", 40) { onCerrar() }
            }
            VistaWeb(c.url, Modifier.fillMaxWidth().weight(1f)) { u ->
                if (u.startsWith("anaquel://") || "/api/cobros/${c.id}/vuelta" in u) { onCerrar(); true } else false
            }
        }
    }
}

/** «¡Pagado!»: palomita que rebota y se va sola. */
@Composable
private fun Pagado(c: Cobro, onListo: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(c.id) {
        p.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
        delay(1600)
        onListo()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f * p.value.coerceIn(0f, 1f))).clickable { onListo() }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.graphicsLayer { scaleX = p.value; scaleY = p.value }.shadow(24.dp, RoundedCornerShape(28.dp))
                .background(Color.White, RoundedCornerShape(28.dp)).padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(86.dp).background(VerdeMarca, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(54.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("¡Pagado!", fontSize = 26.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
            Text(formatPrice(c.montoC), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VerdeMarca)
            Text("Ya le avisamos a la tienda", fontSize = 13.sp, color = NeuColors.TextSecondary)
        }
    }
}

// ---------------- Diálogos ----------------

@Composable
private fun DialogoPedido(p: PedidoEnCurso, ocupado: Boolean, vm: AnaquelViewModel) {
    var nota by remember(p.chatId, p.catalogo) { mutableStateOf("") }
    HojaAnaquel("Pedir a ${p.tienda.removeSuffix(" (demo)")}", "Nuevo pedido", onCerrar = vm::cancelarPedido) {
        Text("Pon cuántas quieres de cada uno. El mayoreo se aplica solo al llegar al mínimo.", fontSize = 13.sp, color = NeuColors.TextSecondary)
        Spacer(Modifier.height(12.dp))
        var busca by remember(p.chatId) { mutableStateOf("") }
        var cat by remember(p.chatId) { mutableStateOf<String?>(null) }
        val categorias = remember(p.items) { p.items.map { it.categoria }.filter { it.isNotBlank() }.distinct() }
        if (p.items.size > 8) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp).background(Color.White, RoundedCornerShape(14.dp))
                    .border(1.dp, NeuColors.Hairline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = NeuColors.TextSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    busca, { busca = it }, Modifier.weight(1f), singleLine = true,
                    textStyle = TextStyle(fontSize = 15.sp, color = NeuColors.TextPrimary), cursorBrush = SolidColor(VerdeMarca),
                    decorationBox = { campo -> if (busca.isEmpty()) Text("Buscar en ${p.items.size} productos", color = NeuColors.TextSecondary, fontSize = 15.sp); campo() },
                )
            }
            if (categorias.size > 1) {
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { ChipVerde("Todo", cat == null) { cat = null } }
                    items(categorias) { c -> ChipVerde(c, cat == c) { cat = if (cat == c) null else c } }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        val q = busca.trim().lowercase()
        val visibles = p.items.filter { (cat == null || it.categoria == cat) && (q.isEmpty() || it.nombre.lowercase().contains(q)) }
        LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (visibles.isEmpty()) item { Text("Nada con «$busca».", color = NeuColors.TextSecondary, fontSize = 14.sp) }
            items(visibles, key = { it.productoId }) { i ->
                val n = p.cantidades[i.productoId] ?: 0
                val elegido = n > 0
                Column(
                    Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp))
                        .border(if (elegido) 1.5.dp else 1.dp, if (elegido) VerdeMarca.copy(alpha = 0.6f) else NeuColors.Hairline, RoundedCornerShape(18.dp))
                        .padding(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Miniatura(i.foto, i.nombre, 56)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(i.nombre, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 2, lineHeight = 17.sp)
                            Text("${formatPrice(i.precioPara(n.coerceAtLeast(1)))} c/u" + (i.disponible?.let { " · hay $it" } ?: ""),
                                fontSize = 12.sp, color = NeuColors.TextSecondary)
                        }
                        Contador(n, i.disponible, onCambio = { vm.cambiarCantidadPedido(i.productoId, it) })
                    }
                    if (i.minimoMayoreo > 0 && i.precioMayoreoC > 0 && n in 1 until i.minimoMayoreo) {
                        Text("Con ${i.minimoMayoreo - n} más te sale a ${formatPrice(i.precioMayoreoC)} · tocar para subir",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VerdeMarca,
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().background(VerdeMarca.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                                .clickable { vm.cambiarCantidadPedido(i.productoId, i.minimoMayoreo) }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
            item {
                BasicTextField(
                    nota, { nota = it }, Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Color.White, RoundedCornerShape(14.dp))
                        .border(1.dp, NeuColors.Hairline, RoundedCornerShape(14.dp)).padding(14.dp),
                    textStyle = TextStyle(fontSize = 15.sp, color = NeuColors.TextPrimary), cursorBrush = SolidColor(VerdeMarca),
                    decorationBox = { campo -> if (nota.isEmpty()) Text("Nota: cuándo pasas, cómo pagas…", color = NeuColors.TextSecondary, fontSize = 15.sp); campo() },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("${p.piezas} piezas", fontSize = 12.sp, color = NeuColors.TextSecondary)
                Text(formatPrice(p.total), fontSize = 26.sp, fontWeight = FontWeight.Black, color = NeuColors.TextPrimary)
            }
        }
        Spacer(Modifier.height(10.dp))
        BotonVerde("Enviar pedido", Icons.AutoMirrored.Rounded.Send, habilitado = p.piezas > 0 && !ocupado) { vm.enviarPedido(nota) }
    }
}

@Composable
private fun Contador(n: Long, tope: Long?, onCambio: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BotonCircular(Icons.Rounded.Remove, "Menos", 36) { if (n > 0) onCambio(n - 1) }
        Text("$n", Modifier.width(40.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Black, fontSize = 17.sp,
            color = if (n > 0) NeuColors.TextPrimary else NeuColors.TextSecondary)
        BotonCircular(Icons.Rounded.Add, "Más", 36) { if (tope == null || n < tope) onCambio(n + 1) }
    }
}

@Composable
private fun DialogoMiCatalogo(items: List<ItemCatalogo>?, onCerrar: () -> Unit, onEnviar: (List<Long>?) -> Unit) {
    var sel by remember { mutableStateOf(setOf<Long>()) }
    HojaAnaquel("Mandar mi catálogo", "Tu vitrina", onCerrar = onCerrar) {
        when {
            items == null -> Text("Cargando tu vitrina…", color = NeuColors.TextSecondary)
            items.isEmpty() -> Text("No tienes productos publicados con existencia. Publícalos en Mi tienda › Vitrina para poder mandarlos.", color = NeuColors.TextSecondary)
            else -> {
                Text("Elige cuáles mandar, o manda todos.", fontSize = 13.sp, color = NeuColors.TextSecondary)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.productoId }) { i ->
                        val on = i.productoId in sel
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White)
                                .border(if (on) 1.5.dp else 1.dp, if (on) VerdeMarca.copy(alpha = 0.6f) else NeuColors.Hairline, RoundedCornerShape(18.dp))
                                .clickable { sel = if (on) sel - i.productoId else sel + i.productoId }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Miniatura(i.foto, i.nombre, 48)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(i.nombre, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(precioTxt(i) + (i.disponible?.let { " · hay $it" } ?: ""), fontSize = 12.sp, color = NeuColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(
                                Modifier.size(26.dp).background(if (on) VerdeMarca else Color.Transparent, CircleShape)
                                    .border(2.dp, if (on) VerdeMarca else Color(0x33000000), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                BotonVerde(if (sel.isEmpty()) "Mandar todos (${items.size})" else "Mandar ${sel.size}", Icons.AutoMirrored.Rounded.Send) {
                    onEnviar(sel.toList().ifEmpty { null })
                }
            }
        }
    }
}
