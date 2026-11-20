package com.eter.mitienda.feature.anaquel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.core.util.readFileBytes
import com.eter.mitienda.data.local.AjustesDao
import com.eter.mitienda.domain.repository.ProductRepository
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SeccionAnaquel(val titulo: String) {
    HOY("Hoy"), BODEGA("Mi bodega"), ASESOR("Asesor"), RED("Red"), TRATOS("Tratos"), ANAQUELES("Anaqueles"), PEDIDOS("Pedidos"), MAYOREO("Vender al mayoreo"),
}

data class AnaquelUiState(
    val revisandoSesion: Boolean = true,
    val tienda: TiendaNube? = null,
    val seccion: SeccionAnaquel = SeccionAnaquel.HOY,
    val resumen: Resumen? = null,
    val faltantes: List<Faltante>? = null,
    val anaqueles: List<Anaquel>? = null,
    val pedidos: List<Pedido>? = null,
    /** A quién se le pide cada producto (clave de sugerencia) y cuántos. */
    val eleccion: Map<Long, String> = emptyMap(),
    val cantidades: Map<Long, Long> = emptyMap(),
    /** Anaquel abierto en detalle. */
    val abierto: Anaquel? = null,
    val editandoZonas: Boolean = false,
    val productosNube: List<ProductoNube> = emptyList(),
    val resultado: ResultadoFoto? = null,
    val ocupado: String? = null,
    val error: String? = null,
    // Red de comercio local
    val consultaRed: String = "",
    val red: List<ResultadoRed>? = null,
    val tratos: List<Trato>? = null,
    val trato: Trato? = null,
    val asesor: List<MensajeAsesor>? = null,
    val motorAsesor: String = "basico",
    /** Pregunta que el asesor está contestando. */
    val pensando: String? = null,
    // Inicio y chats
    val inicio: Inicio? = null,
    val chats: List<Chat>? = null,
    val chat: Chat? = null,
    /** Pedido que se está armando (diálogo con cantidades). */
    val pidiendo: PedidoEnCurso? = null,
    /** Mi vitrina, para elegir qué mandar como catálogo. */
    val miCatalogo: List<ItemCatalogo>? = null,
    /** Perfil de otra tienda abierto (hoja de perfil). */
    val perfil: PerfilTienda? = null,
    /** Mis productos al mayoreo y a quién se los muestro. */
    val mayoreo: MiMayoreo? = null,
)

/** Pedido a medio armar desde un catálogo: productos de la otra tienda y cantidades. */
data class PedidoEnCurso(
    val chatId: Long, val tienda: String, val catalogo: Long?, val items: List<ItemCatalogo>,
    val cantidades: Map<Long, Long> = emptyMap(),
) {
    val total: Long get() = items.sumOf { it.precioPara(cantidades[it.productoId] ?: 0) * (cantidades[it.productoId] ?: 0) }
    val piezas: Long get() = cantidades.values.sum()
}

/** Un grupo de la lista de resurtido: a quién pedir y qué. */
data class GrupoPedido(val clave: String, val sugerencia: Sugerencia?, val items: List<Faltante>)

/**
 * Estado de la pestaña Anaquel (la parte en la nube de la app). El inventario
 * sigue siendo local y funciona sin internet; aquí solo se sube una copia para
 * que el servidor sepa qué producto va en cada hueco del anaquel.
 */
class AnaquelViewModel(
    private val api: AnaquelApi,
    private val ajustes: AjustesDao,
    private val productos: ProductRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AnaquelUiState())
    val state: StateFlow<AnaquelUiState> = _state

    /** Mensajes de un solo uso (snackbar). */
    var mensaje by mutableStateOf<String?>(null)
        private set

    /** Enlace de WhatsApp que la pantalla debe abrir (y luego limpiar). */
    var abrirEnlace by mutableStateOf<String?>(null)
        private set

    private var sincronizado = false

    init {
        viewModelScope.launch {
            val tok = runCatching { ajustes.leer(CLAVE_TOKEN) }.getOrNull()
            if (tok == null) {
                _state.update { it.copy(revisandoSesion = false) }
                return@launch
            }
            api.token = tok
            val nombre = runCatching { ajustes.leer(CLAVE_TIENDA) }.getOrNull()
            val slug = runCatching { ajustes.leer(CLAVE_SLUG) }.getOrNull().orEmpty()
            _state.update { it.copy(revisandoSesion = false, tienda = TiendaNube(0, nombre ?: "Mi tienda", slug)) }
            refrescar()
        }
    }

    fun onMensajeMostrado() { mensaje = null }
    fun onEnlaceAbierto() { abrirEnlace = null }

    // ---------------- Sesión ----------------

    fun entrar(email: String, password: String) = conSesion { api.entrar(email, password) }
    fun entrarDemo(email: String = "demo@anaquel.mx") = conSesion { api.entrar(email, "demo1234") }
    fun registrar(nombre: String, email: String, password: String, telefono: String) =
        conSesion { api.registrar(nombre, email, password, telefono) }

    private fun conSesion(accion: suspend () -> Sesion) = trabajo("sesion") {
        val s = accion()
        api.token = s.token
        ajustes.guardar(CLAVE_TOKEN, s.token)
        ajustes.guardar(CLAVE_TIENDA, s.tienda.nombre)
        ajustes.guardar(CLAVE_SLUG, s.tienda.slug)
        sincronizado = false
        _state.update { AnaquelUiState(revisandoSesion = false, tienda = s.tienda) }
        refrescarAhora()
    }

    /** true solo la primera vez: después no volvemos a molestar con el permiso. */
    suspend fun tocaPedirPermiso(): Boolean {
        if (runCatching { ajustes.leer("anaquel.permiso.notif") }.getOrNull() != null) return false
        runCatching { ajustes.guardar("anaquel.permiso.notif", "1") }
        return true
    }

    fun reiniciarDemo() = conSesion { api.reiniciarDemo() }

    fun salir() = viewModelScope.launch {
        api.token = null
        ajustes.guardar(CLAVE_TOKEN, null)
        ajustes.guardar("anaquel.notif.aviso", null)
        ajustes.guardar("anaquel.notif.chat", null)
        _state.value = AnaquelUiState(revisandoSesion = false)
    }

    // ---------------- Datos ----------------

    fun elegirSeccion(s: SeccionAnaquel) {
        _state.update { it.copy(seccion = s, trato = null) }
        when (s) {
            SeccionAnaquel.ASESOR -> if (_state.value.asesor == null) trabajo(null) { cargarAsesor() }
            SeccionAnaquel.RED -> if (_state.value.red == null) buscarRed(_state.value.consultaRed)
            SeccionAnaquel.TRATOS -> trabajo(null) { _state.update { it.copy(tratos = api.tratos()) } }
            SeccionAnaquel.MAYOREO -> cargarMayoreo()
            else -> Unit
        }
    }

    /**
     * La bodega 3D es la misma página del panel en un WebView: ?app=1 quita el
     * encabezado, ?q= pregunta en cuanto carga y el token va en el hash (no viaja al servidor en la URL).
     */
    fun urlBodega(pregunta: String): String? {
        val tok = api.token ?: return null
        val q = pregunta.trim()
        val consulta = if (q.isEmpty()) "" else "&q=" + q.encodeURLParameter()
        return "$SERVIDOR_ANAQUEL/panel/bodega?app=1$consulta#t=$tok"
    }

    // ---------------- Red, tratos y asesor ----------------

    private suspend fun cargarAsesor() {
        val c = api.asesor()
        _state.update { it.copy(asesor = c.mensajes, motorAsesor = c.motor) }
    }

    fun preguntar(texto: String) {
        val q = texto.trim()
        if (q.isEmpty() || _state.value.pensando != null) return
        _state.update { it.copy(pensando = q) }
        viewModelScope.launch {
            try {
                val r = api.preguntar(q)
                _state.update { it.copy(asesor = it.asesor.orEmpty() + r.pregunta + r.respuesta) }
                runCatching { _state.update { it.copy(resumen = api.resumen()) } }
            } catch (e: Exception) {
                mensaje = e.message ?: "El asesor no pudo responder"
            } finally {
                _state.update { it.copy(pensando = null) }
            }
        }
    }

    fun conversacionNueva() = trabajo("asesor") {
        api.borrarAsesor()
        _state.update { it.copy(asesor = emptyList()) }
    }

    fun buscarRed(q: String) = trabajo("red") {
        _state.update { it.copy(consultaRed = q) }
        val r = api.red(q)
        _state.update { it.copy(red = r) }
    }

    /** Desde una tarjeta del asesor: ir a la red con esa búsqueda. */
    fun verEnRed(q: String) {
        _state.update { it.copy(seccion = SeccionAnaquel.RED, red = null) }
        buscarRed(q)
    }

    fun negociar(r: ResultadoRed, cantidad: Long, precioC: Long, mensajeTexto: String) = trabajo("trato") {
        val t = api.crearTrato(r, cantidad, precioC, mensajeTexto)
        mensaje = if (r.tipo == "solicitud") "Oferta enviada" else "Propuesta enviada. Te avisamos cuando responda."
        _state.update { it.copy(seccion = SeccionAnaquel.TRATOS, trato = t, tratos = api.tratos()) }
    }

    fun abrirTrato(t: Trato) = trabajo("trato") { _state.update { it.copy(trato = api.trato(t.id)) } }
    fun cerrarTrato() = trabajo(null) { _state.update { it.copy(trato = null, tratos = api.tratos()) } }

    fun escribirTrato(texto: String, cantidad: Long? = null, precioC: Long? = null) {
        val t = _state.value.trato ?: return
        if (texto.isBlank() && cantidad == null) return
        trabajo("trato") { _state.update { it.copy(trato = api.mensajeTrato(t.id, texto.trim(), cantidad, precioC)) } }
    }

    fun accionTrato(accion: String) {
        val t = _state.value.trato ?: return
        trabajo("trato") {
            val n = api.accionTrato(t.id, accion)
            _state.update { it.copy(trato = n) }
            mensaje = when (accion) {
                "aceptar" -> "¡Trato aceptado!"
                "entregar" -> "Entrega registrada en el inventario de las dos tiendas"
                else -> "Trato cancelado"
            }
            runCatching { _state.update { it.copy(resumen = api.resumen()) } }
        }
    }

    fun abrirWhatsApp(enlace: String) { abrirEnlace = enlace }

    fun refrescar() = trabajo(null) { refrescarAhora() }

    private suspend fun refrescarAhora() {
        if (!sincronizado) {
            runCatching { subirInventario() }.onSuccess { sincronizado = true }
        }
        val r = api.resumen()
        val f = api.faltantes()
        val a = api.anaqueles()
        val p = api.pedidos()
        val tr = if (_state.value.seccion == SeccionAnaquel.TRATOS) runCatching { api.tratos() }.getOrNull() else null
        val ini = runCatching { api.inicio() }.getOrNull()
        _state.update { st ->
            st.copy(
                inicio = ini ?: st.inicio,
                resumen = r, faltantes = f, anaqueles = a, pedidos = p, error = null, tratos = tr ?: st.tratos,
                abierto = st.abierto?.let { ab -> a.firstOrNull { it.id == ab.id } ?: ab },
            )
        }
    }

    /** Sube una copia del inventario local (nombre, código, stock) a la nube. */
    private suspend fun subirInventario(): SyncResultado {
        val locales = productos.observeProducts().first()
        return api.sincronizar(
            locales.map {
                ProductoNube(
                    codigo = it.barcode, nombre = it.name, categoria = it.category?.name.orEmpty(),
                    precioC = it.priceCents, stock = it.stock.toLong(), stockMin = it.minStock.toLong(),
                )
            },
        )
    }

    fun sincronizarInventario() = trabajo("sync") {
        val r = subirInventario()
        sincronizado = true
        mensaje = "Inventario sincronizado: ${r.creados} nuevos, ${r.actualizados} actualizados"
        refrescarAhora()
    }

    // ---------------- Vender al mayoreo ----------------

    fun cargarMayoreo() = trabajo(if (_state.value.mayoreo == null) "mayoreo" else null) {
        var m = api.mayoreo()
        // Si aún no ha subido su inventario, se sube solo para poder publicarlo.
        if (m.productos.isEmpty()) {
            subirInventario()
            m = api.mayoreo()
        }
        _state.update { it.copy(mayoreo = m) }
    }

    fun guardarMayoreo(id: Long, c: CambioMayoreo, listo: () -> Unit = {}) = trabajo("mayoreo-guardar") {
        val m = api.guardarMayoreo(id, c)
        _state.update { it.copy(mayoreo = m) }
        val n = c.elegidos.size
        mensaje = when {
            !c.publicado -> "Lo quitaste de la red"
            c.audiencia == "elegidos" -> "Publicado solo para ${if (n == 1) "1 tienda" else "$n tiendas"}"
            else -> "Publicado para toda la red"
        }
        listo()
    }

    // ---------------- Inicio ----------------

    fun cargarInicio() = trabajo(if (_state.value.inicio == null) "inicio" else null) {
        val i = api.inicio()
        _state.update { it.copy(inicio = i) }
    }

    /** Búsqueda desde Inicio: los resultados se ven ahí mismo. */
    fun buscarDesdeInicio(q: String) {
        if (q.isBlank()) { _state.update { it.copy(consultaRed = "", red = null) }; return }
        buscarRed(q)
    }

    // ---------------- Chats ----------------

    fun cargarChats() = trabajo(if (_state.value.chats == null) "chats" else null) {
        _state.update { it.copy(chats = api.chats()) }
    }

    fun verPerfil(id: Long) = trabajo("perfil") { _state.update { it.copy(perfil = api.perfil(id)) } }
    fun cerrarPerfil() = _state.update { it.copy(perfil = null) }

    fun abrirChat(id: Long) = trabajo("chat") { _state.update { it.copy(chat = api.chat(id)) } }

    /**
     * Abre (o crea) la conversación con una tienda. Con [pedir] se abre además
     * su catálogo para armar el pedido; [producto] llega con una cantidad sugerida.
     */
    fun chatCon(tienda: Long, pedir: Boolean = false, producto: Long? = null, cantidad: Long = 0) = trabajo("chat") {
        val c = api.abrirChat(tienda)
        _state.update { it.copy(chat = c) }
        if (pedir) {
            val items = api.catalogo(tienda)
            if (items.isEmpty()) {
                mensaje = "${c.otra.nombre} no tiene productos en su vitrina. Escríbele lo que necesitas."
            } else {
                val ini = if (producto != null && items.any { it.productoId == producto }) mapOf(producto to cantidad.coerceAtLeast(1)) else emptyMap()
                _state.update { it.copy(pidiendo = PedidoEnCurso(c.id, c.otra.nombre, null, items, ini)) }
            }
        }
    }

    fun cerrarChat() {
        _state.update { it.copy(chat = null, pidiendo = null) }
        cargarChats()
    }

    /** Recarga el chat abierto sin bloquear la pantalla (para ver respuestas nuevas). */
    fun refrescarChat() {
        val c = _state.value.chat ?: return
        viewModelScope.launch {
            runCatching { api.chat(c.id) }.onSuccess { n -> _state.update { if (it.chat?.id == n.id) it.copy(chat = n) else it } }
        }
    }

    fun escribirChat(texto: String) {
        val c = _state.value.chat ?: return
        if (texto.isBlank()) return
        trabajo("enviar") { _state.update { it.copy(chat = api.escribirChat(c.id, texto.trim())) } }
    }

    fun cargarMiCatalogo() = trabajo("catalogo") { _state.update { it.copy(miCatalogo = api.miCatalogo()) } }

    /** Manda mi catálogo (todo, o solo [productos]). */
    fun mandarCatalogo(productos: List<Long>?) {
        val c = _state.value.chat ?: return
        trabajo("enviar") {
            _state.update { it.copy(chat = api.mandarCatalogo(c.id, productos)) }
            mensaje = "Catálogo enviado"
        }
    }

    /** Desde una tarjeta de catálogo del chat: armar el pedido con esos productos. */
    fun pedirDeCatalogo(m: MensajeChat) {
        val c = _state.value.chat ?: return
        val items = m.items.filter { it.sigue }
        if (items.isEmpty()) { mensaje = "Esos productos ya no están en su vitrina"; return }
        _state.update { it.copy(pidiendo = PedidoEnCurso(c.id, c.otra.nombre, m.id, items)) }
    }

    fun cambiarCantidadPedido(producto: Long, n: Long) = _state.update { st ->
        val p = st.pidiendo ?: return@update st
        val tope = p.items.firstOrNull { it.productoId == producto }?.disponible ?: Long.MAX_VALUE
        val v = n.coerceIn(0, tope)
        st.copy(pidiendo = p.copy(cantidades = if (v == 0L) p.cantidades - producto else p.cantidades + (producto to v)))
    }

    fun cancelarPedido() = _state.update { it.copy(pidiendo = null) }

    fun enviarPedido(texto: String) {
        val p = _state.value.pidiendo ?: return
        val items = p.cantidades.filterValues { it > 0 }.toList()
        if (items.isEmpty()) { mensaje = "Pon la cantidad de al menos un producto"; return }
        trabajo("pedido") {
            val c = api.pedirEnChat(p.chatId, p.catalogo, items, texto.trim())
            _state.update { it.copy(chat = c, pidiendo = null) }
            mensaje = "Pedido enviado a ${p.tienda}. Te avisamos cuando lo acepte."
        }
    }

    fun accionPedidoChat(m: MensajeChat, accion: String) {
        val c = _state.value.chat ?: return
        trabajo("pedido${m.id}") {
            _state.update { it.copy(chat = api.accionPedidoChat(c.id, m.id, accion)) }
            mensaje = when (accion) {
                "aceptar" -> "Pedido aceptado"; "entregar" -> "Marcado como entregado: el inventario ya se movió"
                else -> "Pedido cancelado"
            }
        }
    }

    // ---------------- Resurtido y pedidos ----------------

    fun grupos(st: AnaquelUiState): List<GrupoPedido> {
        val porPedir = st.faltantes.orEmpty().filter { it.enPedido == null }
        return porPedir
            .groupBy { f -> st.eleccion[f.producto.id] ?: f.sugerencias.firstOrNull()?.clave ?: SIN }
            .map { (k, items) -> GrupoPedido(k, items.first().sugerencias.firstOrNull { it.clave == k }, items) }
            .sortedBy { it.clave == SIN }
    }

    fun elegir(producto: Long, clave: String) = _state.update { it.copy(eleccion = it.eleccion + (producto to clave)) }

    fun cantidad(f: Faltante): Long = _state.value.cantidades[f.producto.id] ?: f.sugerido

    fun cambiarCantidad(producto: Long, delta: Int) = _state.update { st ->
        val f = st.faltantes?.firstOrNull { it.producto.id == producto } ?: return@update st
        val actual = st.cantidades[producto] ?: f.sugerido
        st.copy(cantidades = st.cantidades + (producto to (actual + delta).coerceAtLeast(1)))
    }

    fun pedir(g: GrupoPedido) {
        val s = g.sugerencia ?: return
        trabajo(g.clave) {
            val p = api.pedir(s, g.items.map { it.producto.id to cantidad(it) })
            abrirEnlace = p.whatsapp
            mensaje = "Pedido #${p.id} listo para ${p.destino}"
            refrescarAhora()
        }
    }

    fun cambiarEstado(p: Pedido, estado: String) = trabajo("pedido${p.id}") {
        api.cambiarEstado(p.id, estado)
        refrescarAhora()
    }

    fun reenviar(p: Pedido) { abrirEnlace = p.whatsapp }

    // ---------------- Anaqueles ----------------

    fun abrir(a: Anaquel) = _state.update { it.copy(abierto = a, resultado = null, editandoZonas = false) }
    fun cerrar() = _state.update { it.copy(abierto = null, resultado = null, editandoZonas = false) }

    fun crearAnaquel(nombre: String, rutaFoto: String) = trabajo("crear") {
        val a = api.crearAnaquel(nombre.ifBlank { "Anaquel ${(_state.value.anaqueles?.size ?: 0) + 1}" }, readFileBytes(rutaFoto))
        refrescarAhora()
        editarZonas(a)
        mensaje = "Ahora dibuja un recuadro sobre cada producto"
    }

    fun fotoDelDia(rutaFoto: String) {
        val a = _state.value.abierto ?: return
        trabajo("foto") {
            val r = api.fotoDelDia(a.id, readFileBytes(rutaFoto))
            _state.update { it.copy(abierto = r.anaquel, resultado = r) }
            refrescarAhora()
        }
    }

    fun nuevaReferencia(rutaFoto: String) {
        val a = _state.value.abierto ?: return
        trabajo("foto") {
            val n = api.nuevaReferencia(a.id, readFileBytes(rutaFoto))
            mensaje = "Foto de referencia cambiada. Revisa que las zonas sigan en su lugar."
            editarZonas(n)
        }
    }

    fun editarZonas(a: Anaquel? = _state.value.abierto) = trabajo("zonas") {
        if (a == null) return@trabajo
        val prods = api.productos()
        _state.update { it.copy(abierto = a, editandoZonas = true, productosNube = prods, resultado = null) }
    }

    fun cancelarZonas() = _state.update { it.copy(editandoZonas = false) }

    fun guardarZonas(zonas: List<Zona>) {
        val a = _state.value.abierto ?: return
        trabajo("zonas") {
            val n = api.guardarZonas(a.id, zonas.map { if (it.id < 0) it.copy(id = 0) else it })
            _state.update { it.copy(abierto = n, editandoZonas = false) }
            mensaje = "Zonas guardadas"
            refrescarAhora()
        }
    }

    fun renombrar(nombre: String) {
        val a = _state.value.abierto ?: return
        if (nombre.isBlank()) return
        trabajo("renombrar") {
            _state.update { it.copy(abierto = api.renombrar(a.id, nombre.trim())) }
            refrescarAhora()
        }
    }

    fun borrarAnaquel() {
        val a = _state.value.abierto ?: return
        trabajo("borrar") {
            api.borrarAnaquel(a.id)
            _state.update { it.copy(abierto = null) }
            refrescarAhora()
        }
    }

    suspend fun foto(ruta: String): ByteArray = api.bajarFoto(ruta)

    // ---------------- utilidades ----------------

    private fun trabajo(etiqueta: String?, bloque: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(ocupado = etiqueta ?: it.ocupado) }
            try {
                bloque()
            } catch (e: AnaquelError) {
                if (e.sesionVencida && _state.value.tienda != null) {
                    salir()
                    mensaje = "Tu sesión expiró, vuelve a entrar"
                } else {
                    mensaje = e.message
                    _state.update { it.copy(error = e.message) }
                }
            } catch (e: Exception) {
                mensaje = e.message ?: "Algo salió mal"
            } finally {
                _state.update { it.copy(ocupado = null) }
            }
        }
    }

    companion object {
        const val SIN = "ninguno"
        private const val CLAVE_TOKEN = "anaquel.token"
        private const val CLAVE_TIENDA = "anaquel.tienda"
        private const val CLAVE_SLUG = "anaquel.slug"
    }
}
