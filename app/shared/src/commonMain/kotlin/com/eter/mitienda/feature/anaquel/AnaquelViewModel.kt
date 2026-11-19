package com.eter.mitienda.feature.anaquel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.core.util.readFileBytes
import com.eter.mitienda.data.local.AjustesDao
import com.eter.mitienda.domain.repository.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SeccionAnaquel(val titulo: String) { HOY("Hoy"), ANAQUELES("Anaqueles"), PEDIDOS("Pedidos") }

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
)

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
    fun entrarDemo() = conSesion { api.entrar("demo@anaquel.mx", "demo1234") }
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

    fun reiniciarDemo() = conSesion { api.reiniciarDemo() }

    fun salir() = viewModelScope.launch {
        api.token = null
        ajustes.guardar(CLAVE_TOKEN, null)
        _state.value = AnaquelUiState(revisandoSesion = false)
    }

    // ---------------- Datos ----------------

    fun elegirSeccion(s: SeccionAnaquel) = _state.update { it.copy(seccion = s) }

    fun refrescar() = trabajo(null) { refrescarAhora() }

    private suspend fun refrescarAhora() {
        if (!sincronizado) {
            runCatching { subirInventario() }.onSuccess { sincronizado = true }
        }
        val r = api.resumen()
        val f = api.faltantes()
        val a = api.anaqueles()
        val p = api.pedidos()
        _state.update { st ->
            st.copy(
                resumen = r, faltantes = f, anaqueles = a, pedidos = p, error = null,
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
