package com.eter.mitienda.feature.mercado

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun Acceso(sesion: MercadoSesion) {
    var registro by remember { mutableStateOf(false) }
    if (registro) Registro(sesion) { registro = false } else Entrar(sesion) { registro = true }
}

@Composable
private fun Entrar(sesion: MercadoSesion, irRegistro: () -> Unit) {
    val api = sesion.api
    val alcance = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Pair<String, String?>?>(null) } // mensaje, campo
    var ocupado by remember { mutableStateOf<String?>(null) }
    var servidorAbierto by remember { mutableStateOf(false) }
    var vuelta by remember { mutableStateOf(0) }
    val config = rememberConsulta(vuelta) { api.config() }

    fun entrar(e: String, p: String, quien: String) {
        ocupado = quien; error = null
        alcance.launch {
            try { sesion.entro(api.entrar(e, p)) }
            catch (ex: MercadoError) { error = (ex.message ?: "") to (if (ex.clave == "credenciales") "pass" else null) }
            ocupado = null
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(E.x4), verticalArrangement = Arrangement.spacedBy(E.x6)) {
        Column(verticalArrangement = Arrangement.spacedBy(E.x3)) {
            Insignia("Solo negocios de Durango", "acento")
            Titulo("Compra y vende cerca de ti, entre negocios de confianza")
            listOf("escudo" to "Cada cuenta se verifica antes de entrar.",
                "ubicacion" to "Proveedores de tu ciudad, con la distancia a tu negocio.",
                "tarjeta" to "Aparta y paga con tarjeta en la terminal del vendedor.").forEach { (ic, t) ->
                Row(horizontalArrangement = Arrangement.spacedBy(E.x3)) {
                    Icon(icono(ic), null, Modifier.size(20.dp), tint = M.c.marca)
                    Text(t, style = MaterialTheme.typography.bodyMedium, color = M.c.texto2)
                }
            }
        }
        Tarjeta {
            Titulo("Entrar al mercado", grande = false)
            Campo("Correo", email, { email = it; error = null }, teclado = KeyboardType.Email,
                error = error?.takeIf { it.second == "email" }?.first)
            Campo("Contraseña", pass, { pass = it; error = null }, contrasena = true, error = error?.takeIf { it.second == "pass" }?.first)
            error?.takeIf { it.second == null }?.let { Alerta(it.first, "error") }
            Boton("Entrar", {
                when {
                    email.isBlank() -> error = "Escribe tu correo" to "email"
                    pass.isEmpty() -> error = "Escribe tu contraseña" to "pass"
                    else -> entrar(email, pass, "form")
                }
            }, Modifier.fillMaxWidth(), cargando = ocupado == "form", habilitado = ocupado == null)
            Boton("¿Te invitaron? Crea la cuenta de tu negocio", irRegistro, Modifier.fillMaxWidth(), Variante.Fantasma, chico = true)
        }
        when {
            config.cargando -> Cargando { Column(verticalArrangement = Arrangement.spacedBy(E.x2)) { repeat(3) { Esqueleto(Modifier.fillMaxWidth().size(56.dp)) } } }
            config.error != null -> Alerta("No hay conexión con el mercado (${api.base}). Revisa que el servidor esté encendido o cambia la dirección abajo.", "aviso")
            config.datos?.demo == true -> Tarjeta {
                Titulo("Probar con un negocio de demostración", grande = false)
                Text("Contraseña de todas: demo1234", style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                config.datos!!.cuentasDemo.forEach { d ->
                    Surface(onClick = { entrar(d.email, "demo1234", d.email) }, enabled = ocupado == null, shape = androidx.compose.foundation.shape.RoundedCornerShape(E.radioControl),
                        color = M.c.superficie, border = androidx.compose.foundation.BorderStroke(1.dp, M.c.borde)) {
                        Row(Modifier.fillMaxWidth().padding(E.x2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x3)) {
                            Avatar(d.nombre, 36.dp)
                            Column(Modifier.weight(1f)) {
                                Text(d.nombre, style = MaterialTheme.typography.labelLarge, color = M.c.texto)
                                Text(d.que, style = MaterialTheme.typography.bodySmall, color = M.c.texto3)
                            }
                            if (ocupado == d.email) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = M.c.marca)
                            else Icon(icono("adelante"), null, Modifier.size(20.dp), tint = M.c.texto3)
                        }
                    }
                }
            }
        }
        // Dirección del servidor (desarrollo local hoy; VPS después).
        if (servidorAbierto) {
            var url by remember { mutableStateOf(api.base) }
            Tarjeta {
                Titulo("Servidor del mercado", grande = false)
                Campo("Dirección", url, { url = it }, teclado = KeyboardType.Uri, ayuda = "Emulador: http://10.0.2.2:8080/anaquel · Celular: la IP de la computadora")
                Row(horizontalArrangement = Arrangement.spacedBy(E.x2)) {
                    Boton("Cancelar", { servidorAbierto = false }, variante = Variante.Secundario, chico = true)
                    Boton("Guardar", { sesion.cambiarServidor(url); servidorAbierto = false; vuelta++ }, chico = true,
                        habilitado = url.startsWith("http://") || url.startsWith("https://"))
                }
            }
        } else {
            Boton("Servidor: ${api.base}", { servidorAbierto = true }, Modifier.fillMaxWidth(), Variante.Fantasma, icono = "servidor", chico = true)
        }
    }
}

private val GIROS = listOf("abarrotes" to "Abarrotes / tiendita", "ropa" to "Ropa y accesorios", "papeleria" to "Papelería",
    "ferreteria" to "Ferretería", "farmacia" to "Farmacia", "dulceria" to "Dulcería y fiestas", "comida" to "Comida",
    "mayoreo" to "Proveedor / mayoreo", "productor" to "Productor local", "servicios" to "Servicios", "otro" to "Otro")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Registro(sesion: MercadoSesion, volver: () -> Unit) {
    val alcance = rememberCoroutineScope()
    var codigo by remember { mutableStateOf("") }
    var nombre by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var tel by remember { mutableStateOf("") }
    var colonia by remember { mutableStateOf("") }
    var giro by remember { mutableStateOf("abarrotes") }
    var errores by remember { mutableStateOf(mapOf<String, String>()) }
    var general by remember { mutableStateOf<String?>(null) }
    var ocupado by remember { mutableStateOf(false) }

    fun enviar() {
        val e = buildMap {
            if (codigo.filter { it.isLetterOrDigit() }.length != 10) put("codigo", "El código tiene 10 letras y números")
            if (nombre.trim().length < 2) put("nombre", "Escribe el nombre de tu negocio")
            if (!Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""").matches(email.trim())) put("email", "Revisa tu correo")
            if (pass.length < 10) put("pass", "Usa al menos 10 caracteres")
            if (tel.isNotBlank() && tel.filter { it.isDigit() }.length != 10) put("tel", "El teléfono lleva 10 dígitos")
            if (colonia.isBlank()) put("colonia", "Escribe tu colonia o localidad")
        }
        errores = e; general = null
        if (e.isNotEmpty()) return
        ocupado = true
        alcance.launch {
            try { sesion.entro(sesion.api.registrar(codigo, nombre, email, pass, tel, colonia, giro)) }
            catch (ex: MercadoError) {
                when {
                    ex.clave == "codigo_invalido" -> errores = mapOf("codigo" to (ex.message ?: ""))
                    ex.clave == "correo_registrado" -> errores = mapOf("email" to (ex.message ?: ""))
                    ex.message?.contains("contraseña", true) == true -> errores = mapOf("pass" to (ex.message ?: ""))
                    else -> general = ex.message
                }
            }
            ocupado = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BarraAtras("Crear cuenta", volver)
        Column(Modifier.padding(horizontal = E.x4).padding(bottom = E.x8).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(E.x3)) {
            Text("Necesitas un código de invitación de otro negocio del mercado. Después revisamos tus datos y te damos acceso.",
                style = MaterialTheme.typography.bodyMedium, color = M.c.texto2)
            Campo("Código de invitación", codigo, { codigo = it.uppercase(); errores = errores - "codigo" }, error = errores["codigo"], ayuda = "Ejemplo: K7M2Q-9XH4T")
            Campo("Nombre del negocio", nombre, { nombre = it; errores = errores - "nombre" }, error = errores["nombre"])
            var abierto by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(abierto, { abierto = it }) {
                androidx.compose.material3.OutlinedTextField(
                    GIROS.first { it.first == giro }.second, {}, readOnly = true, label = { Text("Giro") },
                    trailingIcon = { Icon(icono("adelante"), null, Modifier.size(18.dp)) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(E.radioControl),
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(abierto, { abierto = false }) {
                    GIROS.forEach { (k, n) -> DropdownMenuItem({ Text(n) }, { giro = k; abierto = false }) }
                }
            }
            Campo("Correo", email, { email = it; errores = errores - "email" }, teclado = KeyboardType.Email, error = errores["email"])
            Campo("WhatsApp", tel, { tel = it; errores = errores - "tel" }, teclado = KeyboardType.Phone, opcional = true, error = errores["tel"])
            Campo("Contraseña", pass, { pass = it; errores = errores - "pass" }, contrasena = true, error = errores["pass"],
                ayuda = "Mínimo 10 caracteres. Una frase corta es fácil de recordar.")
            Campo("Colonia o localidad", colonia, { colonia = it; errores = errores - "colonia" }, error = errores["colonia"], placeholder = "Zona Centro")
            general?.let { Alerta(it, "error") }
            Boton("Crear cuenta", ::enviar, Modifier.fillMaxWidth(), cargando = ocupado)
        }
    }
}

/** Protocolo de acceso: la cuenta existe, pero aún no entra al mercado. */
@Composable
fun Revision(cuenta: Cuenta, salir: () -> Unit) {
    val suspendida = cuenta.estado == "suspendida"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(E.x4), verticalArrangement = Arrangement.spacedBy(E.x4)) {
        EstadoVacio(
            if (suspendida) "Tu cuenta está suspendida" else "Tu cuenta está en revisión",
            if (suspendida) "Escríbenos para revisar tu caso. Mientras tanto no puedes comprar ni vender."
            else "Hola, ${cuenta.nombre}. Solo negocios verificados de Durango entran al mercado. Te avisaremos a ${cuenta.email}.",
            if (suspendida) "alerta" else "reloj",
        )
        if (!suspendida) Tarjeta {
            Linea(listOf("Recibimos tu registro" to "hecho", "Revisamos que tu negocio esté en Durango" to "actual", "Te damos acceso al mercado" to ""))
        }
        Boton("Salir", salir, Modifier.fillMaxWidth(), Variante.Secundario, icono = "salir")
    }
}

/** Línea de tiempo: estado "hecho", "actual", "cancelado" o "" (pendiente). */
@Composable
fun Linea(pasos: List<Pair<String, String>>, detalles: List<String?> = emptyList()) {
    val c = M.c
    Column {
        pasos.forEachIndexed { i, (t, estado) ->
            val (fondo, frente) = when (estado) {
                "hecho" -> c.marca to c.sobreMarca
                "actual" -> c.marcaSuave to c.marca
                "cancelado" -> c.errorSuave to c.error
                else -> c.superficie2 to c.texto3
            }
            val leido = when (estado) { "hecho" -> "listo"; "actual" -> "en curso"; "cancelado" -> "cancelado"; else -> "pendiente" }
            Row(
                Modifier.semantics(mergeDescendants = true) { contentDescription = "Paso ${i + 1}: $t, $leido" + (detalles.getOrNull(i)?.let { ". $it" } ?: "") },
                horizontalArrangement = Arrangement.spacedBy(E.x3),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(fondo), contentAlignment = Alignment.Center) {
                        when (estado) {
                            "hecho" -> Icon(icono("check"), null, Modifier.size(14.dp), tint = frente)
                            "cancelado" -> Icon(icono("cerrar"), null, Modifier.size(14.dp), tint = frente)
                            else -> Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = frente)
                        }
                    }
                    if (i < pasos.lastIndex) Box(Modifier.width(2.dp).height(28.dp).background(if (estado == "hecho") c.marca else c.borde))
                }
                Column(Modifier.padding(bottom = E.x2)) {
                    Text(t, style = MaterialTheme.typography.labelLarge, color = if (estado.isEmpty()) c.texto3 else c.texto)
                    detalles.getOrNull(i)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.texto3) }
                }
            }
        }
    }
}
