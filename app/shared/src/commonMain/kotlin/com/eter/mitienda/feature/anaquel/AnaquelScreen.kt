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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicChip
import com.eter.mitienda.core.designsystem.components.NeumorphicDropdown
import com.eter.mitienda.core.designsystem.components.NeumorphicFab
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.components.NeumorphicInput
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.image.ImagePickResult
import com.eter.mitienda.core.image.rememberImagePickerLauncher
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_add
import mitienda.shared.generated.resources.ic_remove
import mitienda.shared.generated.resources.ic_upload
import org.jetbrains.compose.resources.painterResource

/** Para qué se pidió la foto a la cámara. */
private enum class Proposito { CREAR, HOY, REFERENCIA }

@Composable
fun AnaquelRoute(viewModel: AnaquelViewModel) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val uri = LocalUriHandler.current
    var proposito by remember { mutableStateOf(Proposito.HOY) }
    var nombreNuevo by remember { mutableStateOf("") }

    val camara = rememberImagePickerLauncher { r ->
        when (r) {
            is ImagePickResult.Success -> when (proposito) {
                Proposito.CREAR -> viewModel.crearAnaquel(nombreNuevo, r.tempPath)
                Proposito.HOY -> viewModel.fotoDelDia(r.tempPath)
                Proposito.REFERENCIA -> viewModel.nuevaReferencia(r.tempPath)
            }
            is ImagePickResult.Error -> viewModel.refrescar()
            ImagePickResult.Cancelled -> Unit
        }
    }
    val tomarFoto = { p: Proposito -> proposito = p; camara.takePhoto() }

    LaunchedEffect(viewModel.mensaje) {
        viewModel.mensaje?.let { snackbar.showSnackbar(it, withDismissAction = true); viewModel.onMensajeMostrado() }
    }
    LaunchedEffect(viewModel.abrirEnlace) {
        viewModel.abrirEnlace?.let { runCatching { uri.openUri(it) }; viewModel.onEnlaceAbierto() }
    }

    Box(Modifier.fillMaxSize().background(NeuColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val abierto = st.abierto
        when {
            st.revisandoSesion -> Texto("Cargando…", Modifier.align(Alignment.Center))
            st.tienda == null -> Entrar(st, viewModel)
            abierto != null -> DetalleAnaquel(st, abierto, viewModel, tomarFoto)
            else -> Principal(st, viewModel, onNuevo = { n -> nombreNuevo = n; tomarFoto(Proposito.CREAR) })
        }
        if (camara.isProcessing || st.ocupado == "foto" || st.ocupado == "crear") {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                NeumorphicCard { Text(if (st.ocupado == "foto") "Revisando tu anaquel…" else "Procesando foto…", color = NeuColors.TextPrimary) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(NeuDimens.ScreenPadding)) {
            Snackbar(it, shape = NeuShapes.Large, containerColor = NeuColors.TextPrimary, contentColor = NeuColors.Background)
        }
    }
}

@Composable
private fun Texto(t: String, modifier: Modifier = Modifier, secundario: Boolean = true) = Text(
    t, style = MaterialTheme.typography.bodyMedium, color = if (secundario) NeuColors.TextSecondary else NeuColors.TextPrimary, modifier = modifier,
)

@Composable
private fun Titulo(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)

// ---------------- Entrar ----------------

@Composable
private fun Entrar(st: AnaquelUiState, vm: AnaquelViewModel) {
    var registro by remember { mutableStateOf(false) }
    var nombre by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var tel by remember { mutableStateOf("") }
    val ocupado = st.ocupado == "sesion"
    LazyColumn(
        contentPadding = PaddingValues(NeuDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
    ) {
        item {
            Text("Anaquel", style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
            Texto("Una foto al día y tu tienda sabe qué le falta, a quién pedírselo y quién de tu zona lo produce.")
        }
        item {
            NeumorphicCard(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
                    NeumorphicChip("Entrar", selected = !registro, onClick = { registro = false })
                    NeumorphicChip("Registrar tienda", selected = registro, onClick = { registro = true })
                }
                Spacer(Modifier.height(NeuDimens.SpaceLg))
                Column(verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                    if (registro) NeumorphicInput(nombre, { nombre = it }, Modifier.fillMaxWidth(), placeholder = "Nombre de la tienda")
                    NeumorphicInput(email, { email = it }, Modifier.fillMaxWidth(), placeholder = "Correo",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    NeumorphicInput(pass, { pass = it }, Modifier.fillMaxWidth(), placeholder = "Contraseña",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                    if (registro) NeumorphicInput(tel, { tel = it }, Modifier.fillMaxWidth(), placeholder = "WhatsApp de la tienda",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    NeumorphicButton(
                        text = if (ocupado) "Un momento…" else if (registro) "Crear mi tienda" else "Entrar",
                        enabled = !ocupado,
                        onClick = { if (registro) vm.registrar(nombre, email, pass, tel) else vm.entrar(email, pass) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            NeumorphicCard(Modifier.fillMaxWidth()) {
                Titulo("¿Solo quieres verla funcionar?")
                Texto("Entra a «Abarrotes Doña Lupita», una tienda de demostración con anaquel, faltantes y pedidos.")
                Spacer(Modifier.height(NeuDimens.SpaceMd))
                NeumorphicButton("Entrar a la demo", onClick = vm::entrarDemo, enabled = !ocupado, containerColor = NeuColors.Blue, modifier = Modifier.fillMaxWidth())
            }
        }
        item { Texto("Tu inventario se sigue guardando en el teléfono y funciona sin internet. Anaquel sube una copia para revisar tus anaqueles.", Modifier.fillMaxWidth()) }
    }
}

// ---------------- Principal ----------------

@Composable
private fun Principal(st: AnaquelUiState, vm: AnaquelViewModel, onNuevo: (String) -> Unit) {
    var pedirNombre by remember { mutableStateOf(false) }
    var confirmarSalir by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.padding(start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = NeuDimens.SpaceLg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Anaquel", style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
                    Texto(st.tienda?.nombre.orEmpty())
                }
                NeumorphicIconButton(painterResource(Res.drawable.ic_upload), "Sincronizar inventario", onClick = vm::sincronizarInventario)
                Spacer(Modifier.width(NeuDimens.SpaceMd))
                TextButton(onClick = { confirmarSalir = true }) { Text("Salir", color = NeuColors.TextSecondary) }
            }
            LazyRow(
                contentPadding = PaddingValues(start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = NeuDimens.SpaceXl, bottom = NeuDimens.SpaceSm),
                horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd),
            ) {
                items(SeccionAnaquel.entries) { s ->
                    val n = when (s) {
                        SeccionAnaquel.HOY -> st.resumen?.sinPedir ?: 0
                        SeccionAnaquel.PEDIDOS -> st.resumen?.atrasados ?: 0
                        SeccionAnaquel.ANAQUELES -> st.resumen?.sinFotoHoy ?: 0
                    }
                    NeumorphicChip(if (n > 0) "${s.titulo} · $n" else s.titulo, selected = st.seccion == s, onClick = { vm.elegirSeccion(s) })
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = NeuDimens.SpaceXl,
                    bottom = NeuDimens.FabSize + NeuDimens.ScreenPadding * 2,
                ),
                verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
            ) {
                if (st.tienda?.slug == "dona-lupita" && st.seccion == SeccionAnaquel.HOY) {
                    item(key = "demo") {
                        NeumorphicChip("↺ Reiniciar demo", selected = false, onClick = vm::reiniciarDemo)
                    }
                }
                if (st.error != null && st.resumen == null) {
                    item { Errores(st.error, vm::refrescar) }
                }
                when (st.seccion) {
                    SeccionAnaquel.HOY -> hoy(st, vm)
                    SeccionAnaquel.ANAQUELES -> anaqueles(st, vm)
                    SeccionAnaquel.PEDIDOS -> pedidos(st, vm)
                }
            }
        }
        if (st.seccion == SeccionAnaquel.ANAQUELES) {
            NeumorphicFab(painterResource(Res.drawable.ic_add), "Nuevo anaquel", onClick = { pedirNombre = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(NeuDimens.ScreenPadding))
        }
    }
    if (pedirNombre) {
        var n by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pedirNombre = false },
            title = { Text("Nuevo anaquel") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
                    Text("Toma la foto con el anaquel LLENO, de frente y desde un lugar al que puedas volver cada día.")
                    NeumorphicInput(n, { n = it }, Modifier.fillMaxWidth(), placeholder = "Nombre (Refrescos, Botanas…)")
                }
            },
            confirmButton = { TextButton(onClick = { pedirNombre = false; onNuevo(n) }) { Text("Tomar foto") } },
            dismissButton = { TextButton(onClick = { pedirNombre = false }) { Text("Cancelar") } },
        )
    }
    if (confirmarSalir) {
        AlertDialog(
            onDismissRequest = { confirmarSalir = false },
            title = { Text("¿Cerrar sesión de Anaquel?") },
            text = { Text("Tu inventario del teléfono no se borra.") },
            confirmButton = { TextButton(onClick = { confirmarSalir = false; vm.salir() }) { Text("Cerrar sesión") } },
            dismissButton = { TextButton(onClick = { confirmarSalir = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Errores(error: String, reintentar: () -> Unit) {
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Text(error, color = NeuColors.OnLowStock)
        Spacer(Modifier.height(NeuDimens.SpaceMd))
        NeumorphicButton("Reintentar", onClick = reintentar)
    }
}

@Composable
private fun Cifra(valor: String, texto: String, alerta: Boolean, modifier: Modifier) {
    NeumorphicCard(modifier, elevation = NeuDimens.ElevationMedium) {
        Text(valor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            color = if (alerta) NeuColors.OnLowStock else NeuColors.TextPrimary)
        Text(texto, style = MaterialTheme.typography.bodySmall, color = NeuColors.TextSecondary)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.hoy(st: AnaquelUiState, vm: AnaquelViewModel) {
    val r = st.resumen
    if (r != null) {
        item(key = "cifras") {
            Column(verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                    Cifra("${r.sinPedir}", "por pedir", r.sinPedir > 0, Modifier.weight(1f))
                    Cifra("${r.pedidosAbiertos}", "en camino", false, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                    Cifra("${r.atrasados}", "no han llegado", r.atrasados > 0, Modifier.weight(1f))
                    Cifra("${r.anaqueles - r.sinFotoHoy}/${r.anaqueles}", "fotos de hoy", false, Modifier.weight(1f))
                }
            }
        }
        if (r.anaqueles > 0 && r.sinFotoHoy > 0) {
            item(key = "sinfoto") {
                NeumorphicCard(Modifier.fillMaxWidth(), onClick = { vm.elegirSeccion(SeccionAnaquel.ANAQUELES) }) {
                    Text("Te faltan ${r.sinFotoHoy} anaquel(es) por fotografiar hoy. Toca para ir.", color = NeuColors.TextPrimary)
                }
            }
        }
        if (r.anaqueles == 0) {
            item(key = "primero") {
                NeumorphicCard(Modifier.fillMaxWidth(), onClick = { vm.elegirSeccion(SeccionAnaquel.ANAQUELES) }) {
                    Titulo("Empieza con tu primer anaquel")
                    Texto("Toma la foto de tu anaquel lleno y marca dónde va cada producto.")
                }
            }
        }
    }
    item(key = "titulo-resurtido") { Titulo("Lista de resurtido") }
    val grupos = vm.grupos(st)
    if (st.faltantes != null && grupos.isEmpty()) {
        item(key = "nada") { Texto("No falta nada por pedir. Toma la foto de hoy para revisar tus anaqueles.", Modifier.fillMaxWidth()) }
    }
    if (st.faltantes == null && st.error == null) item(key = "cargando") { Texto("Cargando…") }
    items(grupos, key = { "g-" + it.clave }) { g -> GrupoCard(g, st, vm) }
    val pedidos = st.faltantes.orEmpty().filter { it.enPedido != null }
    if (pedidos.isNotEmpty()) {
        item(key = "ya") {
            NeumorphicCard(Modifier.fillMaxWidth(), onClick = { vm.elegirSeccion(SeccionAnaquel.PEDIDOS) }) {
                Titulo("Ya pedidos")
                pedidos.forEach { f ->
                    Spacer(Modifier.height(NeuDimens.SpaceSm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.producto.nombre, color = NeuColors.TextPrimary, modifier = Modifier.weight(1f))
                        Texto("Pedido #${f.enPedido}")
                    }
                }
            }
        }
    }
}

@Composable
private fun GrupoCard(g: GrupoPedido, st: AnaquelUiState, vm: AnaquelViewModel) {
    val s = g.sugerencia
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Titulo(s?.nombre ?: "Sin proveedor asignado")
        Texto(
            when {
                s == null -> "Agrégale proveedor desde la web (Productos o Proveedores)."
                s.tipo == "productor" -> "🌱 Productor local" + (s.distanciaKm?.let { " · a $it km" } ?: "") + (s.lugar?.let { " · $it" } ?: "")
                else -> "Proveedor"
            },
        )
        g.items.forEach { f ->
            Spacer(Modifier.height(NeuDimens.SpaceLg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f.producto.nombre, fontWeight = FontWeight.SemiBold, color = NeuColors.TextPrimary)
                    Texto(f.motivo)
                }
                EtiquetaNivel(f.nivel)
            }
            Spacer(Modifier.height(NeuDimens.SpaceSm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeumorphicIconButton(painterResource(Res.drawable.ic_remove), "Menos", onClick = { vm.cambiarCantidad(f.producto.id, -1) },
                    size = NeuDimens.IconButtonSmallSize, iconSize = NeuDimens.IconSmallSize)
                Text("${st.cantidades[f.producto.id] ?: f.sugerido}", style = MaterialTheme.typography.titleMedium, color = NeuColors.TextPrimary,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 44.dp))
                NeumorphicIconButton(painterResource(Res.drawable.ic_add), "Más", onClick = { vm.cambiarCantidad(f.producto.id, 1) },
                    size = NeuDimens.IconButtonSmallSize, iconSize = NeuDimens.IconSmallSize)
                Spacer(Modifier.width(NeuDimens.SpaceMd))
                if (f.sugerencias.size > 1) {
                    val elegida = f.sugerencias.firstOrNull { it.clave == (st.eleccion[f.producto.id] ?: f.sugerencias.first().clave) } ?: f.sugerencias.first()
                    NeumorphicDropdown(
                        selected = elegida, options = f.sugerencias,
                        optionLabel = { (if (it.tipo == "productor") "🌱 " else "") + it.nombre + (it.distanciaKm?.let { d -> " ($d km)" } ?: "") },
                        onSelect = { vm.elegir(f.producto.id, it.clave) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (s != null) {
            Spacer(Modifier.height(NeuDimens.SpaceLg))
            NeumorphicButton(
                text = if (st.ocupado == g.clave) "Enviando…" else "Pedir por WhatsApp (${g.items.size})",
                onClick = { vm.pedir(g) }, enabled = st.ocupado != g.clave, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.anaqueles(st: AnaquelUiState, vm: AnaquelViewModel) {
    val lista = st.anaqueles
    if (lista == null) { item { Texto("Cargando…") }; return }
    if (lista.isEmpty()) {
        item {
            Texto("Aún no tienes anaqueles.\nToca + y toma la foto de tu anaquel lleno.", Modifier.fillMaxWidth().padding(top = NeuDimens.SpaceXxl))
        }
    }
    items(lista, key = { it.id }) { a ->
        NeumorphicCard(Modifier.fillMaxWidth(), onClick = { vm.abrir(a) }) {
            val foto = rememberFoto(a.ultimaFoto ?: a.fotoRef, vm::foto)
            FotoConZonas(foto, a.zonas)
            Spacer(Modifier.height(NeuDimens.SpaceMd))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Titulo(a.nombre)
                    Texto("${a.zonas.size} zonas · última foto ${hace(a.ultimaEn)}")
                }
                if (a.faltantes > 0) {
                    Text("${a.faltantes} con hueco", style = MaterialTheme.typography.labelMedium, color = Color.White,
                        modifier = Modifier.background(RojoVacio, NeuShapes.Pill).padding(horizontal = 10.dp, vertical = 2.dp))
                } else EtiquetaNivel("lleno")
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.pedidos(st: AnaquelUiState, vm: AnaquelViewModel) {
    val lista = st.pedidos
    if (lista == null) { item { Texto("Cargando…") }; return }
    val abiertos = lista.filter { it.abierto }
    val cerrados = lista.filterNot { it.abierto }.take(15)
    if (abiertos.isEmpty()) item { Texto("No hay pedidos en camino. Se crean desde «Hoy».", Modifier.fillMaxWidth()) }
    items(abiertos, key = { it.id }) { p -> PedidoCard(p, st, vm) }
    if (cerrados.isNotEmpty()) item { Titulo("Anteriores") }
    items(cerrados, key = { it.id }) { p -> PedidoCard(p, st, vm) }
}

@Composable
private fun PedidoCard(p: Pedido, st: AnaquelUiState, vm: AnaquelViewModel) {
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Titulo("#${p.id} · ${p.destino}")
                Texto((if (p.tipo == "productor") "🌱 Local · " else "") + "pedido ${hace(p.creado)}" +
                    if (p.estado == "llego" && p.llegoAuto) " · confirmado por foto" else "")
            }
            EtiquetaNivel(p.estado)
        }
        if (p.atrasado) {
            Spacer(Modifier.height(NeuDimens.SpaceSm))
            Text("No ha llegado en ${p.dias} días", color = NeuColors.OnLowStock, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(NeuDimens.SpaceSm))
        p.items.forEach { Texto("${it.cantidad} × ${it.nombre}" + if (it.recibido) "  ✓" else "", secundario = it.recibido) }
        if (p.abierto) {
            Spacer(Modifier.height(NeuDimens.SpaceLg))
            NeumorphicButton(if (p.atrasado) "Preguntar por WhatsApp" else "Abrir WhatsApp", onClick = { vm.reenviar(p) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(NeuDimens.SpaceMd))
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
                if (p.estado == "enviado") NeumorphicChip("Me confirmó", selected = false, onClick = { vm.cambiarEstado(p, "confirmado") })
                NeumorphicChip("Ya llegó", selected = false, onClick = { vm.cambiarEstado(p, "llego") })
                NeumorphicChip("Cancelar", selected = false, onClick = { vm.cambiarEstado(p, "cancelado") })
            }
        }
    }
}

// ---------------- Detalle del anaquel ----------------

@Composable
private fun DetalleAnaquel(st: AnaquelUiState, a: Anaquel, vm: AnaquelViewModel, tomarFoto: (Proposito) -> Unit) {
    if (st.editandoZonas) {
        EditorZonas(a, st, vm)
        return
    }
    var verReferencia by remember(a.id) { mutableStateOf(false) }
    var renombrar by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(NeuDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::cerrar) { Text("← Anaqueles", color = NeuColors.Blue) }
            }
            Text(a.nombre, style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
            Texto("${a.zonas.size} zonas · última foto ${hace(a.ultimaEn)}")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicButton("Foto de hoy", onClick = { tomarFoto(Proposito.HOY) }, enabled = a.zonas.isNotEmpty(), modifier = Modifier.weight(1f))
                NeumorphicButton("Editar zonas", onClick = { vm.editarZonas() }, containerColor = NeuColors.Blue, modifier = Modifier.weight(1f))
            }
        }
        if (a.zonas.isEmpty()) item { Texto("Este anaquel no tiene zonas. Toca «Editar zonas» y dibuja un recuadro sobre cada producto.") }
        st.resultado?.let { r -> item { ResultadoCard(r, vm) } }
        item {
            NeumorphicCard(Modifier.fillMaxWidth()) {
                if (a.ultimaFoto != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
                        NeumorphicChip("Última foto", selected = !verReferencia, onClick = { verReferencia = false })
                        NeumorphicChip("Lleno (referencia)", selected = verReferencia, onClick = { verReferencia = true })
                    }
                    Spacer(Modifier.height(NeuDimens.SpaceLg))
                }
                val foto = rememberFoto(if (verReferencia || a.ultimaFoto == null) a.fotoRef else a.ultimaFoto, vm::foto)
                FotoConZonas(foto, a.zonas)
                Spacer(Modifier.height(NeuDimens.SpaceSm))
                Texto("Verde = lleno · Amarillo = queda poco · Rojo = vacío")
            }
        }
        items(a.zonas, key = { it.id }) { z ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(z.producto.ifBlank { "Zona sin producto" }, color = NeuColors.TextPrimary, modifier = Modifier.weight(1f))
                EtiquetaNivel(z.nivel)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
                NeumorphicChip("Cambiar referencia", selected = false, onClick = { tomarFoto(Proposito.REFERENCIA) })
                NeumorphicChip("Renombrar", selected = false, onClick = { renombrar = true })
                NeumorphicChip("Borrar", selected = false, onClick = { borrar = true })
            }
        }
    }
    if (renombrar) {
        var n by remember { mutableStateOf(a.nombre) }
        AlertDialog(
            onDismissRequest = { renombrar = false }, title = { Text("Renombrar anaquel") },
            text = { NeumorphicInput(n, { n = it }, Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { renombrar = false; vm.renombrar(n) }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renombrar = false }) { Text("Cancelar") } },
        )
    }
    if (borrar) {
        AlertDialog(
            onDismissRequest = { borrar = false }, title = { Text("¿Borrar «${a.nombre}»?") },
            text = { Text("Se borran su foto y sus zonas. Los productos no se borran.") },
            confirmButton = { TextButton(onClick = { borrar = false; vm.borrarAnaquel() }) { Text("Borrar", color = NeuColors.OnLowStock) } },
            dismissButton = { TextButton(onClick = { borrar = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ResultadoCard(r: ResultadoFoto, vm: AnaquelViewModel) {
    NeumorphicCard(Modifier.fillMaxWidth()) {
        Titulo(if (r.faltantes.isEmpty()) "¡Todo lleno! No falta nada." else "Se están acabando ${r.faltantes.size}:")
        if (r.faltantes.isNotEmpty()) Texto(r.faltantes.joinToString { it.producto.ifBlank { "zona sin producto" } }, secundario = false)
        r.llegaron.forEach { p ->
            Spacer(Modifier.height(NeuDimens.SpaceSm))
            Text("✓ El pedido #${p.id} de ${p.destino} ya llegó. Lo marqué como recibido.", color = VerdeLleno, fontWeight = FontWeight.SemiBold)
        }
        if (r.faltantes.isNotEmpty()) {
            Spacer(Modifier.height(NeuDimens.SpaceMd))
            NeumorphicButton("Ver a quién pedirlo", onClick = { vm.cerrar(); vm.elegirSeccion(SeccionAnaquel.HOY) }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(NeuDimens.SpaceSm))
        Texto("Revisado por " + if (r.metodo == "ia") "inteligencia artificial." else "comparación con la foto de referencia.")
    }
}

@Composable
private fun EditorZonas(a: Anaquel, st: AnaquelUiState, vm: AnaquelViewModel) {
    var zonas by remember(a.id) { mutableStateOf(a.zonas) }
    var sel by remember(a.id) { mutableStateOf<Int?>(null) }
    val foto = rememberFoto(a.fotoRef, vm::foto)
    val nombres = remember(st.productosNube) { st.productosNube.associate { it.id to it.nombre } }
    LazyColumn(
        contentPadding = PaddingValues(NeuDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
        userScrollEnabled = true,
    ) {
        item {
            Text("Zonas de ${a.nombre}", style = MaterialTheme.typography.headlineSmall, color = NeuColors.TextPrimary)
            Texto("Arrastra sobre la foto para dibujar un recuadro sobre cada producto. Toca uno para elegir qué producto es; arrástralo para moverlo o jala la bolita para cambiar su tamaño.")
        }
        item {
            if (foto == null) Texto("Cargando foto…")
            else EditorZonasFoto(
                foto = foto, zonas = zonas, seleccion = sel,
                nombreDe = { id -> id?.let { nombres[it] } ?: "Elige producto" },
                onCambio = { zonas = it }, onSeleccion = { sel = it },
            )
        }
        val i = sel
        if (i != null && i in zonas.indices) {
            item {
                NeumorphicCard(Modifier.fillMaxWidth()) {
                    Titulo("¿Qué producto va aquí?")
                    Spacer(Modifier.height(NeuDimens.SpaceMd))
                    val opciones: List<ProductoNube?> = listOf<ProductoNube?>(null) + st.productosNube
                    NeumorphicDropdown(
                        selected = st.productosNube.firstOrNull { it.id == zonas[i].productoId },
                        options = opciones,
                        optionLabel = { it?.nombre ?: "— Sin producto —" },
                        onSelect = { p -> zonas = zonas.mapIndexed { j, z -> if (j == i) z.copy(productoId = p?.id, producto = p?.nombre.orEmpty()) else z } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(NeuDimens.SpaceMd))
                    NeumorphicChip("Quitar esta zona", selected = false, onClick = { zonas = zonas.filterIndexed { j, _ -> j != i }; sel = null })
                }
            }
        }
        if (st.productosNube.isEmpty()) {
            item { Texto("No hay productos en la nube. Toca el botón de sincronizar en la pantalla principal para subir tu inventario.") }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicButton(if (st.ocupado == "zonas") "Guardando…" else "Guardar ${zonas.size} zonas",
                    onClick = { vm.guardarZonas(zonas) }, enabled = st.ocupado != "zonas", modifier = Modifier.weight(1f))
                NeumorphicButton("Cancelar", onClick = vm::cancelarZonas, containerColor = NeuColors.Background, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.size(NeuDimens.SpaceSm))
            Texto("${zonas.count { it.productoId == null }} zonas sin producto")
        }
    }
}

private fun hace(ms: Long?): String {
    if (ms == null) return "nunca"
    val min = ((kotlin.time.Clock.System.now().toEpochMilliseconds() - ms) / 60000).coerceAtLeast(0)
    return when {
        min < 1 -> "hace un momento"
        min < 60 -> "hace $min min"
        min < 60 * 24 -> "hace ${min / 60} h"
        min < 60 * 48 -> "ayer"
        else -> "hace ${min / 1440} días"
    }
}
