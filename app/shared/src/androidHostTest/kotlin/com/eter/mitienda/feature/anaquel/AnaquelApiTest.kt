package com.eter.mitienda.feature.anaquel

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Prueba de punta a punta contra un servidor real (local o producción).
 * ANAQUEL_URL=http://127.0.0.1:8097/anaquel ./gradlew :shared:testAndroidHostTest
 * Sin la variable no hace nada.
 */
class AnaquelApiTest {
    @Test
    fun flujoCompleto() = runBlocking {
        val url = System.getenv("ANAQUEL_URL") ?: return@runBlocking
        val api = AnaquelApi(url)
        val s = api.entrar("demo@anaquel.mx", "demo1234")
        api.token = s.token
        println("tienda: ${s.tienda.nombre}")
        val r = api.resumen()
        println("resumen: $r")
        val sync = api.sincronizar(listOf(ProductoNube(codigo = "PRUEBA-APP", nombre = "Producto de prueba app", stock = 1, stockMin = 3)))
        println("sync: $sync")
        val falt = api.faltantes()
        println("faltantes: ${falt.map { it.producto.nombre + "/" + it.nivel + "/" + it.sugerencias.map { s -> s.nombre } }}")
        val an = api.anaqueles()
        assertTrue(an.isNotEmpty())
        val ref = api.bajarFoto(an.first().fotoRef)
        println("foto ref: ${ref.size} bytes")
        val res = api.fotoDelDia(an.first().id, ref)
        println("foto del día: método=${res.metodo} faltan=${res.faltantes.size} llegaron=${res.llegaron.map { it.id }}")
        val conSug = falt.firstOrNull { it.enPedido == null && it.sugerencias.isNotEmpty() }
        if (conSug != null) {
            val p = api.pedir(conSug.sugerencias.first(), listOf(conSug.producto.id to 2L))
            println("pedido: #${p.id} ${p.estado} ${p.whatsapp.take(40)}")
            val c = api.cambiarEstado(p.id, "cancelado")
            assertTrue(c.estado == "cancelado")
        }
        val z = api.guardarZonas(an.first().id, an.first().zonas)
        assertTrue(z.zonas.size == an.first().zonas.size)
        println("pedidos: ${api.pedidos().size}")
    }

    @Test
    fun redTratosYAsesor() = runBlocking {
        val url = System.getenv("ANAQUEL_URL") ?: return@runBlocking
        val compra = AnaquelApi(url).apply { token = entrar("papeleria@anaquel.mx", "demo1234").token }
        val vende = AnaquelApi(url).apply { token = entrar("mayoreo@anaquel.mx", "demo1234").token }
        val red = compra.red("gorras")
        println("red: ${red.map { it.tipo + ":" + it.titulo }}")
        val gorra = red.first { it.tipo == "producto" }
        val t = compra.crearTrato(gorra, 100, 4500, "Prueba desde la app")
        assertTrue(t.estado == "negociando" && !t.teToca)
        val delVendedor = vende.tratos().first { it.id == t.id }
        assertTrue(delVendedor.teToca && delVendedor.rol == "vendo")
        val contra = vende.mensajeTrato(t.id, "", 100, 4800)
        assertTrue(contra.precioC == 4800L)
        val ok = compra.accionTrato(t.id, "aceptar")
        assertTrue(ok.estado == "aceptado")
        val ent = vende.accionTrato(t.id, "entregar")
        assertTrue(ent.estado == "entregado")
        println("mensajes: ${vende.trato(t.id).mensajes.map { it.de + ": " + it.texto }}")
        val conv = compra.asesor()
        println("motor: ${conv.motor}, mensajes previos: ${conv.mensajes.size}")
        val r = compra.preguntar("¿Qué viene para mi papelería?")
        assertTrue(r.respuesta.texto.isNotBlank())
        println("asesor: ${r.respuesta.texto.take(120)} · tarjetas=${r.respuesta.tarjetas.size}")
        println("resumen: ${compra.resumen()}")
    }
}
