package com.eter.mitienda.feature.mercado

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Estado de la sesión del mercado (vive en AppContainer: una por proceso). */
class MercadoSesion(val api: MercadoApi, private val almacen: AlmacenSeguro) {
    var cuenta by mutableStateOf<Cuenta?>(null)
    var revisada by mutableStateOf(false)

    init {
        almacen.leer("servidor")?.let { api.base = it }
        api.token = almacen.leer("token")
        api.alPerderSesion = { clave ->
            if (clave == "sin_sesion") cerrarLocal() else cuenta = cuenta?.copy(estado = if (clave == "suspendida") "suspendida" else "pendiente")
        }
    }

    suspend fun restaurar() {
        cuenta = if (api.token == null) null else runCatching { api.sesion() }.getOrNull()
        if (cuenta == null) cerrarLocal()
        revisada = true
    }

    fun entro(r: RespSesion) {
        api.token = r.token
        almacen.guardar("token", r.token)
        cuenta = r.cuenta
    }

    suspend fun salir() {
        api.salir()
        cerrarLocal()
    }

    private fun cerrarLocal() {
        api.token = null
        almacen.guardar("token", null)
        cuenta = null
    }

    fun cambiarServidor(url: String) {
        val u = url.trim().trimEnd('/')
        api.base = u
        almacen.guardar("servidor", u)
    }
}

private object Rutas {
    const val EXPLORAR = "explorar?q={q}&categoria={categoria}"
    fun explorar(q: String = "", categoria: String = "") = "explorar?q=${q.codificar()}&categoria=${categoria.codificar()}"
    const val REELS = "reels"
    const val ORDENES = "ordenes?ventas={ventas}"
    fun ordenes(ventas: Boolean = false) = "ordenes?ventas=$ventas"
    const val VENDER = "vender?tab={tab}"
    fun vender(tab: String = "publicaciones") = "vender?tab=$tab"
    const val DETALLE = "producto/{id}"
    const val VENDEDOR = "vendedor/{slug}"
    const val ORDEN = "orden/{id}"
    const val PUBLICAR = "publicar?id={id}"
    fun publicar(id: Long? = null) = "publicar?id=${id ?: -1}"
    const val TERMINAL = "terminal"
    const val CUENTA = "cuenta"
}

private fun String.codificar() = buildString {
    for (b in this@codificar.encodeToByteArray()) {
        val ch = b.toInt().toChar()
        if (ch.isLetterOrDigit() && ch.code < 128 || ch in "-_.~") append(ch) else append('%' + (b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}

/** Navegación del mercado, para que cada pantalla no conozca las rutas. */
class Ir(private val nav: NavHostController) {
    fun atras() { nav.popBackStack() }
    fun producto(id: Long) = nav.navigate("producto/$id")
    fun vendedor(slug: String) = nav.navigate("vendedor/$slug")
    fun orden(id: Long) = nav.navigate("orden/$id")
    fun ordenes(ventas: Boolean) = nav.navigate(Rutas.ordenes(ventas)) { launchSingleTop = true }
    fun publicar(id: Long? = null) = nav.navigate(Rutas.publicar(id))
    fun vender(tab: String) = nav.navigate(Rutas.vender(tab)) { launchSingleTop = true }
    fun terminal() = nav.navigate(Rutas.TERMINAL)
    fun cuenta() = nav.navigate(Rutas.CUENTA)
    fun explorar(q: String = "", categoria: String = "") = nav.navigate(Rutas.explorar(q, categoria)) { launchSingleTop = true; popUpTo(Rutas.EXPLORAR) { inclusive = true } }
}

/**
 * Pestaña «Mercado» de la app: el mismo mercado que la web, en pantallas nativas.
 * Solo entran negocios verificados (protocolo de acceso del servicio "acceso").
 */
@Composable
fun MercadoApp(sesion: MercadoSesion) {
    val snack = remember { SnackbarHostState() }
    val alcance = rememberCoroutineScope()
    val avisar: (String, Boolean) -> Unit = { t, _ -> alcance.launch { snack.showSnackbar(t) } }
    LaunchedEffect(Unit) { if (!sesion.revisada) sesion.restaurar() }

    MercadoTema {
        CompositionLocalProvider(LocalAvisar provides avisar) {
            Box(Modifier.fillMaxSize().background(M.c.fondo).windowInsetsPadding(WindowInsets.statusBars)) {
                val cuenta = sesion.cuenta
                when {
                    !sesion.revisada -> CargandoInicio()
                    cuenta == null -> Acceso(sesion)
                    !cuenta.verificada -> Revision(cuenta) { alcance.launch { sesion.salir() } }
                    else -> Principal(sesion, cuenta)
                }
                SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(E.x4)) { d ->
                    Snackbar(d, containerColor = M.c.texto, contentColor = M.c.superficie, shape = androidx.compose.foundation.shape.RoundedCornerShape(E.radioControl))
                }
            }
        }
    }
}

@Composable
private fun CargandoInicio() {
    Cargando("Abriendo el mercado…") {
        Column(Modifier.fillMaxSize().padding(E.x4), verticalArrangement = Arrangement.spacedBy(E.x4)) {
            Esqueleto(Modifier.fillMaxWidth(.6f).height(28.dp))
            Esqueleto(Modifier.fillMaxWidth().height(48.dp), androidx.compose.foundation.shape.CircleShape)
            Row(horizontalArrangement = Arrangement.spacedBy(E.x3)) {
                EsqueletoProducto(Modifier.weight(1f)); EsqueletoProducto(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Principal(sesion: MercadoSesion, cuenta: Cuenta) {
    val nav = rememberNavController()
    val ir = remember(nav) { Ir(nav) }
    val api = sesion.api
    val ruta = nav.currentBackStackEntryAsState().value?.destination?.route ?: Rutas.EXPLORAR
    val pestanas = buildList {
        add(Triple("Explorar", "tienda", Rutas.EXPLORAR))
        add(Triple("Reels", "video", Rutas.REELS))
        add(Triple("Órdenes", "recibo", Rutas.ORDENES))
        if (cuenta.vende) add(Triple("Vender", "etiqueta", Rutas.VENDER))
    }
    val principal = pestanas.indexOfFirst { it.third == ruta }
    val avisar = LocalAvisar.current

    // «Pago recibido»: el registro de cobros del sistema de pagos se revisa cada 10 s.
    if (cuenta.vende) LaunchedEffect(cuenta.id) {
        var ultimo: Long? = null
        while (true) {
            runCatching { api.registros(ultimo?.takeIf { it > 0 }) }.onSuccess { r ->
                if (ultimo != null) r.items.reversed().forEach {
                    avisar("Pago recibido: ${pesos(it.montoC)} en ${it.terminal.ifBlank { it.marca }}" + if (it.origen == "terminal") " (cobro directo)" else "", false)
                }
                ultimo = maxOf(ultimo ?: 0, r.items.maxOfOrNull { it.id } ?: 0)
            }
            delay(10_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (principal >= 0) {
            Row(Modifier.fillMaxWidth().padding(start = E.x4, end = E.x2, top = E.x2), verticalAlignment = Alignment.CenterVertically) {
                Text("Anaquel ", style = MaterialTheme.typography.titleLarge, color = M.c.texto)
                Text("Mercado", style = MaterialTheme.typography.titleLarge, color = M.c.acento, modifier = Modifier.weight(1f))
                Surface(onClick = { ir.cuenta() }, shape = androidx.compose.foundation.shape.CircleShape, color = M.c.fondo,
                    modifier = Modifier.semantics { contentDescription = "Mi cuenta: ${cuenta.nombre}" }) {
                    Box(Modifier.padding(2.dp)) { Avatar(cuenta.nombre, 40.dp) }
                }
            }
            SecondaryScrollableTabRow(
                selectedTabIndex = principal, containerColor = M.c.fondo, contentColor = M.c.marca, edgePadding = E.x2,
                indicator = { TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(principal), height = 3.dp, color = M.c.marca) },
                divider = { Separador() },
            ) {
                pestanas.forEachIndexed { i, (txt, ic, r) ->
                    Tab(
                        selected = i == principal,
                        onClick = {
                            if (i != principal) nav.navigate(when (r) { Rutas.EXPLORAR -> Rutas.explorar(); Rutas.ORDENES -> Rutas.ordenes(); Rutas.VENDER -> Rutas.vender(); else -> r }) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        selectedContentColor = M.c.marca, unselectedContentColor = M.c.texto2,
                        text = { Text(txt, fontWeight = if (i == principal) FontWeight.Bold else FontWeight.SemiBold) },
                        icon = { Icon(icono(ic), null, Modifier.size(20.dp)) },
                    )
                }
            }
        }
        NavHost(nav, startDestination = Rutas.EXPLORAR, modifier = Modifier.weight(1f)) {
            composable(Rutas.EXPLORAR, arguments = listOf(
                navArgument("q") { type = NavType.StringType; defaultValue = "" },
                navArgument("categoria") { type = NavType.StringType; defaultValue = "" },
            )) { e ->
                Explorar(api, ir, e.arguments?.getString("q") ?: "", e.arguments?.getString("categoria") ?: "")
            }
            composable(Rutas.REELS) { Reels(api, ir) }
            composable(Rutas.ORDENES, arguments = listOf(navArgument("ventas") { type = NavType.BoolType; defaultValue = false })) { e ->
                Ordenes(api, ir, cuenta, e.arguments?.getBoolean("ventas") ?: false)
            }
            composable(Rutas.VENDER, arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "publicaciones" })) { e ->
                Vender(api, ir, e.arguments?.getString("tab") ?: "publicaciones")
            }
            composable(Rutas.DETALLE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                Detalle(api, ir, e.arguments?.getLong("id") ?: 0)
            }
            composable(Rutas.VENDEDOR, arguments = listOf(navArgument("slug") { type = NavType.StringType })) { e ->
                PaginaVendedor(api, ir, e.arguments?.getString("slug") ?: "")
            }
            composable(Rutas.ORDEN, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                OrdenDetalle(api, ir, e.arguments?.getLong("id") ?: 0)
            }
            composable(Rutas.PUBLICAR, arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L })) { e ->
                Publicar(api, ir, e.arguments?.getLong("id")?.takeIf { it > 0 })
            }
            composable(Rutas.TERMINAL) { TerminalVirtual(api, ir) }
            composable(Rutas.CUENTA) { PaginaCuenta(sesion, ir) }
        }
    }
}
