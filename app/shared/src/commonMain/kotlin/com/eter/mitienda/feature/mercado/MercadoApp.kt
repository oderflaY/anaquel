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
import androidx.compose.ui.graphics.Color
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
        // v2: los productos de ejemplo ya traen foto (el estado guardado antes no las tenía).
        api.guardarDemo = { almacen.guardar("demo_estado_v2", it) }
        api.leerDemo = { almacen.leer("demo_estado_v2") }
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
    // Cada vez que se abre la app: ¿hay una versión nueva en los releases del repo?
    LaunchedEffect(Unit) { Actualizaciones.revisar(alAbrir = true) }

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
                VentanaActualizacion()
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
            MarcaAnaquel(Modifier.latido(0.03f))
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
    val vende = cuenta.vende && !SOLO_COMPRAS
    val principales = buildList {
        add(Rutas.EXPLORAR); add(Rutas.REELS); add(Rutas.ORDENES); add(Rutas.CUENTA)
        if (vende) add(Rutas.VENDER)
    }
    val principal = ruta in principales
    val avisar = LocalAvisar.current
    var hoja by remember { mutableStateOf(false) }
    // Compras que esperan pago: globo en la pestaña «Compras».
    var porPagar by remember { mutableStateOf(0) }
    LaunchedEffect(ruta) { runCatching { api.ordenes(false) }.onSuccess { l -> porPagar = l.count { it.estado == "pendiente" || it.estado == "por_cobrar" } } }
    val categorias = rememberConsulta { api.categorias() }
    fun irPestana(r: String) {
        if (r == ruta) return
        nav.navigate(when (r) { Rutas.EXPLORAR -> Rutas.explorar(); Rutas.ORDENES -> Rutas.ordenes(); Rutas.VENDER -> Rutas.vender(); else -> r }) {
            popUpTo(nav.graph.startDestinationId) { saveState = true }
            launchSingleTop = true; restoreState = true
        }
    }

    // «Pago recibido»: el registro de cobros del sistema de pagos se revisa cada 10 s.
    if (vende) LaunchedEffect(cuenta.id) {
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
        if (principal && ruta != Rutas.REELS) EncabezadoMercado(cuenta, api.enDemo) { irPestana(Rutas.EXPLORAR) }
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
        if (principal) BarraMercado(
            izquierda = listOf(PestanaMercado(Rutas.EXPLORAR, "Inicio", "casa"), PestanaMercado(Rutas.REELS, "Reels", "video")),
            derecha = listOf(PestanaMercado(Rutas.ORDENES, "Compras", "recibo", porPagar), PestanaMercado(Rutas.CUENTA, "Cuenta", "usuario", if (Actualizaciones.disponible != null) 1 else 0)),
            activa = ruta, onElegir = ::irPestana, onMas = { hoja = true },
        )
    }
    if (hoja) HojaAccesos(
        listOf(
            GrupoAtajos("Comprar", listOf(
                Atajo("buscar", "buscar", Color(0xFF2F80ED), "Buscar producto"),
                Atajo("reels", "video", Color(0xFF8E44AD), "Ver reels"),
                Atajo("todo", "tienda", Color(0xFF0E7C5A), "Todo el mercado"),
            )),
            GrupoAtajos("Categorías", categorias.datos.orEmpty().map { c ->
                Atajo("cat:" + c.clave, ICONO_CATEGORIA[c.clave] ?: c.icono, TONOS[c.clave] ?: TONOS.getValue("otros"), c.nombre)
            }),
            GrupoAtajos("Mis compras", listOf(
                Atajo("compras", "recibo", Color(0xFFE0871B), "Por pagar", porPagar),
                Atajo("cuenta", "usuario", Color(0xFF3F51B5), "Mi cuenta"),
                Atajo("invitar", "invitar", Color(0xFF00897B), "Invitar negocio"),
                Atajo("actualizar", "descargar", Color(0xFF2F5DA8), "Actualizar app", if (Actualizaciones.disponible != null) 1 else 0),
            )),
        ),
        onCerrar = { hoja = false },
    ) { id ->
        hoja = false
        when {
            id.startsWith("cat:") -> ir.explorar(categoria = id.removePrefix("cat:"))
            id == "buscar" || id == "todo" -> ir.explorar()
            id == "reels" -> irPestana(Rutas.REELS)
            id == "compras" -> irPestana(Rutas.ORDENES)
            id == "actualizar" && Actualizaciones.disponible != null -> Actualizaciones.mostrarVentana = true
            else -> irPestana(Rutas.CUENTA)
        }
    }
}
