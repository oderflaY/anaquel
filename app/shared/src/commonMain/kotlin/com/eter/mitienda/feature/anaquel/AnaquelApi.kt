package com.eter.mitienda.feature.anaquel

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLQueryComponent
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Servidor de Anaquel. El puerto 443 pasa los filtros de red de la escuela. */
const val SERVIDOR_ANAQUEL = "https://2.25.68.28/anaquel"

// ---------------- Modelos (mismos nombres que la API) ----------------

@Serializable
data class TiendaNube(
    val id: Long, val nombre: String, val slug: String, val email: String? = null,
    val telefono: String = "", val direccion: String = "", val lat: Double? = null, val lng: Double? = null,
)

@Serializable
data class Sesion(val token: String, val tienda: TiendaNube)

@Serializable
data class ProductoNube(
    val id: Long = 0, val codigo: String = "", val nombre: String, val categoria: String = "",
    @SerialName("precio_c") val precioC: Long = 0, val stock: Long? = null,
    @SerialName("stock_min") val stockMin: Long = 0,
)

@Serializable
data class Zona(
    val id: Long = 0,
    @SerialName("producto_id") val productoId: Long? = null,
    val producto: String = "",
    val x: Double, val y: Double, val w: Double, val h: Double,
    val nivel: String = "lleno", val score: Double = 1.0,
)

@Serializable
data class Anaquel(
    val id: Long, val nombre: String,
    @SerialName("foto_ref") val fotoRef: String,
    @SerialName("ultima_foto") val ultimaFoto: String? = null,
    @SerialName("ultima_en") val ultimaEn: Long? = null,
    val zonas: List<Zona> = emptyList(),
    val faltantes: Int = 0,
)

@Serializable
data class Sugerencia(
    val tipo: String, val id: Long, val nombre: String, val telefono: String = "",
    val lugar: String? = null, @SerialName("distancia_km") val distanciaKm: Double? = null, val razon: String = "",
) {
    val clave: String get() = "$tipo:$id"
}

@Serializable
data class Faltante(
    val producto: ProductoNube, val nivel: String, val motivo: String,
    @SerialName("en_pedido") val enPedido: Long? = null,
    val sugerido: Long = 1,
    val sugerencias: List<Sugerencia> = emptyList(),
)

@Serializable
data class ItemPedido(
    @SerialName("producto_id") val productoId: Long? = null, val nombre: String,
    val cantidad: Long, val recibido: Boolean = false,
)

@Serializable
data class Pedido(
    val id: Long, val tipo: String, val destino: String, val telefono: String = "",
    val estado: String, val nota: String = "", val creado: Long, val actualizado: Long,
    @SerialName("llego_auto") val llegoAuto: Boolean = false,
    val atrasado: Boolean = false, val dias: Int = 0,
    val items: List<ItemPedido> = emptyList(),
    val mensaje: String = "", val whatsapp: String = "",
) {
    val abierto: Boolean get() = estado == "enviado" || estado == "confirmado"
}

@Serializable
data class Resumen(
    val faltantes: Int = 0, @SerialName("sin_pedir") val sinPedir: Int = 0,
    @SerialName("pedidos_abiertos") val pedidosAbiertos: Int = 0, val atrasados: Int = 0,
    val anaqueles: Int = 0, @SerialName("sin_foto_hoy") val sinFotoHoy: Int = 0, val productos: Int = 0,
    val avisos: Int = 0, @SerialName("tratos_pendientes") val tratosPendientes: Int = 0,
    @SerialName("chats_sin_leer") val chatsSinLeer: Int = 0,
)

// ---------------- Red de comercio local ----------------

@Serializable
data class TiendaCorta(val id: Long, val nombre: String, val slug: String = "", val giro: String = "", val telefono: String = "")

/** Un resultado de la red: producto en vitrina, anuncio, solicitud ("busca…") o productor. */
@Serializable
data class ResultadoRed(
    val tipo: String, val id: Long, val titulo: String, val detalle: String = "",
    val subtipo: String? = null, @SerialName("precio_c") val precioC: Long? = null,
    @SerialName("precio_mayoreo_c") val precioMayoreoC: Long? = null,
    @SerialName("minimo_mayoreo") val minimoMayoreo: Long? = null, val unidad: String? = null,
    val disponible: Long? = null, val precio: String? = null, val cantidad: Long? = null,
    val tienda: TiendaCorta? = null, val telefono: String? = null, val lugar: String? = null,
    @SerialName("distancia_km") val distanciaKm: Double? = null,
    /** Solo en Inicio: por qué te conviene y cuánto ganas por pieza si lo revendes. */
    val motivo: String? = null,
    @SerialName("ganancia_pct") val gananciaPct: Long? = null,
) {
    val clave: String get() = "$tipo:$id"
    val negociable: Boolean get() = tipo != "productor" && tienda != null
}

// ---------------- Inicio ----------------

/** Producto que se te acaba y con quién surtirlo. */
@Serializable
data class SurtirInicio(
    @SerialName("producto_id") val productoId: Long, val nombre: String, val stock: Long? = null,
    @SerialName("stock_min") val stockMin: Long = 0, val unidad: String = "pza", val nivel: String = "",
    @SerialName("mi_precio_c") val miPrecioC: Long = 0, val ofertas: List<ResultadoRed> = emptyList(),
)

@Serializable
data class TiendaZona(
    val id: Long, val nombre: String, val slug: String = "", val giro: String = "", val telefono: String = "",
    val descripcion: String = "", @SerialName("distancia_km") val distanciaKm: Double? = null,
    val productos: Int = 0, @SerialName("chat_id") val chatId: Long? = null,
)

@Serializable
data class ProveedorGrande(
    val id: Long, val nombre: String, val slug: String = "", val giro: String = "", val telefono: String = "",
    val descripcion: String = "", @SerialName("distancia_km") val distanciaKm: Double? = null,
    val productos: Int = 0, @SerialName("chat_id") val chatId: Long? = null,
    val afines: Int = 0, val destacados: List<ResultadoRed> = emptyList(), val promocion: String? = null,
)

@Serializable
data class Inicio(
    val saludo: String = "",
    val proveedores: List<ProveedorGrande> = emptyList(),
    @SerialName("para_surtir") val paraSurtir: List<SurtirInicio> = emptyList(),
    @SerialName("te_convienen") val convienen: List<ResultadoRed> = emptyList(),
    val promociones: List<ResultadoRed> = emptyList(),
    val buscan: List<ResultadoRed> = emptyList(),
    val tiendas: List<TiendaZona> = emptyList(),
    @SerialName("chats_sin_leer") val chatsSinLeer: Int = 0,
    @SerialName("tratos_pendientes") val pendientes: Int = 0,
)

// ---------------- Chats con proveedores y tiendas ----------------

@Serializable
data class ItemCatalogo(
    @SerialName("producto_id") val productoId: Long, val nombre: String, val categoria: String = "",
    val unidad: String = "pza", @SerialName("precio_c") val precioC: Long = 0,
    @SerialName("precio_mayoreo_c") val precioMayoreoC: Long = 0, @SerialName("minimo_mayoreo") val minimoMayoreo: Long = 0,
    val disponible: Long? = null, val sigue: Boolean = true,
) {
    /** Precio por pieza según cuántas pidas (mayoreo desde el mínimo). */
    fun precioPara(n: Long): Long = if (precioMayoreoC > 0 && minimoMayoreo > 0 && n >= minimoMayoreo) precioMayoreoC else precioC
}

@Serializable
data class TratoChat(val id: Long, val titulo: String, val cantidad: Long, @SerialName("precio_c") val precioC: Long, val estado: String)

@Serializable
data class MensajeChat(
    val id: Long, val de: String, val tipo: String, val texto: String = "",
    val items: List<ItemCatalogo> = emptyList(), val tratos: List<TratoChat> = emptyList(),
    val total: Long = 0, val rol: String = "", val estado: String = "", val puedo: List<String> = emptyList(),
    val creado: Long = 0,
)

@Serializable
data class Chat(
    val id: Long, val otra: TiendaCorta, val ultimo: String = "", @SerialName("ultimo_mio") val ultimoMio: Boolean = false,
    val actualizado: Long = 0, @SerialName("sin_leer") val sinLeer: Int = 0, val pendientes: Int = 0,
    val mensajes: List<MensajeChat> = emptyList(), val whatsapp: String? = null,
    @SerialName("distancia_km") val distanciaKm: Double? = null,
)

@Serializable
private data class ChatNuevo(@SerialName("tienda_id") val tiendaId: Long)

@Serializable
private data class EscribirChat(
    val texto: String = "", val catalogo: List<Long>? = null, @SerialName("catalogo_todo") val catalogoTodo: Boolean = false,
)

@Serializable
private data class PedidoChat(val catalogo: Long? = null, val items: List<ItemNuevo>, val texto: String = "")

@Serializable
data class MensajeTrato(
    val id: Long, val de: String, val texto: String,
    val cantidad: Long? = null, @SerialName("precio_c") val precioC: Long? = null, val creado: Long = 0,
)

@Serializable
data class Trato(
    val id: Long, val rol: String, val otra: TiendaCorta, val titulo: String, val cantidad: Long,
    @SerialName("precio_c") val precioC: Long, val estado: String,
    @SerialName("te_toca") val teToca: Boolean = false, val disponible: Long? = null,
    val actualizado: Long = 0, val ultimo: String? = null,
    val mensajes: List<MensajeTrato> = emptyList(), val whatsapp: String? = null,
) {
    val abierto: Boolean get() = estado == "negociando" || estado == "aceptado"
}

@Serializable
data class MensajeAsesor(
    val id: Long = 0, val rol: String, val texto: String,
    val tarjetas: List<ResultadoRed> = emptyList(), val pasos: List<String> = emptyList(),
    val motor: String? = null,
)

@Serializable
data class ConversacionAsesor(val mensajes: List<MensajeAsesor> = emptyList(), val motor: String = "basico")

@Serializable
data class RespuestaAsesor(val pregunta: MensajeAsesor, val respuesta: MensajeAsesor)

@Serializable
private data class Pregunta(val mensaje: String)

@Serializable
private data class TratoNuevo(
    @SerialName("producto_id") val productoId: Long? = null,
    @SerialName("anuncio_id") val anuncioId: Long? = null,
    @SerialName("solicitud_id") val solicitudId: Long? = null,
    val cantidad: Long, @SerialName("precio_c") val precioC: Long, val mensaje: String = "",
)

@Serializable
private data class MensajeNuevo(val texto: String = "", val cantidad: Long? = null, @SerialName("precio_c") val precioC: Long? = null)

@Serializable
private class Vacio

@Serializable
data class ResultadoFoto(
    val anaquel: Anaquel, val metodo: String,
    val faltantes: List<Zona> = emptyList(), val llegaron: List<Pedido> = emptyList(),
)

@Serializable
private data class Credenciales(val email: String, val password: String)

@Serializable
private data class Registro(val nombre: String, val email: String, val password: String, val telefono: String = "")

@Serializable
private data class SyncProductos(val productos: List<ProductoNube>)

@Serializable
data class SyncResultado(val creados: Int = 0, val actualizados: Int = 0)

@Serializable
private data class ItemNuevo(@SerialName("producto_id") val productoId: Long, val cantidad: Long)

@Serializable
private data class PedidoNuevo(
    @SerialName("proveedor_id") val proveedorId: Long? = null,
    @SerialName("productor_id") val productorId: Long? = null,
    val items: List<ItemNuevo>,
)

@Serializable
private data class CambioEstado(val estado: String)

@Serializable
private data class Nombre(val nombre: String)

/** Error que ya viene explicado por el servidor (o "sin conexión"). */
class AnaquelError(message: String, val sesionVencida: Boolean = false) : Exception(message)

// ---------------- Cliente ----------------

class AnaquelApi(private val base: String = SERVIDOR_ANAQUEL) {
    var token: String? = null

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val http = HttpClient {
        install(HttpTimeout) {
            // El asesor con IA puede tardar (busca en la red varias veces).
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
        }
        expectSuccess = false
    }

    /** URL absoluta de una foto ("/media/x.jpg" → https://…/anaquel/media/x.jpg). */
    fun media(ruta: String) = base + ruta

    private suspend fun llamar(metodo: HttpMethod, ruta: String, cuerpo: String? = null): String {
        val r: HttpResponse = try {
            http.request(base + "/api" + ruta) {
                method = metodo
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (cuerpo != null) {
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo)
                }
            }
        } catch (e: Exception) {
            throw AnaquelError("Sin conexión con el servidor. Revisa tu internet.")
        }
        return revisar(r)
    }

    private suspend fun revisar(r: HttpResponse): String {
        val texto = r.bodyAsText()
        if (!r.status.isSuccess()) {
            val msg = runCatching { json.parseToJsonElement(texto).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw AnaquelError(msg ?: "Error del servidor (${r.status.value})", sesionVencida = r.status.value == 401)
        }
        return texto
    }

    private suspend inline fun <reified T> get(ruta: String): T = json.decodeFromString(llamar(HttpMethod.Get, ruta))
    private suspend inline fun <reified T, reified C> post(ruta: String, c: C): T =
        json.decodeFromString(llamar(HttpMethod.Post, ruta, json.encodeToString(c)))
    private suspend inline fun <reified T, reified C> put(ruta: String, c: C): T =
        json.decodeFromString(llamar(HttpMethod.Put, ruta, json.encodeToString(c)))

    suspend fun entrar(email: String, password: String): Sesion = post("/login", Credenciales(email.trim(), password))
    suspend fun registrar(nombre: String, email: String, password: String, telefono: String): Sesion =
        post("/registro", Registro(nombre.trim(), email.trim(), password, telefono.trim()))

    suspend fun reiniciarDemo(): Sesion = json.decodeFromString(llamar(HttpMethod.Post, "/demo/reiniciar"))
    suspend fun resumen(): Resumen = get("/resumen")
    suspend fun anaqueles(): List<Anaquel> = get("/anaqueles")
    suspend fun anaquel(id: Long): Anaquel = get("/anaqueles/$id")
    suspend fun faltantes(): List<Faltante> = get("/faltantes")
    suspend fun pedidos(): List<Pedido> = get("/pedidos")
    suspend fun productos(): List<ProductoNube> = get("/productos")
    suspend fun sincronizar(productos: List<ProductoNube>): SyncResultado = post("/productos/sync", SyncProductos(productos))
    suspend fun guardarZonas(id: Long, zonas: List<Zona>): Anaquel = put("/anaqueles/$id/zonas", zonas)
    suspend fun renombrar(id: Long, nombre: String): Anaquel = put("/anaqueles/$id", Nombre(nombre))
    suspend fun borrarAnaquel(id: Long) { llamar(HttpMethod.Delete, "/anaqueles/$id") }

    // Red, tratos y asesor
    suspend fun red(q: String): List<ResultadoRed> = get("/red?q=" + q.trim().encodeURLQueryComponent())
    suspend fun tratos(): List<Trato> = get("/tratos")
    suspend fun trato(id: Long): Trato = get("/tratos/$id")
    suspend fun crearTrato(r: ResultadoRed, cantidad: Long, precioC: Long, mensaje: String): Trato = post(
        "/tratos",
        TratoNuevo(
            productoId = r.id.takeIf { r.tipo == "producto" }, anuncioId = r.id.takeIf { r.tipo == "anuncio" },
            solicitudId = r.id.takeIf { r.tipo == "solicitud" }, cantidad = cantidad, precioC = precioC, mensaje = mensaje,
        ),
    )
    suspend fun mensajeTrato(id: Long, texto: String, cantidad: Long? = null, precioC: Long? = null): Trato =
        post("/tratos/$id/mensajes", MensajeNuevo(texto, cantidad, precioC))
    /** aceptar | entregar | cancelar */
    suspend fun accionTrato(id: Long, accion: String): Trato = post("/tratos/$id/$accion", Vacio())
    suspend fun asesor(): ConversacionAsesor = get("/asesor")
    suspend fun preguntar(m: String): RespuestaAsesor = post("/asesor", Pregunta(m))
    suspend fun borrarAsesor() { llamar(HttpMethod.Delete, "/asesor") }

    // Inicio y chats
    suspend fun inicio(): Inicio = get("/inicio")
    suspend fun chats(): List<Chat> = get("/chats")
    suspend fun chat(id: Long): Chat = get("/chats/$id")
    suspend fun abrirChat(tienda: Long): Chat = post("/chats", ChatNuevo(tienda))
    suspend fun escribirChat(id: Long, texto: String): Chat = post("/chats/$id/mensajes", EscribirChat(texto = texto))
    suspend fun mandarCatalogo(id: Long, productos: List<Long>?): Chat =
        post("/chats/$id/mensajes", EscribirChat(catalogo = productos, catalogoTodo = productos == null))
    suspend fun pedirEnChat(id: Long, catalogo: Long?, items: List<Pair<Long, Long>>, texto: String = ""): Chat =
        post("/chats/$id/pedido", PedidoChat(catalogo, items.map { (p, c) -> ItemNuevo(p, c) }, texto))
    /** aceptar | entregar | cancelar */
    suspend fun accionPedidoChat(id: Long, mensaje: Long, accion: String): Chat = post("/chats/$id/pedido/$mensaje/$accion", Vacio())
    suspend fun catalogo(tienda: Long): List<ItemCatalogo> = get("/tiendas/$tienda/catalogo")
    suspend fun miCatalogo(): List<ItemCatalogo> = get("/tiendas/yo/catalogo")

    suspend fun cambiarEstado(pedido: Long, estado: String): Pedido = post("/pedidos/$pedido/estado", CambioEstado(estado))

    suspend fun pedir(s: Sugerencia, items: List<Pair<Long, Long>>): Pedido = post(
        "/pedidos",
        PedidoNuevo(
            proveedorId = s.id.takeIf { s.tipo == "proveedor" },
            productorId = s.id.takeIf { s.tipo == "productor" },
            items = items.map { (p, c) -> ItemNuevo(p, c) },
        ),
    )

    private suspend fun subirFoto(ruta: String, foto: ByteArray, nombre: String? = null): String {
        val r = try {
            http.submitFormWithBinaryData(
                url = base + "/api" + ruta,
                formData = formData {
                    if (nombre != null) append("nombre", nombre)
                    append("foto", foto, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"foto.jpg\"")
                    })
                },
            ) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        } catch (e: Exception) {
            throw AnaquelError("No se pudo subir la foto. Revisa tu internet.")
        }
        return revisar(r)
    }

    suspend fun crearAnaquel(nombre: String, foto: ByteArray): Anaquel =
        json.decodeFromString(subirFoto("/anaqueles", foto, nombre))

    suspend fun fotoDelDia(id: Long, foto: ByteArray): ResultadoFoto =
        json.decodeFromString(subirFoto("/anaqueles/$id/fotos", foto))

    suspend fun nuevaReferencia(id: Long, foto: ByteArray): Anaquel =
        json.decodeFromString(subirFoto("/anaqueles/$id/referencia", foto))

    /** Bytes de una foto (con caché en memoria: las fotos nunca cambian de nombre). */
    private val cache = mutableMapOf<String, ByteArray>()

    suspend fun bajarFoto(ruta: String): ByteArray {
        cache[ruta]?.let { return it }
        val r = try { http.get(media(ruta)) } catch (e: Exception) { throw AnaquelError("Sin conexión") }
        if (!r.status.isSuccess()) throw AnaquelError("No se encontró la foto")
        return r.readRawBytes().also { if (cache.size > 30) cache.clear(); cache[ruta] = it }
    }
}
