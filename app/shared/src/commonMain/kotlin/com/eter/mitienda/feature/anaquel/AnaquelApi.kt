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
)

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
            requestTimeoutMillis = 60_000
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
