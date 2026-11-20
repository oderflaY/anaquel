package com.eter.mitienda.feature.anaquel

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.util.formatPrice

// Cada publicación del feed se presenta como un anuncio de marca: fondo con
// formas en movimiento, producto en una «tarjeta de vidrio», precio grande con
// el precio normal tachado, sello de descuento, barra de existencias y un botón
// de compra con brillo. Todo con íconos (sin emojis).

/** Paletas de anuncio: [fondo claro, fondo oscuro, acento]. */
private val paletas = listOf(
    Triple(Color(0xFF0E7C5A), Color(0xFF052A1F), Color(0xFFB8F5DC)),
    Triple(Color(0xFF2F5DA8), Color(0xFF0B1631), Color(0xFFBFD4FF)),
    Triple(Color(0xFF7B3FC4), Color(0xFF1D0B33), Color(0xFFE3C9FF)),
    Triple(Color(0xFFE0571B), Color(0xFF3A1204), Color(0xFFFFD9B8)),
    Triple(Color(0xFFC2185B), Color(0xFF33061A), Color(0xFFFFC7DD)),
    Triple(Color(0xFF00897B), Color(0xFF00241F), Color(0xFFB2F1E8)),
)
private val Dorado = Color(0xFFFFC94D)

/** Todo lo que se muestra en un anuncio, ya calculado. */
private class Anuncio(
    val etiqueta: String, val etiquetaIcono: ImageVector, val icono: ImageVector, val titulo: String,
    val tienda: TiendaCorta?, val km: Double?, val precio: Long?, val precioAntes: Long?, val precioTexto: String?,
    val condicion: String?, val descuento: Int?, val detalle: String?, val disponible: Long?, val accion: String,
    val destacados: List<Pair<String, Long>> = emptyList(), val foto: String? = null,
)

private fun armar(pub: Publicacion): Anuncio = when (pub) {
    is Publicacion.Oferta -> {
        val r = pub.r
        val mayoreo = (r.precioMayoreoC ?: 0) > 0
        val precio = if (mayoreo) r.precioMayoreoC else r.precioC?.takeIf { it > 0 }
        val antes = if (mayoreo && (r.precioC ?: 0) > (r.precioMayoreoC ?: 0)) r.precioC else null
        val desc = antes?.let { (((it - precio!!) * 100) / it).toInt() }?.takeIf { it >= 3 }
        val (icoEt, txtEt) = when (pub.etiqueta) {
            "Se te acaba" -> Ico.Alerta to "SE TE ACABA"
            "Promoción" -> Ico.Fuego to "PROMOCIÓN"
            "Te buscan" -> Ico.Buscar to "TE BUSCAN"
            else -> Ico.Oferta to "TE CONVIENE"
        }
        Anuncio(
            etiqueta = txtEt, etiquetaIcono = icoEt, icono = iconoDe(r.titulo, r.tipo), titulo = r.titulo, tienda = r.tienda, km = r.distanciaKm,
            precio = precio, precioAntes = antes, precioTexto = if (precio == null) r.precio?.takeIf { it.isNotBlank() } else null,
            condicion = (r.minimoMayoreo ?: 0).takeIf { mayoreo && it > 0 }?.let { "Mayoreo desde $it ${r.unidad ?: "pza"}" } ?: r.unidad?.let { "por $it" },
            descuento = desc, detalle = listOfNotNull(pub.motivo?.takeIf { it.isNotBlank() }, r.detalle.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { null },
            disponible = r.disponible,
            accion = if (r.tipo == "solicitud") "Ofrecer ahora" else "Pedir ahora", foto = r.foto,
        )
    }
    is Publicacion.Proveedor -> {
        val p = pub.p
        Anuncio(
            etiqueta = "PROVEEDOR DESTACADO", etiquetaIcono = Ico.Verificado, icono = Ico.Fabrica, titulo = p.nombre.removeSuffix(" (demo)"),
            tienda = TiendaCorta(p.id, p.nombre, p.slug, p.giro, p.telefono), km = p.distanciaKm, precio = null, precioAntes = null,
            precioTexto = p.promocion?.takeIf { it.isNotBlank() }, condicion = null, descuento = null,
            detalle = "${p.productos} productos · ${p.afines} de lo que tú vendes", disponible = null, accion = "Ver catálogo",
            destacados = p.destacados.take(3).map { d -> d.titulo to (if ((d.precioMayoreoC ?: 0) > 0) d.precioMayoreoC!! else d.precioC ?: 0) },
            foto = p.destacados.firstNotNullOfOrNull { it.foto },
        )
    }
}

@Composable
internal fun PaginaFeed(pub: Publicacion, i: Int, total: Int, activa: Boolean, onPedir: () -> Unit, onChat: (Long) -> Unit, onNegociar: (ResultadoRed) -> Unit, onPerfil: (Long) -> Unit = {}) {
    val a = remember(pub.clave) { armar(pub) }
    val (claro, oscuro, acento) = paletas[(pub.clave.hashCode() and 0x7fffffff) % paletas.size]
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(claro, oscuro)))) {
        FondoAnuncio(acento, activa)
        // ---------- Producto en tarjeta de vidrio con aro que gira ----------
        Box(Modifier.align(Alignment.TopCenter).padding(top = 128.dp).size(196.dp).pop(), contentAlignment = Alignment.Center) {
            AroGiratorio(acento, activa, Modifier.fillMaxSize())
            if (a.foto != null) {
                // Foto real integrada al anuncio: «foco» de luz del color de la paleta,
                // el blanco de la foto se funde con él y una sombra de piso la asienta.
                val foco = lerp(claro, Color.White, 0.80f)
                Box(Modifier.size(188.dp).flotar(6f, 2800, activa), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(120.dp, 16.dp)
                            .background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent)), CircleShape),
                    )
                    Box(
                        Modifier.size(172.dp).shadow(24.dp, CircleShape, spotColor = acento, ambientColor = oscuro)
                            .background(foco, CircleShape) // liso: así el fondo de la foto no deja un cuadro
                            .border(3.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f), acento.copy(alpha = 0.6f))), CircleShape)
                            .clip(CircleShape).padding(22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        FotoRed(a.foto, a.titulo, Modifier.fillMaxSize(), tinte = foco) {
                            Icon(a.icono, null, tint = oscuro.copy(alpha = 0.5f), modifier = Modifier.size(72.dp))
                        }
                    }
                }
            } else Box(
                Modifier.size(150.dp).flotar(8f, 2600, activa)
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.06f))), RoundedCornerShape(40.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(40.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(a.icono, null, tint = Color.White, modifier = Modifier.size(84.dp)) }
            a.descuento?.let { SelloDescuento(it, activa, Modifier.align(Alignment.TopEnd).offset(x = (-2).dp, y = 2.dp)) }
        }
        // ---------- Etiqueta y contador ----------
        Row(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 66.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.entrada(0).background(Color.White, RoundedCornerShape(50)).padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(a.etiquetaIcono, null, tint = oscuro, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(a.etiqueta, color = oscuro, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.2.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text("${i + 1} / $total", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        // ---------- Texto del anuncio ----------
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, oscuro.copy(alpha = 0.92f))))
                .padding(start = 18.dp, end = 92.dp, top = 36.dp, bottom = 20.dp),
        ) {
            a.tienda?.let { t ->
                Row(Modifier.entrada(0), verticalAlignment = Alignment.CenterVertically) {
                    Text("@${t.slug.ifBlank { t.nombre }}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(4.dp))
                    Icon(Ico.Verificado, "verificado", tint = acento, modifier = Modifier.size(16.dp))
                    km(a.km)?.let { k ->
                        Spacer(Modifier.width(8.dp))
                        Icon(Ico.Ubicacion, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                        Text(k, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(a.titulo, color = Color.White, fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.entrada(1))
            // Precio grande: pesos grandes, centavos arriba; el normal tachado al lado.
            if (a.precio != null) {
                Row(Modifier.entrada(2).padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(precioGrande(a.precio), color = acento, lineHeight = 46.sp)
                    a.precioAntes?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(formatPrice(it), color = Color.White.copy(alpha = 0.55f), fontSize = 16.sp, textDecoration = TextDecoration.LineThrough,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                a.condicion?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.entrada(2)) }
            } else a.precioTexto?.let {
                Row(Modifier.entrada(2).padding(top = 8.dp).background(acento, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = oscuro, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(it, color = oscuro, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // Lo más vendido del proveedor, como lista de precios.
            if (a.destacados.isNotEmpty()) Column(Modifier.entrada(3).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                a.destacados.forEach { (t, c) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Star, null, tint = Dorado, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (c > 0) Text(formatPrice(c), color = acento, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            a.detalle?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.entrada(3))
            }
            a.disponible?.takeIf { it > 0 }?.let { BarraExistencias(it, acento, Modifier.entrada(4).padding(top = 10.dp)) }
            Spacer(Modifier.height(12.dp))
            BotonCompra(a.accion, oscuro, activa, onPedir, Modifier.entrada(4))
            if (i == 0 && total > 1) Text("Desliza para ver más", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally).flotar(4f, 900, activa))
        }
        // ---------- Acciones a la derecha ----------
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            a.tienda?.let { t ->
                Box(
                    Modifier.size(50.dp).pop(1).latido(activa, 0.05f).border(2.dp, acento, CircleShape)
                        .background(Brush.linearGradient(listOf(claro, oscuro)), CircleShape).clip(CircleShape)
                        .clickable(onClickLabel = "Ver perfil de la tienda") { onPerfil(t.id) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Ico.Tienda, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            }
            a.tienda?.let { t -> AccionLateral(Ico.Chat, "Chat") { onChat(t.id) } }
            if (pub is Publicacion.Oferta && pub.r.negociable) AccionLateral(Ico.Trato, "Negociar") { onNegociar(pub.r) }
            a.tienda?.let { t -> AccionLateral(Ico.Tienda, "Perfil") { onPerfil(t.id) } }
        }
    }
}

/** «$26» grande con «.00» chiquito arriba, como en los carteles de súper. */
private fun precioGrande(c: Long) = buildAnnotatedString {
    withStyle(SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, baselineShift = BaselineShift(0.6f))) { append("$") }
    withStyle(SpanStyle(fontSize = 46.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)) { append("${c / 100}") }
    withStyle(SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, baselineShift = BaselineShift(0.9f))) { append(".${(c % 100).toString().padStart(2, '0')}") }
    withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)) { append(" c/u") }
}

/** Círculos y una franja diagonal que se mueven despacio detrás del anuncio. */
@Composable
private fun FondoAnuncio(acento: Color, activa: Boolean) {
    // Solo se mueve en la página que se ve: las vecinas quedan quietas (ahorra batería y cuadros).
    val f = if (activa) {
        val t = rememberInfiniteTransition()
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse)).value
    } else 0.5f
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        drawCircle(acento.copy(alpha = 0.10f), radius = size.width * 0.55f, center = Offset(size.width * (0.95f - 0.15f * f), size.height * (0.12f + 0.05f * f)))
        drawCircle(Color.White.copy(alpha = 0.05f), radius = size.width * 0.35f, center = Offset(size.width * (0.05f + 0.1f * f), size.height * 0.42f))
        // Franja diagonal de luz.
        val y = size.height * (0.2f + 0.25f * f)
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent)),
            Offset(-100f, y + 300f), Offset(size.width + 100f, y - 300f), strokeWidth = 120f)
        // Puntos de retícula, toque «editorial».
        val paso = 46.dp.toPx()
        var yy = paso
        while (yy < size.height * 0.55f) {
            var xx = paso / 2
            while (xx < size.width) { drawCircle(Color.White.copy(alpha = 0.06f), 1.6f, Offset(xx, yy)); xx += paso }
            yy += paso
        }
    }
}

/** Aro punteado que gira alrededor del producto, con un destello. */
@Composable
private fun AroGiratorio(acento: Color, activa: Boolean, modifier: Modifier) {
    val giro = if (activa) {
        val t = rememberInfiniteTransition()
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)))
    } else null
    Canvas(modifier.graphicsLayer { rotationZ = giro?.value ?: 0f }) {
        drawCircle(acento.copy(alpha = 0.35f), style = Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 14f))))
        drawArc(Brush.sweepGradient(listOf(Color.Transparent, acento)), -90f, 80f, false, style = Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** Sello circular dorado con el descuento, girado y latiendo. */
@Composable
private fun SelloDescuento(pct: Int, activa: Boolean, modifier: Modifier) {
    Box(
        modifier.size(68.dp).pop(2).latido(activa, fuerza = 0.07f).graphicsLayer { rotationZ = -14f }
            .background(Brush.radialGradient(listOf(Color(0xFFFFE08A), Dorado, Color(0xFFE0A21B))), CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .semantics { contentDescription = "$pct por ciento menos" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("-$pct%", color = Color(0xFF3A2500), fontWeight = FontWeight.Black, fontSize = 19.sp, lineHeight = 20.sp)
            Text("MAYOREO", color = Color(0xFF3A2500), fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 0.8.sp, lineHeight = 9.sp)
        }
    }
}

/** Cuánto hay disponible, como barra que se llena. */
@Composable
private fun BarraExistencias(n: Long, acento: Color, modifier: Modifier) {
    val lleno by animateFloatAsState((n.toFloat() / (n + 60f)).coerceIn(0.15f, 0.95f), tween(1200, easing = FastOutSlowInEasing))
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Disponibles", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text("$n", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth(lleno).height(6.dp).background(Brush.horizontalGradient(listOf(acento.copy(alpha = 0.7f), acento)), RoundedCornerShape(3.dp)))
        }
    }
}

/** Botón blanco ancho con flecha y un brillo que lo cruza. */
@Composable
private fun BotonCompra(texto: String, oscuro: Color, activa: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val fuente = remember { MutableInteractionSource() }
    val brillo = if (activa) {
        val t = rememberInfiniteTransition()
        t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2200, delayMillis = 600, easing = LinearEasing)))
    } else null
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).rebote(fuente, 0.95f)
            .clip(RoundedCornerShape(16.dp)).background(Color.White)
            .drawWithContent {
                drawContent()
                val x = brillo?.value
                if (x != null) drawRect(
                    Brush.linearGradient(listOf(Color.Transparent, oscuro.copy(alpha = 0.10f), Color.Transparent),
                        start = Offset(size.width * x - 80f, 0f), end = Offset(size.width * x + 80f, size.height)),
                    blendMode = BlendMode.SrcOver,
                )
            }
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .semantics { role = Role.Button; contentDescription = texto }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(texto, color = oscuro, fontWeight = FontWeight.Black, fontSize = 16.sp)
        Box(Modifier.size(32.dp).background(oscuro, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AccionLateral(icono: ImageVector, texto: String, onClick: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    Column(
        Modifier.pop(2).rebote(fuente, 0.8f).clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = texto },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).background(Color.White.copy(alpha = 0.16f), CircleShape).border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icono, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(texto, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
