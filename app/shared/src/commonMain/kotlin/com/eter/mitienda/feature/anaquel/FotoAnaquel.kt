package com.eter.mitienda.feature.anaquel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import org.jetbrains.compose.resources.decodeToImageBitmap

val VerdeLleno = Color(0xFF0E7C5A)
val AmbarPoco = Color(0xFFF2B233)
val RojoVacio = Color(0xFFE74C3C)

fun colorNivel(nivel: String) = when (nivel) {
    "vacio" -> RojoVacio
    "poco", "bajo" -> AmbarPoco
    else -> VerdeLleno
}

fun textoNivel(nivel: String) = when (nivel) {
    "vacio" -> "Vacío"
    "poco" -> "Queda poco"
    "bajo" -> "Stock bajo"
    "enviado" -> "Enviado"
    "confirmado" -> "Confirmado"
    "llego" -> "Llegó"
    "cancelado" -> "Cancelado"
    else -> "Lleno"
}

/** Descarga y decodifica una foto del servidor. */
@Composable
fun rememberFoto(ruta: String?, bajar: suspend (String) -> ByteArray): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, ruta) {
        value = null
        if (ruta != null) value = runCatching { bajar(ruta).decodeToImageBitmap() }.getOrNull()
    }
    return bmp
}

/** Foto del anaquel con sus zonas pintadas por nivel (solo lectura). */
@Composable
fun FotoConZonas(foto: ImageBitmap?, zonas: List<Zona>, modifier: Modifier = Modifier) {
    val medidor = rememberTextMeasurer()
    if (foto == null) {
        Box(
            modifier.fillMaxWidth().height(220.dp).clip(NeuShapes.Large).background(NeuColors.ShadowDark),
            contentAlignment = Alignment.Center,
        ) { Text("Cargando foto…", color = NeuColors.TextSecondary) }
        return
    }
    Box(modifier.fillMaxWidth().aspectRatio(foto.width.toFloat() / foto.height).clip(NeuShapes.Large)) {
        Image(foto, contentDescription = "Foto del anaquel", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            zonas.forEach { z ->
                val c = if (z.productoId == null) Color.White else colorNivel(z.nivel)
                dibujarZona(z, c, size, medidor, if (z.producto.isBlank()) "Sin producto" else z.producto, z.productoId == null, false)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.dibujarZona(
    z: Zona, color: Color, lienzo: Size, medidor: TextMeasurer, texto: String, punteada: Boolean, seleccionada: Boolean,
) {
    val tl = Offset(z.x.toFloat() * lienzo.width, z.y.toFloat() * lienzo.height)
    val tam = Size(z.w.toFloat() * lienzo.width, z.h.toFloat() * lienzo.height)
    drawRoundRect(color.copy(alpha = 0.22f), tl, tam, CornerRadius(10f))
    drawRoundRect(
        if (seleccionada) Color.White else color, tl, tam, CornerRadius(10f),
        style = Stroke(width = if (seleccionada) 8f else 6f, pathEffect = if (punteada) PathEffect.dashPathEffect(floatArrayOf(18f, 12f)) else null),
    )
    val estilo = TextStyle(color = Color.White, fontSize = 10.sp, background = Color.Black.copy(alpha = 0.65f))
    val medida = medidor.measure(texto, estilo, overflow = TextOverflow.Ellipsis, maxLines = 1,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = tam.width.toInt().coerceAtLeast(1)))
    drawText(medida, topLeft = Offset(tl.x + 4f, tl.y + tam.height - medida.size.height - 4f))
}

/**
 * Editor de zonas: arrastrar en un área vacía dibuja una zona nueva; arrastrar
 * una zona la mueve; arrastrar su esquina inferior derecha cambia el tamaño;
 * tocar una zona la selecciona.
 */
@Composable
fun EditorZonasFoto(
    foto: ImageBitmap,
    zonas: List<Zona>,
    seleccion: Int?,
    nombreDe: (Long?) -> String,
    onCambio: (List<Zona>) -> Unit,
    onSeleccion: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val medidor = rememberTextMeasurer()
    var tam by remember { mutableStateOf(IntSize(1, 1)) }
    var nueva by remember { mutableStateOf<Zona?>(null) }
    val asaPx = with(LocalDensity.current) { 28.dp.toPx() }
    // Referencias actualizadas para los gestos (que se crean una sola vez).
    val actuales = remember { mutableStateOf(zonas) }
    actuales.value = zonas

    Box(
        modifier.fillMaxWidth().aspectRatio(foto.width.toFloat() / foto.height).clip(NeuShapes.Large)
            .onSizeChanged { tam = it },
    ) {
        Image(foto, contentDescription = "Foto de referencia", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val x = p.x / tam.width; val y = p.y / tam.height
                        val i = actuales.value.indexOfLast { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
                        onSeleccion(i.takeIf { it >= 0 })
                    }
                }
                .pointerInput(Unit) {
                    var modo = 0 // 0 nueva, 1 mover, 2 tamaño
                    var indice = -1
                    var inicio = Offset.Zero
                    var original: Zona? = null
                    detectDragGestures(
                        onDragStart = { p ->
                            inicio = p
                            val zs = actuales.value
                            val x = p.x / tam.width; val y = p.y / tam.height
                            indice = zs.indexOfLast { z ->
                                val ex = (z.x + z.w) * tam.width; val ey = (z.y + z.h) * tam.height
                                (p.x - ex) * (p.x - ex) + (p.y - ey) * (p.y - ey) < asaPx * asaPx
                            }
                            if (indice >= 0) {
                                modo = 2
                            } else {
                                indice = zs.indexOfLast { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
                                modo = if (indice >= 0) 1 else 0
                            }
                            original = zs.getOrNull(indice)
                            if (indice >= 0) onSeleccion(indice) else onSeleccion(null)
                        },
                        onDrag = { cambio, _ ->
                            cambio.consume()
                            val p = cambio.position
                            val dx = ((p.x - inicio.x) / tam.width).toDouble()
                            val dy = ((p.y - inicio.y) / tam.height).toDouble()
                            val o = original
                            when {
                                modo == 0 -> {
                                    val x0 = (inicio.x / tam.width).toDouble(); val y0 = (inicio.y / tam.height).toDouble()
                                    val x1 = (p.x / tam.width).toDouble().coerceIn(0.0, 1.0); val y1 = (p.y / tam.height).toDouble().coerceIn(0.0, 1.0)
                                    nueva = Zona(id = -1, x = minOf(x0, x1), y = minOf(y0, y1), w = kotlin.math.abs(x1 - x0), h = kotlin.math.abs(y1 - y0))
                                }
                                o != null && modo == 1 -> onCambio(actuales.value.mapIndexed { i, z ->
                                    if (i != indice) z else z.copy(x = (o.x + dx).coerceIn(0.0, 1 - z.w), y = (o.y + dy).coerceIn(0.0, 1 - z.h))
                                })
                                o != null -> onCambio(actuales.value.mapIndexed { i, z ->
                                    if (i != indice) z else z.copy(w = (o.w + dx).coerceIn(0.03, 1 - z.x), h = (o.h + dy).coerceIn(0.03, 1 - z.y))
                                })
                            }
                        },
                        onDragEnd = {
                            val n = nueva
                            nueva = null
                            if (modo == 0 && n != null && n.w > 0.03 && n.h > 0.03) {
                                val nuevoId = (actuales.value.minOfOrNull { it.id }?.coerceAtMost(0) ?: 0) - 1
                                onCambio(actuales.value + n.copy(id = nuevoId))
                                onSeleccion(actuales.value.size)
                            }
                        },
                        onDragCancel = { nueva = null },
                    )
                },
        ) {
            zonas.forEachIndexed { i, z ->
                val sel = i == seleccion
                dibujarZona(z, if (z.productoId == null) Color.White else VerdeLleno, size, medidor,
                    nombreDe(z.productoId), z.productoId == null, sel)
                if (sel) {
                    val c = Offset(((z.x + z.w) * size.width).toFloat(), ((z.y + z.h) * size.height).toFloat())
                    drawCircle(Color.White, 22f, c)
                    drawCircle(VerdeLleno, 22f, c, style = Stroke(6f))
                }
            }
            nueva?.let { n ->
                drawRect(Color.White.copy(alpha = 0.25f), Offset(n.x.toFloat() * size.width, n.y.toFloat() * size.height),
                    Size(n.w.toFloat() * size.width, n.h.toFloat() * size.height))
                drawRect(Color.White, Offset(n.x.toFloat() * size.width, n.y.toFloat() * size.height),
                    Size(n.w.toFloat() * size.width, n.h.toFloat() * size.height), style = Stroke(4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            }
        }
    }
}

@Composable
fun EtiquetaNivel(nivel: String, modifier: Modifier = Modifier) {
    val c = when (nivel) {
        "enviado" -> NeuColors.Blue
        "confirmado" -> AmbarPoco
        "llego" -> VerdeLleno
        "cancelado" -> NeuColors.TextSecondary
        else -> colorNivel(nivel)
    }
    Text(
        textoNivel(nivel),
        style = MaterialTheme.typography.labelMedium,
        color = if (c == AmbarPoco) Color(0xFF5A3D00) else Color.White,
        modifier = modifier.background(c, NeuShapes.Pill).padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
