package com.eter.mitienda.feature.mercado

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun PaginaCuenta(sesion: MercadoSesion, ir: Ir) {
    val c = sesion.cuenta ?: return
    val api = sesion.api
    val alcance = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        BarraAtras("Mi cuenta", ir::atras)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = E.x4).padding(bottom = E.x8), verticalArrangement = Arrangement.spacedBy(E.x4)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x3)) {
                Avatar(c.nombre, 72.dp())
                Column(verticalArrangement = Arrangement.spacedBy(E.x1)) {
                    Titulo(c.nombre, grande = false)
                    Text(c.email, style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                    if (c.verificada) Insignia("Cuenta verificada", "exito") else Insignia("En revisión", "aviso")
                }
            }
            if (c.verificada) Invitaciones(api)
            Perfil(sesion)
            Contrasena(api)
            Sesiones(api)
            if (c.rol == "admin") Admin(api)
            Boton("Salir", { alcance.launch { sesion.salir() } }, Modifier.fillMaxWidth(), Variante.Secundario, icono = "salir")
        }
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

@Composable
private fun Invitaciones(api: MercadoApi) {
    val q = rememberConsulta { api.invitaciones() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    val portapapeles = LocalClipboardManager.current
    var codigo by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Tarjeta {
        Titulo("Invitar a otro negocio", grande = false)
        Text("Solo entra quien recibe un código de un negocio verificado. Cada código sirve una vez y vence en 7 días.", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
        codigo?.let { cod ->
            Alerta("Código: $cod\nGuárdalo: por seguridad no lo volveremos a mostrar.", "exito")
            Boton("Copiar código", { portapapeles.setText(AnnotatedString(cod)); avisar("Código copiado", false) }, variante = Variante.Fantasma, chico = true)
        }
        error?.let { Alerta(it, "error") }
        Boton("Crear código de invitación", {
            ocupado = true; error = null
            alcance.launch { try { codigo = api.crearInvitacion().codigo; q.recargar() } catch (e: MercadoError) { error = e.message }; ocupado = false }
        }, Modifier.fillMaxWidth(), Variante.Secundario, icono = "invitar", cargando = ocupado)
        q.datos.orEmpty().forEach { i ->
            Fila {
                Column(Modifier.weight(1f)) {
                    Text("Creada ${fecha(i.creada)}", style = MaterialTheme.typography.bodyMedium, color = M.c.texto)
                    Text(if (i.usada != null) "La usó ${i.usadaPor ?: "un negocio"}" else "Vence ${fecha(i.expira)}", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                }
                if (i.usada != null) Insignia("Usada", "exito") else Insignia("Disponible", "info")
            }
        }
    }
}

@Composable
private fun Perfil(sesion: MercadoSesion) {
    val c = sesion.cuenta ?: return
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var nombre by remember { mutableStateOf(c.nombre) }
    var colonia by remember { mutableStateOf(c.colonia) }
    var tel by remember { mutableStateOf(c.telefono) }
    var desc by remember { mutableStateOf(c.descripcion) }
    var error by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    Tarjeta {
        Titulo("Datos del negocio", grande = false)
        Campo("Nombre", nombre, { nombre = it.take(80) })
        Campo("Colonia", colonia, { colonia = it.take(80) })
        Campo("WhatsApp", tel, { tel = it.take(15) }, teclado = KeyboardType.Phone, opcional = true)
        Campo("Descripción", desc, { desc = it.take(600) }, lineas = 3, opcional = true)
        error?.let { Alerta(it, "error") }
        Boton("Guardar", {
            ocupado = true; error = null
            alcance.launch {
                try { sesion.cuenta = sesion.api.actualizarYo(nombre, desc, tel, colonia); avisar("Datos guardados", false) } catch (e: MercadoError) { error = e.message }
                ocupado = false
            }
        }, variante = Variante.Secundario, cargando = ocupado)
    }
}

@Composable
private fun Contrasena(api: MercadoApi) {
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var actual by remember { mutableStateOf("") }
    var nueva by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    Tarjeta {
        Titulo("Cambiar contraseña", grande = false)
        Campo("Contraseña actual", actual, { actual = it }, contrasena = true)
        Campo("Nueva contraseña", nueva, { nueva = it }, contrasena = true, ayuda = "Mínimo 10 caracteres.")
        error?.let { Alerta(it, "error") }
        Boton("Cambiar contraseña", {
            if (nueva.length < 10) { error = "La nueva contraseña debe tener al menos 10 caracteres"; return@Boton }
            ocupado = true; error = null
            alcance.launch {
                try { api.cambiarPass(actual, nueva); actual = ""; nueva = ""; avisar("Contraseña cambiada. Cerramos tus otras sesiones.", false) } catch (e: MercadoError) { error = e.message }
                ocupado = false
            }
        }, variante = Variante.Secundario, cargando = ocupado, habilitado = actual.isNotEmpty() && nueva.isNotEmpty())
    }
}

@Composable
private fun Sesiones(api: MercadoApi) {
    val q = rememberConsulta { api.sesiones() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    Tarjeta {
        Fila {
            Titulo("Dónde tienes sesión", grande = false)
            if ((q.datos?.size ?: 0) > 1) Boton("Cerrar las demás", {
                alcance.launch { runCatching { api.cerrarOtras() }.onSuccess { avisar("Cerramos tus otras sesiones", false); q.recargar() } }
            }, variante = Variante.Fantasma, chico = true)
        }
        when {
            q.cargando -> EsqueletoRenglones(1)
            q.error != null -> Alerta(q.error!!, "error")
            else -> q.datos.orEmpty().forEach { s ->
                Fila {
                    Column(Modifier.weight(1f)) {
                        Text(if (s.tipo == "app") "App del celular" else "Navegador", style = MaterialTheme.typography.bodyMedium, color = M.c.texto)
                        Text("Último uso ${fecha(s.ultimoUso)}", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                    }
                    if (s.actual) Insignia("Esta sesión", "exito")
                }
            }
        }
    }
}

@Composable
private fun Admin(api: MercadoApi) {
    val q = rememberConsulta { api.pendientes() }
    val avisar = LocalAvisar.current
    val alcance = rememberCoroutineScope()
    var ocupado by remember { mutableStateOf<String?>(null) }
    Tarjeta {
        Titulo("Cuentas por verificar", grande = false)
        Text("Confirma que el negocio existe y está en Durango antes de darle acceso.", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
        when {
            q.cargando -> EsqueletoRenglones(2)
            q.error != null -> Alerta(q.error!!, "error")
            q.datos.isNullOrEmpty() -> EstadoVacio("No hay cuentas pendientes", icono = "check")
            else -> q.datos!!.forEach { x ->
                Column(verticalArrangement = Arrangement.spacedBy(E.x2)) {
                    Text(x.nombre, style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                    Text("${x.email} · ${x.colonia}", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                    Row(horizontalArrangement = Arrangement.spacedBy(E.x2)) {
                        for ((accion, txt, v) in listOf(Triple("suspender", "Rechazar", Variante.Peligro), Triple("verificar", "Verificar", Variante.Primario))) {
                            Boton(txt, {
                                ocupado = "${x.id}$accion"
                                alcance.launch {
                                    try { api.adminAccion(x.id, accion); q.datos = q.datos?.filterNot { it.id == x.id }; avisar(if (accion == "verificar") "Cuenta verificada" else "Cuenta rechazada", false) }
                                    catch (e: MercadoError) { avisar(e.message ?: "", true) }
                                    ocupado = null
                                }
                            }, variante = v, chico = true, cargando = ocupado == "${x.id}$accion", habilitado = ocupado == null)
                        }
                    }
                    Separador()
                }
            }
        }
    }
}
