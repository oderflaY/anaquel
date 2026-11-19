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
}
