package com.eter.mitienda.feature.mercado

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.decodeToImageBitmap

// ===================== Íconos (mismos trazos que la web) =====================

private val trazos = mapOf(
    "buscar" to "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14Zm5-2 4 4",
    "tienda" to "M4 9.5 5.5 4h13L20 9.5M4 9.5V20h16V9.5M4 9.5c0 1.4 1.1 2.5 2.7 2.5S9.3 10.9 9.3 9.5c0 1.4 1.2 2.5 2.7 2.5s2.7-1.1 2.7-2.5c0 1.4 1.1 2.5 2.6 2.5S20 10.9 20 9.5M10 20v-4.5h4V20",
    "video" to "M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v11a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 17.5v-11ZM10 9v6l5-3-5-3Z",
    "recibo" to "M6 3h12v18l-3-2-3 2-3-2-3 2V3Zm3 5h6m-6 4h6m-6 4h3",
    "etiqueta" to "M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9-9-9Zm5-4.5h.01",
    "mas" to "M12 5v14M5 12h14", "menos" to "M5 12h14",
    "usuario" to "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 8a7 7 0 0 1 14 0",
    "salir" to "M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 16l-4-4 4-4M6 12h10",
    "atras" to "M15 18l-6-6 6-6", "adelante" to "M9 6l6 6-6 6",
    "check" to "M5 12.5l4.5 4.5L19 7.5",
    "alerta" to "M12 8v5m0 3.5h.01M10.3 3.9 2.6 17.3A2 2 0 0 0 4.3 20.3h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z",
    "info" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-10v6m0-9.5h.01",
    "cerrar" to "M6 6l12 12M18 6 6 18",
    "camara" to "M4 8a2 2 0 0 1 2-2h2l1.5-2h5L16 6h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8Zm8 9a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z",
    "ubicacion" to "M12 21s-7-6.2-7-11.5a7 7 0 1 1 14 0C19 14.8 12 21 12 21Zm0-9a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z",
    "terminal" to "M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Zm1 3h8v5H8V6Zm0 8h.01M12 14h.01M16 14h.01M8 17.5h.01M12 17.5h.01M16 17.5h.01",
    "tarjeta" to "M3 7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Zm0 3h18M7 15h3",
    "volumen" to "M11 5 6 9H3v6h3l5 4V5Zm4.5 3.5a5 5 0 0 1 0 7M18 6a8.5 8.5 0 0 1 0 12",
    "silencio" to "M11 5 6 9H3v6h3l5 4V5Zm5 4.5 5 5m0-5-5 5",
    "play" to "M7 4.5v15l13-7.5L7 4.5Z", "pausa" to "M8 5v14M16 5v14",
    "basura" to "M4 7h16M10 11v6m4-6v6M6 7l1 13h10l1-13M9 7V4h6v3",
    "editar" to "M4 20h4L19 9l-4-4L4 16v4Zm9-13 4 4",
    "invitar" to "M15 19a6 6 0 0 0-12 0m6-8a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm10-3v6m3-3h-6",
    "reloj" to "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Zm0-13v4.5l3 2",
    "escudo" to "M12 3 5 6v5.5c0 4.4 3 8.2 7 9.5 4-1.3 7-5.1 7-9.5V6l-7-3Zm-3 9 2 2 4-4",
    "ojo" to "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Zm10 3a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z",
    "ojoNo" to "M3 3l18 18M10.6 5.1A10.6 10.6 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4.2M6.6 6.6A17.6 17.6 0 0 0 2 12s3.5 7 10 7c1.8 0 3.4-.5 4.8-1.3M9.9 9.9a3 3 0 0 0 4.2 4.2",
    "servidor" to "M4 5h16v6H4V5Zm0 8h16v6H4v-6Zm3-5h.01M7 16h.01",
    "canasta" to "M3 10h18l-2 10H5L3 10Zm5 0 4-6 4 6M9 14v3m3-3v3m3-3v3",
    "hoja" to "M5 19C5 10 10 5 20 4c-1 10-6 15-15 15Zm0 0 8-8",
    "camisa" to "M8 3 3 6l2 5 2-1v11h10V10l2 1 2-5-5-3a4 4 0 0 1-8 0Z",
    "caja" to "M3 7.5 12 3l9 4.5v9L12 21l-9-4.5v-9Zm0 0 9 4.5m0 0 9-4.5M12 12v9",
    "lapiz" to "M4 20l1-4L16 5l3 3L8 19l-4 1Zm10-13 3 3",
    "casa" to "M3 11 12 4l9 7M5 9.5V20h5v-5h4v5h5V9.5",
    "herramienta" to "M14.5 6.5a4 4 0 0 1 5-5L17 4l.5 2.5L20 7l2.5-2.5a4 4 0 0 1-5 5L8 19a2.1 2.1 0 0 1-3-3l9.5-9.5Z",
    "todo" to "M4 4h7v7H4V4Zm9 0h7v7h-7V4ZM4 13h7v7H4v-7Zm9 0h7v7h-7v-7Z",
)

private val iconosCache = HashMap<String, ImageVector>()

/** Ícono de trazo (24×24). Decorativo: el nombre accesible lo pone quien lo usa. */
fun icono(nombre: String): ImageVector = iconosCache.getOrPut(nombre) {
    ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = PathParser().parsePathString(trazos[nombre] ?: trazos.getValue("etiqueta")).toNodes(),
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()
}

val TONOS = mapOf(
    "abarrotes" to Color(0xFF0E7C5A), "hecho-en-durango" to Color(0xFFA3461F), "ropa" to Color(0xFF8A4F7D), "mayoreo" to Color(0xFF6B5A2E),
    "papeleria" to Color(0xFF2D6A7A), "hogar" to Color(0xFF7A5B3A), "servicios" to Color(0xFF4C5D73), "otros" to Color(0xFF5C564D),
)
val ICONO_CATEGORIA = mapOf(
    "abarrotes" to "canasta", "hecho-en-durango" to "hoja", "ropa" to "camisa", "mayoreo" to "caja",
    "papeleria" to "lapiz", "hogar" to "casa", "servicios" to "herramienta", "otros" to "etiqueta",
)

// ===================== Avisos (snackbar) =====================

/** avisar(texto, esError). La pone MercadoApp con su SnackbarHost (se anuncia a TalkBack). */
val LocalAvisar = staticCompositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

// ===================== Botones =====================

enum class Variante { Primario, Secundario, Fantasma, Peligro }

@Composable
fun Boton(
    texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, variante: Variante = Variante.Primario,
    icono: String? = null, cargando: Boolean = false, habilitado: Boolean = true, chico: Boolean = false,
) {
    val c = M.c
    val activo = habilitado && !cargando
    val (fondo, frente, borde) = when (variante) {
        Variante.Primario -> Triple(c.marca, c.sobreMarca, null)
        Variante.Secundario -> Triple(c.superficie, c.texto, c.bordeFuerte)
        Variante.Fantasma -> Triple(Color.Transparent, c.marca, null)
        Variante.Peligro -> Triple(c.superficie, c.error, c.error.copy(alpha = .45f))
    }
    val interaccion = remember { MutableInteractionSource() }
    val presionado by interaccion.collectIsPressedAsState()
    Surface(
        onClick = onClick, enabled = activo, interactionSource = interaccion,
        shape = RoundedCornerShape(E.radioControl), color = fondo, contentColor = frente,
        border = borde?.let { BorderStroke(1.dp, it) },
        modifier = modifier
            .heightIn(min = if (chico) 40.dp else E.tactil)
            .scale(if (presionado) .98f else 1f)
            .semantics {
                if (cargando) stateDescription = "Cargando"
            }
            .then(if (!activo) Modifier.background(Color.Transparent) else Modifier),
    ) {
        Row(
            Modifier.padding(horizontal = if (chico) E.x3 else E.x4).heightIn(min = if (chico) 40.dp else E.tactil).let { if (!activo) it.background(Color.Transparent) else it },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            val alfa = if (activo || cargando) 1f else .5f
            if (cargando) CircularProgressIndicator(Modifier.size(18.dp), color = frente, strokeWidth = 2.dp)
            else if (icono != null) Icon(icono(icono), null, Modifier.size(20.dp), tint = frente.copy(alpha = alfa))
            if (cargando || icono != null) Spacer(Modifier.width(E.x2))
            Text(texto, style = if (chico) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge, color = frente.copy(alpha = alfa), maxLines = 1)
        }
    }
}

@Composable
fun BotonIcono(etiqueta: String, icono: String, onClick: () -> Unit, modifier: Modifier = Modifier, habilitado: Boolean = true, tinte: Color = M.c.texto2) {
    IconButton(onClick = onClick, enabled = habilitado, modifier = modifier.size(E.tactil)) {
        Icon(icono(icono), contentDescription = etiqueta, tint = if (habilitado) tinte else tinte.copy(alpha = .4f), modifier = Modifier.size(22.dp))
    }
}

// ===================== Campos =====================

@Composable
fun Campo(
    etiqueta: String, valor: String, onCambio: (String) -> Unit, modifier: Modifier = Modifier,
    error: String? = null, ayuda: String? = null, contrasena: Boolean = false, teclado: KeyboardType = KeyboardType.Text,
    lineas: Int = 1, habilitado: Boolean = true, opcional: Boolean = false, placeholder: String? = null,
) {
    val c = M.c
    var ver by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(E.x1)) {
        OutlinedTextField(
            value = valor, onValueChange = onCambio, enabled = habilitado,
            label = { Text(if (opcional) "$etiqueta (opcional)" else etiqueta) },
            placeholder = placeholder?.let { { Text(it, color = c.texto3) } },
            isError = error != null, singleLine = lineas == 1, minLines = lineas,
            visualTransformation = if (contrasena && !ver) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (contrasena) KeyboardType.Password else teclado),
            trailingIcon = if (contrasena) {
                { BotonIcono(if (ver) "Ocultar contraseña" else "Mostrar contraseña", if (ver) "ojoNo" else "ojo", { ver = !ver }) }
            } else null,
            shape = RoundedCornerShape(E.radioControl),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.marca, unfocusedBorderColor = c.bordeFuerte, errorBorderColor = c.error,
                focusedLabelColor = c.marca, cursorColor = c.marca, focusedContainerColor = c.superficie, unfocusedContainerColor = c.superficie,
                disabledContainerColor = c.superficie2,
            ),
            supportingText = when {
                error != null -> { { Text(error, color = c.error) } }
                ayuda != null -> { { Text(ayuda, color = c.texto3) } }
                else -> null
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ===================== Superficies =====================

@Composable
fun Tarjeta(modifier: Modifier = Modifier, padding: Dp = E.x4, contenido: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(E.radioCard), color = M.c.superficie, border = BorderStroke(1.dp, M.c.borde), modifier = modifier) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(E.x3)) { contenido() }
    }
}

@Composable
fun Titulo(texto: String, modifier: Modifier = Modifier, grande: Boolean = true) {
    Text(texto, style = if (grande) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
        color = M.c.texto, modifier = modifier.semantics { heading() })
}

@Composable
fun Insignia(texto: String, tono: String = "neutro", modifier: Modifier = Modifier) {
    val c = M.c
    val (fondo, frente) = when (tono) {
        "exito" -> c.exitoSuave to c.exito
        "aviso" -> c.avisoSuave to c.aviso
        "error" -> c.errorSuave to c.error
        "info" -> c.infoSuave to c.info
        "acento" -> c.acentoSuave to c.acento
        else -> c.superficie2 to c.texto2
    }
    Row(
        modifier.clip(CircleShape).background(fondo).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(frente))
        Text(texto, style = MaterialTheme.typography.labelSmall, color = frente, maxLines = 1)
    }
}

@Composable
fun Chip(texto: String, seleccionado: Boolean, onClick: () -> Unit, icono: String? = null) {
    val c = M.c
    Surface(
        onClick = onClick, shape = CircleShape,
        color = if (seleccionado) c.texto else c.superficie, contentColor = if (seleccionado) c.superficie else c.texto2,
        border = BorderStroke(1.dp, if (seleccionado) c.texto else c.borde),
        modifier = Modifier.heightIn(min = 40.dp).semantics {
            stateDescription = if (seleccionado) "Seleccionado" else "No seleccionado"
            role = Role.Button
        },
    ) {
        Row(Modifier.padding(horizontal = E.x4, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x2)) {
            if (icono != null) Icon(icono(icono), null, Modifier.size(18.dp))
            Text(texto, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun Alerta(texto: String, tipo: String = "info", modifier: Modifier = Modifier) {
    val c = M.c
    val (fondo, tinte, ic) = when (tipo) {
        "error" -> Triple(c.errorSuave, c.error, "alerta")
        "exito" -> Triple(c.exitoSuave, c.exito, "check")
        "aviso" -> Triple(c.avisoSuave, c.aviso, "alerta")
        else -> Triple(c.infoSuave, c.info, "info")
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(E.radioControl)).background(fondo).padding(E.x3)
            .semantics { if (tipo == "error") liveRegion = LiveRegionMode.Assertive },
        horizontalArrangement = Arrangement.spacedBy(E.x3),
    ) {
        Icon(icono(ic), null, Modifier.size(20.dp), tint = tinte)
        Text(texto, style = MaterialTheme.typography.bodyMedium, color = c.texto)
    }
}

@Composable
fun Avatar(nombre: String, tam: Dp = 44.dp) {
    Box(Modifier.size(tam).clip(CircleShape).background(M.c.acentoSuave).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(iniciales(nombre).ifEmpty { "·" }, color = M.c.acento, fontWeight = FontWeight.Bold,
            style = if (tam > 50.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelMedium)
    }
}

// ===================== Carga con esqueleto =====================

@Composable
fun Esqueleto(modifier: Modifier = Modifier, forma: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp)) {
    val c = M.c
    val t = rememberInfiniteTransition(label = "esqueleto")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "brillo")
    Box(
        modifier.clip(forma).background(
            Brush.linearGradient(listOf(c.esqueleto, c.esqueletoBrillo, c.esqueleto),
                start = androidx.compose.ui.geometry.Offset(x * 600f, 0f), end = androidx.compose.ui.geometry.Offset(x * 600f + 600f, 0f)),
        ),
    )
}

/** Contenedor que anuncia "Cargando…" a TalkBack mientras se ve el esqueleto. */
@Composable
fun Cargando(texto: String = "Cargando…", modifier: Modifier = Modifier, contenido: @Composable () -> Unit) {
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = texto; liveRegion = LiveRegionMode.Polite }) { contenido() }
}

@Composable
fun EsqueletoProducto(modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(E.radioCard), color = M.c.superficie, border = BorderStroke(1.dp, M.c.borde), modifier = modifier) {
        Column {
            Esqueleto(Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(0.dp))
            Column(Modifier.padding(E.x3), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Esqueleto(Modifier.fillMaxWidth(.45f).height(22.dp))
                Esqueleto(Modifier.fillMaxWidth(.9f).height(14.dp))
                Esqueleto(Modifier.fillMaxWidth(.6f).height(14.dp))
            }
        }
    }
}

@Composable
fun EsqueletoRenglones(n: Int = 4) {
    Cargando {
        Column(verticalArrangement = Arrangement.spacedBy(E.x2)) {
            repeat(n) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(E.radioCard)).background(M.c.superficie).padding(E.x3),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x3)) {
                    Esqueleto(Modifier.size(56.dp), RoundedCornerShape(E.radioControl))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Esqueleto(Modifier.fillMaxWidth(.6f).height(14.dp)); Esqueleto(Modifier.fillMaxWidth(.35f).height(12.dp))
                    }
                    Esqueleto(Modifier.width(72.dp).height(24.dp), CircleShape)
                }
            }
        }
    }
}

// ===================== Estados =====================

@Composable
fun EstadoVacio(titulo: String, texto: String? = null, icono: String = "info", accion: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = E.x12, horizontal = E.x4), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(E.x3)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(M.c.superficie2), contentAlignment = Alignment.Center) {
            Icon(icono(icono), null, Modifier.size(28.dp), tint = M.c.texto2)
        }
        Text(titulo, style = MaterialTheme.typography.titleLarge, color = M.c.texto, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (texto != null) Text(texto, style = MaterialTheme.typography.bodyMedium, color = M.c.texto2, textAlign = TextAlign.Center)
        accion?.invoke()
    }
}

@Composable
fun EstadoError(error: String, reintentar: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = E.x12, horizontal = E.x4).semantics { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(E.x3)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(M.c.errorSuave), contentAlignment = Alignment.Center) {
            Icon(icono("alerta"), null, Modifier.size(28.dp), tint = M.c.error)
        }
        Text("No pudimos cargar esto", style = MaterialTheme.typography.titleLarge, color = M.c.texto)
        Text(error, style = MaterialTheme.typography.bodyMedium, color = M.c.texto2, textAlign = TextAlign.Center)
        if (reintentar != null) Boton("Reintentar", reintentar, variante = Variante.Secundario, icono = "adelante")
    }
}

// ===================== Datos remotos =====================

class Consulta<T> internal constructor() {
    var datos by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var cargando by mutableStateOf(true)
    internal var vuelta by mutableIntStateOf(0)
    fun recargar() { vuelta++ }
}

/** Carga datos al entrar (y al cambiar [claves]); recargar() vuelve a pedirlos. */
@Composable
fun <T> rememberConsulta(vararg claves: Any?, cargar: suspend () -> T): Consulta<T> {
    val q = remember(*claves) { Consulta<T>() }
    LaunchedEffect(*claves, q.vuelta) {
        q.cargando = true; q.error = null
        try { q.datos = cargar() } catch (e: Exception) { q.error = e.message ?: "Algo salió mal" }
        q.cargando = false
    }
    return q
}

/** Imagen protegida del mercado (se pide con el token). Esqueleto mientras baja; ícono de categoría si no hay foto. */
@Composable
fun ImagenMercado(api: MercadoApi, foto: Foto?, categoria: String, modifier: Modifier = Modifier, conAlt: Boolean = true) {
    var bmp by remember(foto?.url) { mutableStateOf<ImageBitmap?>(null) }
    var fallo by remember(foto?.url) { mutableStateOf(false) }
    LaunchedEffect(foto?.url) {
        if (foto != null) runCatching { api.bajar(foto.url).decodeToImageBitmap() }.onSuccess { bmp = it }.onFailure { fallo = true }
    }
    Box(modifier.background(M.c.superficie2), contentAlignment = Alignment.Center) {
        when {
            foto != null && bmp != null -> Image(bmp!!, if (conAlt) foto.alt else null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            foto != null && !fallo -> Esqueleto(Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            else -> {
                val tono = TONOS[categoria] ?: TONOS.getValue("otros")
                Box(Modifier.fillMaxSize().background(tono.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                    Icon(icono(ICONO_CATEGORIA[categoria] ?: "etiqueta"), null, Modifier.fillMaxSize(.42f), tint = tono.copy(alpha = .6f))
                }
            }
        }
    }
}

@Composable
fun InsigniaExistencias(p: Publicacion, modifier: Modifier = Modifier) {
    when {
        p.existencias == null -> {}
        p.existencias == 0 -> Insignia("Agotado", "error", modifier)
        p.existencias <= 5 -> Insignia("Quedan ${p.existencias}", "aviso", modifier)
    }
}

@Composable
fun TarjetaProducto(api: MercadoApi, p: Publicacion, onClick: () -> Unit, modifier: Modifier = Modifier, patrocinado: Boolean = false) {
    val c = M.c
    val lugar = p.distanciaKm?.let { d -> "${p.vendedor.colonia} · " + if (d < 1) "a menos de 1 km" else "a ${d} km" } ?: p.vendedor.colonia
    Surface(
        onClick = onClick, shape = RoundedCornerShape(E.radioCard), color = c.superficie,
        border = BorderStroke(1.dp, if (patrocinado) c.acento.copy(alpha = .35f) else c.borde),
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = (if (patrocinado) "Patrocinado. " else "") + "${p.titulo}, ${pesos(p.precioC)} por ${unidad(p.unidad)}, ${p.vendedor.nombre}, $lugar"
        },
    ) {
        Column {
            Box {
                ImagenMercado(api, p.foto, p.categoria, Modifier.fillMaxWidth().aspectRatio(1f), conAlt = false)
                InsigniaExistencias(p, Modifier.padding(E.x2))
            }
            Column(Modifier.padding(E.x3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (patrocinado) Text("Patrocinado", style = MaterialTheme.typography.labelSmall, color = c.acento)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(pesos(p.precioC), style = MaterialTheme.typography.titleLarge, color = c.texto)
                    Text(" / ${unidad(p.unidad)}", style = MaterialTheme.typography.bodySmall, color = c.texto3)
                }
                Text(p.titulo, style = MaterialTheme.typography.bodyMedium, color = c.texto, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.vendedor.nombre, style = MaterialTheme.typography.bodySmall, color = c.texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lugar, style = MaterialTheme.typography.bodySmall, color = c.texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Separador() { Box(Modifier.fillMaxWidth().height(1.dp).background(M.c.borde)) }

/** Encabezado de pantalla interna con botón atrás. */
@Composable
fun BarraAtras(titulo: String, onAtras: () -> Unit, acciones: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = E.x1, vertical = E.x1), verticalAlignment = Alignment.CenterVertically) {
        BotonIcono("Atrás", "atras", onAtras, tinte = M.c.texto)
        Text(titulo, style = MaterialTheme.typography.titleMedium, color = M.c.texto, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() })
        acciones()
    }
}

val RellenoPantalla = PaddingValues(horizontal = E.x4, vertical = E.x4)

@Composable
fun Etiqueta(texto: String) = Text(texto, style = MaterialTheme.typography.labelMedium, color = M.c.texto2)

@Composable
fun Fila(modifier: Modifier = Modifier, contenido: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, content = contenido)

@Composable
fun Clicable(onClick: () -> Unit, modifier: Modifier = Modifier, contenido: @Composable () -> Unit) =
    Box(modifier.clickable(role = Role.Button, onClick = onClick)) { contenido() }

@Composable
fun Borde(modifier: Modifier = Modifier) = Box(modifier.border(1.dp, M.c.borde, RoundedCornerShape(E.radioControl)))

/** Banner de «Negocio destacado» (plan Premium). Ocupa su propio renglón: nunca se encima. */
@Composable
fun BannerPatrocinado(api: MercadoApi, b: Patrocinado, onClick: () -> Unit) {
    val c = M.c
    val p = b.publicacion
    Surface(
        onClick = onClick, shape = RoundedCornerShape(E.radioCard), color = c.acentoSuave,
        border = BorderStroke(1.dp, c.acento.copy(alpha = .25f)),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "Anuncio patrocinado de ${p.vendedor.nombre}: ${b.mensaje.ifBlank { p.titulo }}. ${p.titulo}, ${pesos(p.precioC)}"
        },
    ) {
        Row(Modifier.padding(E.x3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(E.x3)) {
            ImagenMercado(api, p.foto, p.categoria, Modifier.size(88.dp).clip(RoundedCornerShape(E.radioControl)), conAlt = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Patrocinado · ${p.vendedor.nombre}", style = MaterialTheme.typography.labelSmall, color = c.acento, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(b.mensaje.ifBlank { p.titulo }, style = MaterialTheme.typography.titleMedium, color = c.texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${p.titulo} · ${pesos(p.precioC)} / ${unidad(p.unidad)}", style = MaterialTheme.typography.bodySmall, color = c.texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
