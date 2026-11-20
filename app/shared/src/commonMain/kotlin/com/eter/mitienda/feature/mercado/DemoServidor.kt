package com.eter.mitienda.feature.mercado

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mitienda.shared.generated.resources.Res
import kotlin.time.Duration.Companion.days

/*
 * Modo demo sin servidor: imita /api/acceso, /api/mercado, /api/reels y /api/pagos
 * dentro de la app, para enseñarla en cualquier teléfono sin internet ni Docker.
 *
 * Arranca con una foto de los datos reales del mercado local (files/demo/datos.json:
 * negocios, productos, anuncios, reels, órdenes y la terminal virtual) y guarda en
 * el teléfono lo que cambia (apartar, cobrar, anunciar…), así sobrevive a cerrar la app.
 * «Reiniciar demo» vuelve a los datos de fábrica.
 * Las reglas son las mismas que en los servicios: estados de la orden, quién puede
 * hacer qué, lugares fijos de los patrocinados y el registro automático del cobro.
 */

/** Dirección especial que activa el modo demo (se puede escribir en «Servidor»). */
const val SERVIDOR_DEMO = "demo"

fun esDemo(base: String) = base.trim().lowercase() == SERVIDOR_DEMO

/** Video de un reel del demo: va empaquetado en el APK. */
fun uriDemo(ruta: String): String = Res.getUri("files/demo/media/" + ruta.trim('/').replace('/', '_'))

class Respuesta(val status: Int, val cuerpo: String)

internal class DemoServidor(
    private val json: Json,
    private val guardar: (String?) -> Unit,
    private val leer: () -> String?,
) {
    private val candado = Mutex()
    private var listo = false

    @Serializable private class Cta(var c: Cuenta, var pass: String = "demo1234")
    @Serializable private class Pub(var p: Publicacion, var v: Vendedor, val dueno: Long)
    @Serializable private class Ord(
        val id: Long, val comprador: Long, val vendedor: Long, val pub: Long?, val titulo: String, val unidad: String,
        val cantidad: Int, val precioC: Long, val totalC: Long, val nota: String, var estado: String, var cobroId: String?,
        val foto: Foto?, val creada: String, var actualizada: String, val eventos: MutableList<EventoOrden>,
    )
    @Serializable private class Promo(
        val id: Long, val dueno: Long, val pub: Long, val plan: String, val mensaje: String, val semanas: Int,
        val costoC: Long, val inicio: String, val fin: String, var estado: String, var impresiones: Long, var clics: Long,
    )
    @Serializable private class Ter(val dueno: Long, val t: Terminal)
    @Serializable private class Cob(val c: Cobro, var estado: String, var detalle: String, val terminalId: Long, val dueno: Long)
    @Serializable private class Reg(val dueno: Long, var r: Registro)

    private val cuentas = mutableListOf<Cta>()
    private val pubs = mutableListOf<Pub>()
    private val ordenes = mutableListOf<Ord>()
    private val promos = mutableListOf<Promo>()
    private val terminales = mutableListOf<Ter>()
    private val cobros = mutableListOf<Cob>()
    private val registros = mutableListOf<Reg>()
    @Serializable private class RReel(val dueno: Long, val r: Reel)
    @Serializable private class Estado(
        val cuentas: List<Cta>, val pubs: List<Pub>, val ordenes: List<Ord>, val promos: List<Promo>, val terminales: List<Ter>,
        val cobros: List<Cob>, val registros: List<Reg>, val reels: List<RReel>, val sig: Long,
    )
    private val reels = mutableListOf<RReel>()
    private val fotos = HashMap<String, ByteArray>()
    private var demoCfg = ConfigAcceso(true)
    private var categorias: JsonElement = JsonArray(emptyList())
    private var planes: List<Plan> = emptyList()
    private var proveedores: List<Proveedor> = emptyList()
    private var sig = 1000L

    // ---------------- carga ----------------
    private suspend fun cargar() {
        val d = json.parseToJsonElement(Res.readBytes("files/demo/datos.json").decodeToString()).jsonObject
        demoCfg = json.decodeFromJsonElement(d["config"]!!)
        categorias = d["categorias"]!!
        planes = json.decodeFromJsonElement(d["planes"]!!)
        proveedores = json.decodeFromJsonElement(d["proveedores"]!!)
        listOf(cuentas, pubs, ordenes, promos, terminales, cobros, registros, reels).forEach { it.clear() }
        val guardado = leer()?.let { runCatching { json.decodeFromString<Estado>(it) }.getOrNull() }
        if (guardado != null) {
            cuentas += guardado.cuentas; pubs += guardado.pubs; ordenes += guardado.ordenes; promos += guardado.promos
            terminales += guardado.terminales; cobros += guardado.cobros; registros += guardado.registros; reels += guardado.reels
            sig = guardado.sig
            listo = true
            return
        }
        d["cuentas"]!!.jsonArray.forEach { cuentas += Cta(json.decodeFromJsonElement(it)) }
        d["publicaciones"]!!.jsonArray.forEach { e ->
            val o = e.jsonObject
            val email = o["publicacion"]!!.jsonObject["vendedor_email"]!!.jsonPrimitive.content
            pubs += Pub(json.decodeFromJsonElement(o["publicacion"]!!), json.decodeFromJsonElement(o["vendedor"]!!), porEmail(email)!!.c.id)
        }
        d["promos"]!!.jsonObject.forEach { (email, lista) ->
            val dueno = porEmail(email)!!.c.id
            json.decodeFromJsonElement<List<MiPromo>>(lista).forEach { m ->
                promos += Promo(m.id, dueno, m.publicacion.id, m.plan, m.mensaje, m.semanas, m.costoC, m.inicio, m.fin, m.estado, m.impresiones, m.clics)
            }
        }
        d["terminales"]!!.jsonObject.forEach { (email, lista) ->
            val dueno = porEmail(email)!!.c.id
            json.decodeFromJsonElement<List<Terminal>>(lista).forEach { terminales += Ter(dueno, it) }
        }
        d["ordenes_detalle"]!!.jsonObject.values.forEach { e ->
            val o = json.decodeFromJsonElement<Orden>(e)
            val vend = pubs.firstOrNull { it.p.id == o.publicacionId }?.dueno ?: return@forEach
            val comp = cuentas.firstOrNull { it.c.slug == o.contraparteSlug }?.c?.id ?: return@forEach
            ordenes += Ord(o.id, comp, vend, o.publicacionId, o.titulo, o.unidad, o.cantidad, o.precioC, o.totalC, o.nota,
                o.estado, o.cobroId, o.foto, o.creada, o.actualizada, o.eventos.orEmpty().toMutableList())
        }
        d["reels"]!!.jsonArray.forEach { e ->
            val r = json.decodeFromJsonElement<Reel>(e)
            val dueno = cuentas.firstOrNull { it.c.slug == r.vendedor.slug }?.c?.id ?: 0
            reels += RReel(dueno, r)
        }
        sig = (listOf(pubs.maxOfOrNull { it.p.id }, ordenes.maxOfOrNull { it.id }, promos.maxOfOrNull { it.id },
            terminales.maxOfOrNull { it.t.id }, reels.maxOfOrNull { it.r.id }).maxOf { it ?: 0 }) + 1
        listo = true
    }

    private fun persistir() {
        guardar(json.encodeToString(Estado(cuentas, pubs, ordenes, promos, terminales, cobros, registros, reels, sig)))
    }

    /** Vuelve a los datos de fábrica del demo. */
    suspend fun reiniciar() = candado.withLock {
        guardar(null)
        fotos.clear()
        listo = false
    }

    private fun porEmail(e: String) = cuentas.firstOrNull { it.c.email.equals(e.trim(), ignoreCase = true) }
    private fun porId(id: Long) = cuentas.firstOrNull { it.c.id == id }
    private fun nuevoId() = sig++

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun ahora(masDias: Int = 0) = (kotlin.time.Clock.System.now() + masDias.days).toString()

    // ---------------- respuestas ----------------
    private class Falla(val status: Int, val msg: String, val codigo: String) : Exception(msg)
    private fun falla(status: Int, msg: String, codigo: String = "error"): Nothing = throw Falla(status, msg, codigo)
    private inline fun <reified T> ok(v: T) = Respuesta(200, json.encodeToString(v))
    private fun okJson(e: JsonElement) = Respuesta(200, e.toString())
    private val vacio = Respuesta(200, "{}")

    suspend fun atender(metodo: String, rutaCompleta: String, cuerpo: String?, token: String?): Respuesta = candado.withLock {
        if (!listo) cargar()
        val (ruta, query) = rutaCompleta.split('?', limit = 2).let { it[0].trimEnd('/').ifEmpty { "/" } to (it.getOrNull(1) ?: "") }
        val q = query.split('&').filter { '=' in it }.associate { it.substringBefore('=') to decodificar(it.substringAfter('=')) }
        val b = cuerpo?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val yo = token?.removePrefix("demo-")?.toLongOrNull()?.let(::porId)
        try {
            rutear(metodo, ruta.split('/').filter { it.isNotEmpty() }, q, b, yo)
                .also { if (metodo != "GET" || ruta.endsWith("/espacios")) persistir() }
        } catch (f: Falla) {
            Respuesta(f.status, buildJsonObject { put("error", f.msg); put("codigo", f.codigo) }.toString())
        }
    }

    private fun yo(c: Cta?): Cta = c ?: falla(401, "Tu sesión terminó. Vuelve a entrar.", "sin_sesion")
    private fun vendedor(c: Cta?): Cta = yo(c).also { if (!it.c.vende) falla(403, "Tu cuenta todavía no puede vender.", "sin_permiso") }
    private fun s(b: JsonObject, k: String) = b[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun l(b: JsonObject, k: String) = b[k]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun rutear(m: String, p: List<String>, q: Map<String, String>, b: JsonObject, c: Cta?): Respuesta = when {
        // ---- acceso ----
        p == listOf("acceso", "config") -> ok(demoCfg)
        p == listOf("acceso", "sesion") -> ok(RespSesionActual(c?.c))
        p == listOf("acceso", "entrar") -> {
            val cta = porEmail(s(b, "email"))
            if (cta == null || cta.pass != s(b, "password")) falla(401, "Correo o contraseña incorrectos.", "credenciales")
            if (cta.c.estado == "suspendida") falla(403, "Esta cuenta está suspendida.", "suspendida")
            ok(RespSesion(cta.c, "demo-${cta.c.id}"))
        }
        p == listOf("acceso", "registro") -> {
            if (s(b, "codigo").isBlank()) falla(400, "Escribe el código de invitación.", "codigo")
            if (porEmail(s(b, "email")) != null) falla(409, "Ya hay una cuenta con ese correo.", "duplicado")
            if (s(b, "password").length < 8) falla(400, "La contraseña debe tener al menos 8 caracteres.", "password")
            val id = nuevoId()
            val nombre = s(b, "nombre").ifBlank { "Mi negocio" }
            val nueva = Cuenta(id, nombre, slug(nombre), s(b, "email").trim(), s(b, "telefono"), s(b, "giro"), "", s(b, "colonia"), estado = "pendiente", demo = true)
            cuentas += Cta(nueva, s(b, "password"))
            ok(RespSesion(nueva, "demo-$id"))
        }
        p == listOf("acceso", "salir") -> vacio
        p == listOf("acceso", "yo") && m == "GET" -> ok(yo(c).c)
        p == listOf("acceso", "yo") && m == "PUT" -> {
            val cta = yo(c)
            cta.c = cta.c.copy(nombre = s(b, "nombre").ifBlank { cta.c.nombre }, descripcion = s(b, "descripcion"), telefono = s(b, "telefono"), colonia = s(b, "colonia"))
            ok(cta.c)
        }
        p == listOf("acceso", "contrasena") -> {
            val cta = yo(c)
            if (cta.pass != s(b, "actual")) falla(400, "La contraseña actual no es correcta.", "password")
            if (s(b, "nueva").length < 8) falla(400, "La nueva contraseña debe tener al menos 8 caracteres.", "password")
            cta.pass = s(b, "nueva"); vacio
        }
        p == listOf("acceso", "invitaciones") && m == "GET" -> { yo(c); okJson(JsonArray(emptyList())) }
        p == listOf("acceso", "invitaciones") -> { yo(c); ok(CodigoInvitacion("DEMO-${(1000..9999).random()}", ahora(7))) }
        p == listOf("acceso", "sesiones") -> { yo(c); ok(listOf(SesionActiva(true, ahora(), ahora(), "Este teléfono (modo demo)", "app"))) }
        p == listOf("acceso", "sesiones", "cerrar-otras") -> vacio
        p.take(3) == listOf("acceso", "admin", "cuentas") && p.size == 3 -> {
            if (yo(c).c.rol != "admin") falla(403, "Solo la administración.", "sin_permiso")
            ok(cuentas.filter { it.c.estado == (q["estado"] ?: "pendiente") }.map { it.c })
        }
        p.take(3) == listOf("acceso", "admin", "cuentas") && p.size == 5 -> {
            if (yo(c).c.rol != "admin") falla(403, "Solo la administración.", "sin_permiso")
            val cta = porId(p[3].toLong()) ?: falla(404, "No existe esa cuenta.", "no_existe")
            cta.c = when (p[4]) {
                "aprobar", "verificar", "reactivar" -> cta.c.copy(estado = "verificada", vende = true)
                "suspender" -> cta.c.copy(estado = "suspendida")
                else -> cta.c.copy(estado = "rechazada")
            }
            vacio
        }

        // ---- mercado ----
        p == listOf("mercado", "categorias") -> { yo(c); okJson(categorias) }
        p == listOf("mercado", "publicaciones") && m == "GET" -> {
            val me = yo(c).c.id
            val texto = normalizar(q["q"].orEmpty())
            val lista = pubs.filter { it.p.estado == "activa" }
                .filter { q["categoria"].isNullOrBlank() || it.p.categoria == q["categoria"] }
                .filter { q["vendedor"].isNullOrBlank() || it.v.slug == q["vendedor"] }
                .filter { texto.isBlank() || texto.split(' ').all { t -> normalizar(it.p.titulo + " " + it.p.descripcion.orEmpty() + " " + it.v.nombre).contains(t) } }
                .sortedByDescending { it.p.id }
                .map { pubPara(it, me).copy(fotos = null) }
            ok(Pagina(lista, null))
        }
        p == listOf("mercado", "publicaciones") && m == "POST" -> {
            val cta = vendedor(c)
            val d = datosPub(b)
            val id = nuevoId()
            val v = Vendedor(cta.c.nombre, cta.c.slug, cta.c.colonia, cta.c.giro, cta.c.descripcion, cta.c.tiendaSlug)
            pubs += Pub(Publicacion(id, d.titulo, d.descripcion, d.categoria, d.precioC, d.unidad, d.existencias, d.minimo, "activa", null, emptyList(),
                VendedorCorto(cta.c.nombre, cta.c.slug, cta.c.colonia), 0.0), v, cta.c.id)
            ok(Id(id))
        }
        p.size == 3 && p[0] == "mercado" && p[1] == "publicaciones" && m == "GET" -> {
            val me = yo(c).c.id
            val x = pub(p[2]).takeIf { it.p.estado == "activa" || it.dueno == me } ?: falla(404, "Esta publicación ya no está disponible.", "no_existe")
            ok(DetallePublicacion(pubPara(x, me), x.v))
        }
        p.size == 3 && p[0] == "mercado" && p[1] == "publicaciones" && m == "PUT" -> {
            val x = propia(p[2], c); val d = datosPub(b)
            x.p = x.p.copy(titulo = d.titulo, descripcion = d.descripcion, categoria = d.categoria, precioC = d.precioC, unidad = d.unidad, existencias = d.existencias, minimo = d.minimo)
            vacio
        }
        p.size == 3 && p[0] == "mercado" && p[1] == "publicaciones" && m == "DELETE" -> { val x = propia(p[2], c); pubs.remove(x); vacio }
        p.size == 4 && p[0] == "mercado" && p[1] == "publicaciones" && p[3] == "estado" -> {
            val x = propia(p[2], c)
            x.p = x.p.copy(estado = if (s(b, "estado") == "activa") "activa" else "pausada"); vacio
        }
        p.size == 5 && p[0] == "mercado" && p[3] == "fotos" && m == "DELETE" -> {
            val x = propia(p[2], c)
            val fs = x.p.fotos.orEmpty().filter { it.id != p[4].toLongOrNull() }
            x.p = x.p.copy(fotos = fs, foto = fs.firstOrNull()); vacio
        }
        p.size == 3 && p[0] == "mercado" && p[1] == "vendedores" -> {
            yo(c)
            val v = pubs.firstOrNull { it.v.slug == p[2] }?.v
                ?: cuentas.firstOrNull { it.c.slug == p[2] }?.c?.let { Vendedor(it.nombre, it.slug, it.colonia, it.giro, it.descripcion, it.tiendaSlug) }
                ?: falla(404, "No encontramos ese negocio.", "no_existe")
            ok(PerfilVendedor(v, pubs.count { it.v.slug == v.slug && it.p.estado == "activa" }))
        }
        p == listOf("mercado", "mias") -> { val me = vendedor(c).c.id; ok(pubs.filter { it.dueno == me }.sortedByDescending { it.p.id }.map { pubPara(it, me) }) }

        p == listOf("mercado", "ordenes") && m == "POST" -> apartar(yo(c), b)
        p == listOf("mercado", "ordenes") -> {
            val me = yo(c).c.id
            val ventas = q["rol"] == "ventas"
            ok(ordenes.filter { if (ventas) it.vendedor == me else it.comprador == me }.sortedByDescending { it.id }.map { vista(it, me).copy(eventos = null) })
        }
        p.size == 3 && p[0] == "mercado" && p[1] == "ordenes" -> { val me = yo(c).c.id; ok(vista(orden(p[2], me), me)) }
        p.size == 4 && p[0] == "mercado" && p[1] == "ordenes" -> accionOrden(p[2], p[3], yo(c))

        // ---- publicidad ----
        p == listOf("mercado", "promociones", "espacios") -> espacios(yo(c).c.id, q["q"].orEmpty(), q["categoria"].orEmpty())
        p == listOf("mercado", "promociones", "planes") -> ok(planes)
        p == listOf("mercado", "promociones", "mias") -> { val me = yo(c).c.id; ok(promos.filter { it.dueno == me }.sortedByDescending { it.id }.map(::miPromo)) }
        p == listOf("mercado", "promociones") && m == "POST" -> {
            val cta = vendedor(c)
            val x = pubs.firstOrNull { it.p.id == l(b, "publicacion_id") && it.dueno == cta.c.id } ?: falla(404, "Elige uno de tus productos.", "no_existe")
            if (x.p.estado != "activa") falla(400, "Activa el producto antes de anunciarlo.", "pausada")
            val plan = planes.firstOrNull { it.clave == s(b, "plan") } ?: falla(400, "Elige un plan.", "plan")
            if (promos.any { it.pub == x.p.id && it.estado == "activa" }) falla(409, "Ese producto ya tiene un anuncio activo.", "duplicado")
            val semanas = (l(b, "semanas") ?: 1).toInt().coerceIn(1, 12)
            val id = nuevoId()
            promos += Promo(id, cta.c.id, x.p.id, plan.clave, s(b, "mensaje").take(90), semanas, plan.precioSemanaC * semanas, ahora(), ahora(7 * semanas), "activa", 0, 0)
            ok(Id(id))
        }
        p.size == 4 && p[1] == "promociones" && p[3] == "estado" -> {
            val me = yo(c).c.id
            val pr = promos.firstOrNull { it.id == p[2].toLongOrNull() && it.dueno == me } ?: falla(404, "No existe ese anuncio.", "no_existe")
            pr.estado = s(b, "estado").ifBlank { "pausada" }; vacio
        }
        p.size == 4 && p[1] == "promociones" && p[3] == "clic" -> {
            val me = yo(c).c.id
            promos.firstOrNull { it.id == p[2].toLongOrNull() && it.dueno != me }?.let { it.clics++ }; vacio
        }

        // ---- reels ----
        p == listOf("reels") -> { val me = yo(c).c.id; ok(Pagina(reels.sortedByDescending { it.r.id }.map { it.r.copy(esMio = it.dueno == me) }, null)) }
        p == listOf("reels", "mios") -> { val me = yo(c).c.id; ok(reels.filter { it.dueno == me }.map { it.r.copy(esMio = true) }) }
        p.size == 2 && p[0] == "reels" && m == "DELETE" -> {
            val me = yo(c).c.id
            reels.removeAll { it.dueno == me && it.r.id == p[1].toLongOrNull() }; vacio
        }

        // ---- pagos ----
        p == listOf("pagos", "proveedores") -> ok(proveedores)
        p == listOf("pagos", "terminales") && m == "GET" -> { val me = yo(c).c.id; ok(terminales.filter { it.dueno == me }.map { it.t }) }
        p == listOf("pagos", "terminales") && m == "POST" -> {
            val me = vendedor(c).c.id
            val prov = proveedores.firstOrNull { it.clave == s(b, "proveedor") } ?: falla(400, "Elige el tipo de terminal.", "proveedor")
            val nombre = s(b, "nombre").ifBlank { falla(400, "Ponle un nombre a la terminal.", "nombre") }
            val marca = if (prov.clave == "simulador") "Terminal virtual (demo)" else prov.nombre
            val disp = s(b, "dispositivo").ifBlank { "SIM-" + (100000..999999).random() }
            val t = Terminal(nuevoId(), prov.clave, marca, nombre, disp)
            terminales += Ter(me, t); ok(t)
        }
        p.size == 3 && p[0] == "pagos" && p[1] == "terminales" && m == "DELETE" -> {
            val me = yo(c).c.id; terminales.removeAll { it.dueno == me && it.t.id == p[2].toLongOrNull() }; vacio
        }
        p == listOf("pagos", "cobros") -> cobrar(vendedor(c), b)
        p.size == 3 && p[1] == "cobros" -> ok(cobro(p[2], yo(c)).let(::vistaCobro))
        p.size == 4 && p[1] == "cobros" && p[3] == "cancelar" -> {
            val cb = cobro(p[2], yo(c))
            if (cb.estado == "enviado") {
                cb.estado = "cancelado"
                ordenes.firstOrNull { it.id == cb.c.ordenId }?.let { mover(it, "pendiente", "vendedor", "Cobro cancelado"); it.cobroId = null }
            }
            ok(vistaCobro(cb))
        }
        p == listOf("pagos", "registros") -> {
            val me = yo(c).c.id
            val mios = registros.filter { it.dueno == me }.sortedByDescending { it.r.id }
            val hoy = ahora().take(10)
            val deHoy = mios.filter { it.r.pagadoEn.startsWith(hoy) }
            val desde = q["desde"]?.toLongOrNull()
            ok(Registros(mios.filter { desde == null || it.r.id > desde }.map { it.r }, deHoy.sumOf { it.r.montoC }, deHoy.size))
        }
        p.size == 4 && p[1] == "registros" && p[3] == "vincular" -> {
            val me = yo(c).c.id
            val g = registros.firstOrNull { it.dueno == me && it.r.id == p[2].toLongOrNull() } ?: falla(404, "No existe ese cobro.", "no_existe")
            val o = orden(l(b, "orden_id").toString(), me)
            if (o.vendedor != me) falla(403, "Solo el vendedor puede vincular el cobro.", "sin_permiso")
            if (g.r.ordenId != null) falla(409, "Ese cobro ya está ligado a una orden.", "duplicado")
            if (g.r.montoC != o.totalC) falla(400, "El monto no coincide con el total de la orden.", "monto")
            if (o.estado != "pendiente" && o.estado != "por_cobrar") falla(409, "La orden ya no está pendiente de pago.", "estado")
            g.r = g.r.copy(ordenId = o.id)
            mover(o, "pagada", "pagos", "Pago directo en la terminal, vinculado por el vendedor")
            vacio
        }
        p == listOf("pagos", "simulador", "pendientes") -> {
            val me = yo(c).c.id
            val mias = terminales.filter { it.dueno == me && it.t.proveedor == "simulador" }.map { it.t.id }.toSet()
            ok(cobros.filter { it.estado == "enviado" && it.terminalId in mias }.map {
                TransaccionSim(it.c.id, it.c.terminal, it.c.montoC, ordenes.firstOrNull { o -> o.id == it.c.ordenId }?.titulo.orEmpty())
            })
        }
        p.size == 5 && p[1] == "simulador" && p[2] == "transacciones" -> {
            val me = yo(c)
            val cb = cobros.firstOrNull { it.c.id == p[3] && it.dueno == me.c.id && it.estado == "enviado" } ?: falla(404, "No hay ese cobro en la terminal.", "no_existe")
            val o = ordenes.firstOrNull { it.id == cb.c.ordenId }
            if (p[4] == "aprobar") {
                cb.estado = "aprobado"
                registros += Reg(me.c.id, Registro(nuevoId(), cb.c.montoC, cb.c.proveedor, cb.c.marca, cb.c.terminal, "app", cb.c.ordenId, "", ahora()))
                o?.let { mover(it, "pagada", "pagos", "Pago aprobado") }
            } else {
                cb.estado = "rechazado"; cb.detalle = "Tarjeta rechazada"
                o?.let { mover(it, "pendiente", "pagos", "Tarjeta rechazada"); it.cobroId = null }
            }
            vacio
        }
        p.size == 5 && p[1] == "simulador" && p[2] == "terminales" && p[4] == "cobro-directo" -> {
            val me = yo(c).c.id
            val t = terminales.firstOrNull { it.dueno == me && it.t.id == p[3].toLongOrNull() }?.t ?: falla(404, "No existe esa terminal.", "no_existe")
            val monto = l(b, "monto_c") ?: 0
            if (monto <= 0) falla(400, "Escribe el monto.", "monto")
            registros += Reg(me, Registro(nuevoId(), monto, t.proveedor, t.marca, t.nombre, "terminal", null, s(b, "concepto"), ahora()))
            vacio
        }
        else -> falla(404, "Esto no está disponible en el modo demo.", "no_existe")
    }

    // ---------------- reglas ----------------
    private fun pub(id: String) = pubs.firstOrNull { it.p.id == id.toLongOrNull() } ?: falla(404, "Esta publicación ya no está disponible.", "no_existe")
    private fun propia(id: String, c: Cta?): Pub { val me = vendedor(c).c.id; return pub(id).takeIf { it.dueno == me } ?: falla(403, "Solo puedes cambiar tus publicaciones.", "sin_permiso") }
    private fun pubPara(x: Pub, me: Long) = x.p.copy(esMia = x.dueno == me, distanciaKm = if (x.dueno == me) 0.0 else x.p.distanciaKm)

    private fun datosPub(b: JsonObject): DatosPublicacion {
        val d = runCatching { json.decodeFromJsonElement<DatosPublicacion>(b) }.getOrNull() ?: falla(400, "Revisa los datos del producto.", "datos")
        if (d.titulo.isBlank()) falla(400, "Ponle un título.", "titulo")
        if (d.precioC <= 0) falla(400, "El precio debe ser mayor a cero.", "precio")
        return d
    }

    private fun orden(id: String, me: Long) = ordenes.firstOrNull { it.id == id.toLongOrNull() && (it.comprador == me || it.vendedor == me) }
        ?: falla(404, "No encontramos esa orden.", "no_existe")

    private fun vista(o: Ord, me: Long): Orden {
        val vende = o.vendedor == me
        val otro = porId(if (vende) o.comprador else o.vendedor)?.c
        return Orden(o.id, if (vende) "venta" else "compra", otro?.nombre.orEmpty(), otro?.slug.orEmpty(), o.pub, o.titulo, o.unidad, o.cantidad,
            o.precioC, o.totalC, o.nota, o.estado, o.cobroId, o.foto, o.creada, o.actualizada, o.eventos.toList())
    }

    private fun mover(o: Ord, estado: String, por: String, nota: String = "") {
        o.estado = estado
        o.actualizada = ahora()
        o.eventos += EventoOrden(estado, por, nota, o.actualizada)
    }

    private fun apartar(c: Cta, b: JsonObject): Respuesta {
        val x = pubs.firstOrNull { it.p.id == l(b, "publicacion_id") && it.p.estado == "activa" } ?: falla(404, "Esta publicación ya no está disponible.", "no_existe")
        if (x.dueno == c.c.id) falla(400, "No puedes apartar tu propio producto.", "propio")
        val cant = (l(b, "cantidad") ?: 1).toInt()
        if (cant < x.p.minimo) falla(400, "El mínimo para este producto es ${x.p.minimo}.", "minimo")
        x.p.existencias?.let { if (cant > it) falla(400, "Solo quedan $it disponibles.", "existencias") }
        val t = ahora()
        val o = Ord(nuevoId(), c.c.id, x.dueno, x.p.id, x.p.titulo, x.p.unidad, cant, x.p.precioC, x.p.precioC * cant, s(b, "nota").take(280),
            "pendiente", null, x.p.foto, t, t, mutableListOf(EventoOrden("pendiente", "comprador", "", t)))
        ordenes += o
        return ok(Id(o.id))
    }

    private fun accionOrden(id: String, accion: String, c: Cta): Respuesta {
        val me = c.c.id
        val o = orden(id, me)
        when (accion) {
            "cancelar" -> {
                if (o.estado != "pendiente") falla(409, "Solo se puede cancelar mientras está apartada.", "estado")
                mover(o, "cancelada", if (o.vendedor == me) "vendedor" else "comprador")
            }
            "entregar" -> {
                if (o.vendedor != me) falla(403, "Solo el vendedor marca la entrega.", "sin_permiso")
                if (o.estado != "pagada") falla(409, "Primero se tiene que pagar.", "estado")
                mover(o, "entregada", "vendedor")
            }
            else -> falla(400, "Acción no válida.", "accion")
        }
        return ok(vista(o, me))
    }

    private fun cobrar(c: Cta, b: JsonObject): Respuesta {
        val o = orden(l(b, "orden_id").toString(), c.c.id)
        if (o.vendedor != c.c.id) falla(403, "Solo el vendedor puede cobrar.", "sin_permiso")
        if (o.estado != "pendiente") falla(409, "Esta orden ya no está pendiente de pago.", "estado")
        val t = terminales.firstOrNull { it.dueno == c.c.id && it.t.id == l(b, "terminal_id") }?.t ?: falla(404, "Elige una de tus terminales.", "terminal")
        val cb = Cob(Cobro("cob_demo_${nuevoId()}", o.id, t.proveedor, t.marca, t.nombre, o.totalC, "enviado"), "enviado", "", t.id, c.c.id)
        cobros += cb
        o.cobroId = cb.c.id
        mover(o, "por_cobrar", "pagos")
        return ok(vistaCobro(cb))
    }

    private fun cobro(id: String, c: Cta) = cobros.firstOrNull { it.c.id == id && it.dueno == c.c.id } ?: falla(404, "No existe ese cobro.", "no_existe")
    private fun vistaCobro(cb: Cob) = cb.c.copy(estado = cb.estado, detalle = cb.detalle)

    private fun miPromo(pr: Promo): MiPromo {
        val x = pubs.firstOrNull { it.p.id == pr.pub }
        val plan = planes.firstOrNull { it.clave == pr.plan }
        val vigente = pr.estado == "activa" && pr.fin > ahora()
        return MiPromo(pr.id, pr.plan, plan?.nombre ?: pr.plan, pr.mensaje, PubPromo(pr.pub, x?.p?.titulo ?: "Producto", x?.p?.foto, x?.p?.categoria ?: "otros"),
            pr.semanas, pr.costoC, pr.inicio, pr.fin, pr.estado, vigente, pr.impresiones, pr.clics)
    }

    /** Mismas reglas que el servicio: banner solo sin búsqueda, máximo 3 patrocinados, prioridad por plan y luego por menos vistas. */
    private fun espacios(me: Long, texto: String, categoria: String): Respuesta {
        val prioridad = mapOf("premium" to 0, "destacado" to 1, "basico" to 2)
        val ahora = ahora()
        val t = normalizar(texto)
        val candidatos = promos.filter { it.estado == "activa" && it.fin > ahora && it.dueno != me }
            .mapNotNull { pr -> pubs.firstOrNull { it.p.id == pr.pub && it.p.estado == "activa" }?.let { pr to it } }
            .filter { (_, x) -> categoria.isBlank() || x.p.categoria == categoria }
            .filter { (pr, x) -> t.isBlank() || (planes.firstOrNull { it.clave == pr.plan }?.enBusquedas == true && normalizar(x.p.titulo + " " + x.p.descripcion.orEmpty()).contains(t)) }
            .sortedWith(compareBy({ prioridad[it.first.plan] ?: 9 }, { it.first.impresiones }))
        val banner = if (t.isBlank()) candidatos.firstOrNull { (pr, _) -> planes.firstOrNull { it.clave == pr.plan }?.banner == true } else null
        val productos = candidatos.filter { it != banner }.distinctBy { it.second.p.id }.take(POSICIONES_PATROCINADAS.size)
        (listOfNotNull(banner) + productos).forEach { it.first.impresiones++ }
        fun anuncio(par: Pair<Promo, Pub>): JsonObject {
            val base = json.encodeToJsonElement(Publicacion.serializer(), pubPara(par.second, me).copy(fotos = null)).jsonObject
            return JsonObject(base + mapOf("promo_id" to JsonPrimitive(par.first.id), "plan" to JsonPrimitive(par.first.plan), "mensaje" to JsonPrimitive(par.first.mensaje)))
        }
        return okJson(JsonObject(mapOf("banner" to (banner?.let(::anuncio) ?: JsonNull), "productos" to JsonArray(productos.map(::anuncio)))))
    }

    // ---------------- fotos (subidas en el demo) ----------------
    fun subirFoto(ruta: String, bytes: ByteArray, alt: String, token: String?): Respuesta {
        val me = token?.removePrefix("demo-")?.toLongOrNull()?.let(::porId) ?: return Respuesta(401, """{"error":"Vuelve a entrar.","codigo":"sin_sesion"}""")
        val partes = ruta.split('/').filter { it.isNotEmpty() }
        if (partes.size == 4 && partes[1] == "publicaciones" && partes[3] == "fotos") {
            val x = pubs.firstOrNull { it.p.id == partes[2].toLongOrNull() && it.dueno == me.c.id } ?: return Respuesta(404, """{"error":"No existe ese producto.","codigo":"no_existe"}""")
            val id = nuevoId()
            val url = "/demo/fotos/$id.jpg"
            fotos[url] = bytes
            val f = Foto(id, url, alt.ifBlank { x.p.titulo })
            val fs = x.p.fotos.orEmpty() + f
            x.p = x.p.copy(fotos = fs, foto = fs.first())
            persistir()
            return ok(f)
        }
        return Respuesta(400, """{"error":"En el modo demo no se pueden subir videos. Usa los reels de ejemplo.","codigo":"demo"}""")
    }

    /** Fotos subidas en el demo (memoria) o las de los productos de ejemplo (empaquetadas). */
    suspend fun foto(ruta: String): ByteArray? = fotos[ruta]
        ?: if (ruta.startsWith("/demo/img/")) runCatching { Res.readBytes("files/demo/img/" + ruta.substringAfterLast('/')) }.getOrNull() else null

    // ---------------- utilidades ----------------
    private fun decodificar(v: String): String = buildString {
        val bytes = mutableListOf<Byte>()
        var i = 0
        fun volcar() { if (bytes.isNotEmpty()) { append(bytes.toByteArray().decodeToString()); bytes.clear() } }
        while (i < v.length) {
            when {
                v[i] == '%' && i + 2 < v.length + 0 && i + 2 <= v.length - 1 -> { bytes += v.substring(i + 1, i + 3).toInt(16).toByte(); i += 3 }
                v[i] == '+' -> { volcar(); append(' '); i++ }
                else -> { volcar(); append(v[i]); i++ }
            }
        }
        volcar()
    }

    private fun normalizar(t: String) = t.lowercase().map { c ->
        when (c) { 'á' -> 'a'; 'é' -> 'e'; 'í' -> 'i'; 'ó' -> 'o'; 'ú', 'ü' -> 'u'; else -> c }
    }.joinToString("").trim()

    private fun slug(t: String) = normalizar(t).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "negocio" } + "-" + (100..999).random()
}

/** Crédito de una foto de ejemplo (todas de Wikimedia Commons con licencia libre). */
@Serializable data class CreditoFoto(val producto: String, val autor: String, val licencia: String, val fuente: String)

suspend fun creditosFotos(): List<CreditoFoto> = runCatching {
    val j = Json { ignoreUnknownKeys = true }
    val d = j.parseToJsonElement(Res.readBytes("files/demo/datos.json").decodeToString()).jsonObject
    j.decodeFromJsonElement<List<CreditoFoto>>(d["creditos_fotos"] ?: JsonArray(emptyList()))
}.getOrDefault(emptyList())
