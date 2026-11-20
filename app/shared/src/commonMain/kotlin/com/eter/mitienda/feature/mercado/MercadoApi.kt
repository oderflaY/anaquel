package com.eter.mitienda.feature.mercado

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/*
 * Cliente de los servicios del mercado (los mismos que usa la web):
 * /api/acceso, /api/mercado, /api/reels y /api/pagos detrás de la pasarela.
 *
 * La app se identifica con "X-Cliente: app-anaquel" al entrar y recibe un token
 * que guarda cifrado (ver AlmacenSeguro) y manda como "Authorization: Bearer".
 */

// ---------------- Modelos ----------------

@Serializable
data class Cuenta(
    val id: Long, val nombre: String, val slug: String, val email: String = "", val telefono: String = "",
    val giro: String = "", val descripcion: String = "", val colonia: String = "",
    val rol: String = "cliente", val estado: String = "pendiente", val vende: Boolean = false,
    val demo: Boolean = false, @SerialName("tienda_slug") val tiendaSlug: String? = null,
) {
    val verificada get() = estado == "verificada"
}

@Serializable data class CuentaDemo(val email: String, val nombre: String, val que: String)
@Serializable data class ConfigAcceso(val demo: Boolean = false, @SerialName("cuentas_demo") val cuentasDemo: List<CuentaDemo> = emptyList())
@Serializable data class RespSesion(val cuenta: Cuenta, val token: String? = null)
@Serializable data class RespSesionActual(val cuenta: Cuenta? = null)
@Serializable data class Categoria(val clave: String, val nombre: String, val icono: String)
@Serializable data class Foto(val id: Long? = null, val url: String, val alt: String, val ancho: Int = 0, val alto: Int = 0)
@Serializable data class VendedorCorto(val nombre: String, val slug: String, val colonia: String = "")

@Serializable
data class Publicacion(
    val id: Long, val titulo: String, val descripcion: String? = null, val categoria: String,
    @SerialName("precio_c") val precioC: Long, val unidad: String, val existencias: Int? = null,
    val minimo: Int = 1, val estado: String = "activa", val foto: Foto? = null, val fotos: List<Foto>? = null,
    val vendedor: VendedorCorto, @SerialName("distancia_km") val distanciaKm: Double? = null,
    @SerialName("es_mia") val esMia: Boolean = false,
)

@Serializable
data class Vendedor(
    val nombre: String, val slug: String, val colonia: String = "", val giro: String = "", val descripcion: String = "",
    @SerialName("tienda_slug") val tiendaSlug: String? = null,
)

@Serializable data class DetallePublicacion(val publicacion: Publicacion, val vendedor: Vendedor)
@Serializable data class PerfilVendedor(val vendedor: Vendedor, val publicaciones: Int)
@Serializable data class Pagina<T>(val items: List<T>, val siguiente: Long? = null)

@Serializable data class EventoOrden(val estado: String, val por: String, val nota: String = "", val creado: String)

@Serializable
data class Orden(
    val id: Long, val rol: String, val contraparte: String, @SerialName("contraparte_slug") val contraparteSlug: String,
    @SerialName("publicacion_id") val publicacionId: Long? = null, val titulo: String, val unidad: String,
    val cantidad: Int, @SerialName("precio_c") val precioC: Long, @SerialName("total_c") val totalC: Long,
    val nota: String = "", val estado: String, @SerialName("cobro_id") val cobroId: String? = null,
    val foto: Foto? = null, val creada: String, val actualizada: String, val eventos: List<EventoOrden>? = null,
)

@Serializable
data class Reel(
    val id: Long, val url: String, val descripcion: String, @SerialName("duracion_ms") val duracionMs: Int,
    val ancho: Int, val alto: Int, val vendedor: VendedorCorto, val publicacion: PubCorta? = null,
    @SerialName("es_mio") val esMio: Boolean = false,
)

@Serializable data class PubCorta(val id: Long, val titulo: String, @SerialName("precio_c") val precioC: Long, val unidad: String = "pieza")
@Serializable data class Proveedor(val clave: String, val nombre: String)
@Serializable data class Terminal(val id: Long, val proveedor: String, val marca: String, val nombre: String, val dispositivo: String)

@Serializable
data class Cobro(
    val id: String, @SerialName("orden_id") val ordenId: Long, val proveedor: String, val marca: String,
    val terminal: String, @SerialName("monto_c") val montoC: Long, val estado: String, val detalle: String = "",
)

@Serializable
data class Registro(
    val id: Long, @SerialName("monto_c") val montoC: Long, val proveedor: String, val marca: String, val terminal: String,
    val origen: String, @SerialName("orden_id") val ordenId: Long? = null, val detalle: String = "",
    @SerialName("pagado_en") val pagadoEn: String,
)

@Serializable data class Registros(val items: List<Registro>, @SerialName("hoy_c") val hoyC: Long = 0, @SerialName("hoy_cobros") val hoyCobros: Int = 0)
@Serializable data class TransaccionSim(val id: String, val terminal: String, @SerialName("monto_c") val montoC: Long, val concepto: String = "")
@Serializable data class Invitacion(val id: Long, val creada: String, val expira: String, val usada: String? = null, @SerialName("usada_por") val usadaPor: String? = null)
@Serializable data class CodigoInvitacion(val codigo: String, val expira: String)
@Serializable data class SesionActiva(val actual: Boolean, val creada: String, @SerialName("ultimo_uso") val ultimoUso: String, val agente: String, val tipo: String = "web")
@Serializable data class Id(val id: Long)

@Serializable
data class DatosPublicacion(
    val titulo: String, val descripcion: String, val categoria: String, @SerialName("precio_c") val precioC: Long,
    val unidad: String, val existencias: Int?, val minimo: Int,
)

/** Anuncio: el producto + datos de la promoción. */
data class Patrocinado(val promoId: Long, val plan: String, val mensaje: String, val publicacion: Publicacion)
data class Espacios(val banner: Patrocinado?, val productos: List<Patrocinado>)

@Serializable
data class Plan(
    val clave: String, val nombre: String, @SerialName("precio_semana_c") val precioSemanaC: Long,
    val banner: Boolean = false, @SerialName("en_busquedas") val enBusquedas: Boolean = false, val descripcion: String,
)

@Serializable data class PubPromo(val id: Long, val titulo: String, val foto: Foto? = null, val categoria: String = "otros")

@Serializable
data class MiPromo(
    val id: Long, val plan: String, @SerialName("plan_nombre") val planNombre: String, val mensaje: String = "",
    val publicacion: PubPromo, val semanas: Int, @SerialName("costo_c") val costoC: Long, val inicio: String, val fin: String,
    val estado: String, val vigente: Boolean, val impresiones: Long, val clics: Long,
)

/** Posiciones fijas de los patrocinados en la cuadrícula: 1, 9 y 17 (iguales que en la web). */
val POSICIONES_PATROCINADAS = listOf(0, 8, 16)

/** Intercala los patrocinados sin duplicar productos ni pasarse del final. */
fun intercalar(organicos: List<Publicacion>, anuncios: List<Patrocinado>): List<Pair<Publicacion, Patrocinado?>> {
    val ids = anuncios.map { it.publicacion.id }.toSet()
    val lista = organicos.filter { it.id !in ids }.map { it to null as Patrocinado? }.toMutableList()
    anuncios.forEachIndexed { i, a ->
        val pos = minOf(POSICIONES_PATROCINADAS.getOrElse(i) { lista.size }, lista.size)
        lista.add(pos, a.publicacion to a)
    }
    return lista
}

/** Error explicado por el servidor. [clave] permite decidir qué pantalla mostrar. */
class MercadoError(message: String, val codigo: Int = 0, val clave: String? = null) : Exception(message)

// ---------------- Cliente ----------------

class MercadoApi(base: String) {
    /** Dirección de la pasarela, p. ej. "http://10.0.2.2:8080/anaquel" o la del VPS. */
    var base: String = base.trimEnd('/')
    @kotlin.concurrent.Volatile var token: String? = null

    /** Se llama cuando el servidor dice que la sesión terminó o la cuenta no puede entrar. */
    var alPerderSesion: (String) -> Unit = {}

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val http = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000 // subir videos
            connectTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    fun url(ruta: String) = base + ruta

    private suspend fun revisar(r: HttpResponse): String {
        val texto = r.bodyAsText()
        if (!r.status.isSuccess()) {
            val obj = runCatching { json.parseToJsonElement(texto).jsonObject }.getOrNull()
            val msg = obj?.get("error")?.jsonPrimitive?.content
            val clave = obj?.get("codigo")?.jsonPrimitive?.content
            if (r.status.value == 401 || clave == "en_revision" || clave == "suspendida") alPerderSesion(clave ?: "sin_sesion")
            throw MercadoError(msg ?: "Algo salió mal (${r.status.value}). Intenta de nuevo.", r.status.value, clave)
        }
        return texto
    }

    suspend fun llamar(metodo: HttpMethod, ruta: String, cuerpo: String? = null): String {
        val r = try {
            http.request(base + "/api" + ruta) {
                method = metodo
                header("X-Cliente", "app-anaquel")
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (cuerpo != null) {
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo)
                }
            }
        } catch (e: Exception) {
            throw MercadoError("Sin conexión con el mercado. Revisa tu internet.", 0, "sin_conexion")
        }
        return revisar(r)
    }

    private suspend inline fun <reified T> get(ruta: String): T = json.decodeFromString(llamar(HttpMethod.Get, ruta))
    private suspend inline fun <reified T> post(ruta: String, c: JsonElement = JsonObject(emptyMap())): T =
        json.decodeFromString(llamar(HttpMethod.Post, ruta, c.toString()))
    private suspend inline fun <reified C> mandar(metodo: HttpMethod, ruta: String, c: C) {
        llamar(metodo, ruta, json.encodeToString(c))
    }

    private fun obj(vararg pares: Pair<String, Any?>) = JsonObject(pares.associate { (k, v) ->
        k to when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    })

    /** Descarga protegida (fotos y videos solo se sirven con sesión). Con caché en memoria. */
    private val cache = LinkedHashMap<String, ByteArray>(32, 0.75f, true)
    private val candado = Mutex()
    private var bytesEnCache = 0L
    suspend fun bajar(ruta: String): ByteArray {
        candado.withLock { cache[ruta]?.let { return it } }
        val r = try {
            http.request(base + ruta) {
                method = HttpMethod.Get
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        } catch (e: Exception) {
            throw MercadoError("Sin conexión", 0, "sin_conexion")
        }
        if (!r.status.isSuccess()) throw MercadoError("No se pudo cargar (${r.status.value})", r.status.value)
        val b = r.readRawBytes()
        candado.withLock {
            cache[ruta] = b
            bytesEnCache += b.size
            val it = cache.entries.iterator()
            while (bytesEnCache > 24L * 1024 * 1024 && it.hasNext()) { bytesEnCache -= it.next().value.size; it.remove() }
        }
        return b
    }

    // ---- acceso ----
    suspend fun config(): ConfigAcceso = get("/acceso/config")
    suspend fun sesion(): Cuenta? = get<RespSesionActual>("/acceso/sesion").cuenta
    suspend fun entrar(email: String, password: String): RespSesion = post("/acceso/entrar", obj("email" to email.trim(), "password" to password))
    suspend fun registrar(codigo: String, nombre: String, email: String, password: String, telefono: String, colonia: String, giro: String): RespSesion =
        post("/acceso/registro", obj("codigo" to codigo, "nombre" to nombre, "email" to email.trim(), "password" to password,
            "telefono" to telefono, "colonia" to colonia, "giro" to giro))
    suspend fun salir() { runCatching { llamar(HttpMethod.Post, "/acceso/salir", "{}") } }
    suspend fun yo(): Cuenta = get("/acceso/yo")
    suspend fun actualizarYo(nombre: String, descripcion: String, telefono: String, colonia: String): Cuenta =
        json.decodeFromString(llamar(HttpMethod.Put, "/acceso/yo", obj("nombre" to nombre, "descripcion" to descripcion, "telefono" to telefono, "colonia" to colonia).toString()))
    suspend fun cambiarPass(actual: String, nueva: String) { llamar(HttpMethod.Put, "/acceso/contrasena", obj("actual" to actual, "nueva" to nueva).toString()) }
    suspend fun invitaciones(): List<Invitacion> = get("/acceso/invitaciones")
    suspend fun crearInvitacion(): CodigoInvitacion = post("/acceso/invitaciones")
    suspend fun sesiones(): List<SesionActiva> = get("/acceso/sesiones")
    suspend fun cerrarOtras() { llamar(HttpMethod.Post, "/acceso/sesiones/cerrar-otras", "{}") }
    suspend fun pendientes(): List<Cuenta> = get("/acceso/admin/cuentas?estado=pendiente")
    suspend fun adminAccion(id: Long, accion: String) { llamar(HttpMethod.Post, "/acceso/admin/cuentas/$id/$accion", "{}") }

    // ---- mercado ----
    suspend fun categorias(): List<Categoria> = get("/mercado/categorias")
    suspend fun publicaciones(q: String = "", categoria: String = "", vendedor: String = "", antes: Long? = null): Pagina<Publicacion> {
        val p = buildList {
            if (q.isNotBlank()) add("q=" + q.trim().encodeURLQueryComponent())
            if (categoria.isNotBlank()) add("categoria=" + categoria.encodeURLQueryComponent())
            if (vendedor.isNotBlank()) add("vendedor=" + vendedor.encodeURLQueryComponent())
            if (antes != null) add("antes=$antes")
        }.joinToString("&")
        return get("/mercado/publicaciones" + if (p.isEmpty()) "" else "?$p")
    }
    suspend fun publicacion(id: Long): DetallePublicacion = get("/mercado/publicaciones/$id")
    suspend fun vendedor(slug: String): PerfilVendedor = get("/mercado/vendedores/${slug.encodeURLQueryComponent()}")
    suspend fun mias(): List<Publicacion> = get("/mercado/mias")
    suspend fun crearPublicacion(d: DatosPublicacion): Long = json.decodeFromString<Id>(llamar(HttpMethod.Post, "/mercado/publicaciones", json.encodeToString(d))).id
    suspend fun editarPublicacion(id: Long, d: DatosPublicacion) = mandar(HttpMethod.Put, "/mercado/publicaciones/$id", d)
    suspend fun estadoPublicacion(id: Long, estado: String) { llamar(HttpMethod.Post, "/mercado/publicaciones/$id/estado", obj("estado" to estado).toString()) }
    suspend fun borrarPublicacion(id: Long) { llamar(HttpMethod.Delete, "/mercado/publicaciones/$id") }
    suspend fun borrarFoto(pub: Long, foto: Long) { llamar(HttpMethod.Delete, "/mercado/publicaciones/$pub/fotos/$foto") }
    suspend fun subirFoto(pub: Long, jpeg: ByteArray, alt: String): Foto = subir("/mercado/publicaciones/$pub/fotos", "foto", "foto.jpg", "image/jpeg", jpeg, mapOf("alt" to alt))

    suspend fun apartar(pub: Long, cantidad: Int, nota: String): Long =
        post<Id>("/mercado/ordenes", obj("publicacion_id" to pub, "cantidad" to cantidad, "nota" to nota)).id
    suspend fun ordenes(ventas: Boolean): List<Orden> = get("/mercado/ordenes" + if (ventas) "?rol=ventas" else "")
    suspend fun orden(id: Long): Orden = get("/mercado/ordenes/$id")
    suspend fun accionOrden(id: Long, accion: String): Orden = post("/mercado/ordenes/$id/$accion")

    // ---- publicidad ----
    private fun patrocinado(e: JsonElement): Patrocinado {
        val o = e.jsonObject
        return Patrocinado(
            o["promo_id"]!!.jsonPrimitive.content.toLong(), o["plan"]!!.jsonPrimitive.content,
            o["mensaje"]?.jsonPrimitive?.content.orEmpty(), json.decodeFromJsonElement(Publicacion.serializer(), e),
        )
    }
    suspend fun espacios(q: String = "", categoria: String = ""): Espacios {
        val p = buildList {
            if (q.isNotBlank()) add("q=" + q.trim().encodeURLQueryComponent())
            if (categoria.isNotBlank()) add("categoria=" + categoria.encodeURLQueryComponent())
        }.joinToString("&")
        val o = json.parseToJsonElement(llamar(HttpMethod.Get, "/mercado/promociones/espacios?$p")).jsonObject
        val banner = o["banner"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let(::patrocinado)
        val productos = (o["productos"] as? kotlinx.serialization.json.JsonArray).orEmpty().map(::patrocinado)
        return Espacios(banner, productos)
    }
    suspend fun clicPromo(id: Long) { runCatching { llamar(HttpMethod.Post, "/mercado/promociones/$id/clic", "{}") } }
    suspend fun planes(): List<Plan> = get("/mercado/promociones/planes")
    suspend fun misPromos(): List<MiPromo> = get("/mercado/promociones/mias")
    suspend fun crearPromo(pub: Long, plan: String, semanas: Int, mensaje: String): Long =
        post<Id>("/mercado/promociones", obj("publicacion_id" to pub, "plan" to plan, "semanas" to semanas, "mensaje" to mensaje)).id
    suspend fun estadoPromo(id: Long, estado: String) { llamar(HttpMethod.Post, "/mercado/promociones/$id/estado", obj("estado" to estado).toString()) }

    // ---- reels ----
    suspend fun reels(antes: Long? = null): Pagina<Reel> = get("/reels/" + if (antes != null) "?antes=$antes" else "")
    suspend fun misReels(): List<Reel> = get("/reels/mios")
    suspend fun borrarReel(id: Long) { llamar(HttpMethod.Delete, "/reels/$id") }
    suspend fun subirReel(mp4: ByteArray, descripcion: String, publicacion: Long?): Long =
        json.decodeFromString<Id>(subirTexto("/reels/subir", "video", "video.mp4", "video/mp4", mp4,
            buildMap { put("descripcion", descripcion); if (publicacion != null) put("publicacion_id", publicacion.toString()) })).id

    // ---- pagos ----
    suspend fun proveedores(): List<Proveedor> = get("/pagos/proveedores")
    suspend fun terminales(): List<Terminal> = get("/pagos/terminales")
    suspend fun vincularTerminal(proveedor: String, nombre: String, dispositivo: String): Terminal =
        post("/pagos/terminales", obj("proveedor" to proveedor, "nombre" to nombre, "dispositivo" to dispositivo))
    suspend fun quitarTerminal(id: Long) { llamar(HttpMethod.Delete, "/pagos/terminales/$id") }
    suspend fun cobrar(orden: Long, terminal: Long): Cobro = post("/pagos/cobros", obj("orden_id" to orden, "terminal_id" to terminal))
    suspend fun cobro(id: String): Cobro = get("/pagos/cobros/$id")
    suspend fun cancelarCobro(id: String): Cobro = post("/pagos/cobros/$id/cancelar")
    suspend fun registros(desde: Long? = null): Registros = get("/pagos/registros" + if (desde != null) "?desde=$desde" else "")
    suspend fun vincularRegistro(id: Long, orden: Long) { llamar(HttpMethod.Post, "/pagos/registros/$id/vincular", obj("orden_id" to orden).toString()) }
    suspend fun simPendientes(): List<TransaccionSim> = get("/pagos/simulador/pendientes")
    suspend fun simResolver(tx: String, accion: String) { llamar(HttpMethod.Post, "/pagos/simulador/transacciones/$tx/$accion", "{}") }
    suspend fun simDirecto(terminal: Long, montoC: Long) { llamar(HttpMethod.Post, "/pagos/simulador/terminales/$terminal/cobro-directo", obj("monto_c" to montoC, "concepto" to "Venta de mostrador").toString()) }

    // ---- subidas (multipart) ----
    private suspend fun subirTexto(ruta: String, campo: String, nombre: String, tipo: String, datos: ByteArray, extra: Map<String, String>): String {
        val r = try {
            http.submitFormWithBinaryData(base + "/api" + ruta, formData {
                extra.forEach { (k, v) -> append(k, v) }
                append(campo, datos, Headers.build {
                    append(HttpHeaders.ContentType, tipo)
                    append(HttpHeaders.ContentDisposition, "filename=\"$nombre\"")
                })
            }) {
                header("X-Cliente", "app-anaquel")
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
        } catch (e: Exception) {
            throw MercadoError("No se pudo subir. Revisa tu conexión.", 0, "sin_conexion")
        }
        return revisar(r)
    }

    private suspend inline fun <reified T> subir(ruta: String, campo: String, nombre: String, tipo: String, datos: ByteArray, extra: Map<String, String>): T =
        json.decodeFromString(subirTexto(ruta, campo, nombre, tipo, datos, extra))
}
