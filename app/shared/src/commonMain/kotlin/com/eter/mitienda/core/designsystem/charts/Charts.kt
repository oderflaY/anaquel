package com.eter.mitienda.core.designsystem.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.neumorphism.neumorphicInset
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/*
 * Gráficas propias en Canvas, sin librerías.
 * Reglas aplicadas: una sola serie = un solo color (azul DIF) que codifica
 * magnitud; barras delgadas con punta redondeada de 4 dp anclada a la base;
 * rejilla y ejes discretos; los textos usan colores de texto, nunca el de la serie;
 * el valor exacto se ve al tocar (en móvil "tocar" sustituye al "pasar el cursor").
 */

/** Dato destacado: la cifra manda; la etiqueta la explica. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null) {
    NeumorphicCard(
        modifier = modifier,
        elevation = NeuDimens.ElevationMedium,
        contentPadding = PaddingValues(NeuDimens.SpaceLg),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = NeuColors.TextSecondary, maxLines = 1)
        Spacer(Modifier.size(NeuDimens.SpaceXs))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
            fontWeight = FontWeight.Bold,
            color = NeuColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodySmall, color = NeuColors.TextSecondary, maxLines = 1)
        }
    }
}

/**
 * Barras verticales de una serie (p. ej. ventas por día).
 * Tocar una barra la selecciona; [onSelect] recibe su índice (o null).
 * Toda la columna es zona táctil, no solo la barra: así una barra de $0 también se puede tocar.
 *
 * @param labelEvery muestra la etiqueta del eje X cada N barras (las demás se omiten para no encimarse).
 */
@Composable
fun VerticalBarChart(
    values: List<Long>,
    labels: List<String>,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    axisFormatter: (Long) -> String,
    modifier: Modifier = Modifier,
    labelEvery: Int = 1,
    description: String = "",
) {
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = NeuColors.TextSecondary)
    val maxValue = niceMax(values.maxOrNull() ?: 0)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = description }
            .pointerInput(values.size) {
                detectTapGestures { pos ->
                    val left = 40.dp.toPx()
                    val slot = (size.width - left) / values.size
                    val index = ((pos.x - left) / slot).toInt()
                    onSelect(if (index in values.indices && index != selectedIndex) index else null)
                }
            },
    ) {
        val left = 40.dp.toPx() // espacio para las etiquetas del eje Y
        val bottom = 20.dp.toPx() // espacio para las etiquetas del eje X
        val plotHeight = size.height - bottom
        val plotWidth = size.width - left

        // Rejilla recesiva: 0, 50 % y 100 % del máximo "redondo".
        listOf(0f, 0.5f, 1f).forEach { f ->
            val y = plotHeight - plotHeight * f
            drawLine(NeuColors.ShadowDark, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(axisFormatter((maxValue * f).toLong()), axisStyle)
            drawText(text, topLeft = Offset(left - text.size.width - 6.dp.toPx(), y - text.size.height / 2f))
        }

        if (values.isEmpty() || maxValue == 0L) return@Canvas
        val slot = plotWidth / values.size
        val barWidth = (slot * 0.6f).coerceAtMost(24.dp.toPx())
        values.forEachIndexed { i, value ->
            val x = left + slot * i + (slot - barWidth) / 2f
            val h = (value.toFloat() / maxValue) * plotHeight
            // Con una barra seleccionada, las demás se atenúan (mismo color, menos opacidad).
            val alpha = if (selectedIndex == null || selectedIndex == i) 1f else 0.35f
            if (h > 0f) roundedTopBar(x, plotHeight, barWidth, h, NeuColors.Blue.copy(alpha = alpha))
            if (i % labelEvery == 0 || i == values.lastIndex) {
                val text = measurer.measure(labels[i], axisStyle)
                drawText(text, topLeft = Offset(x + barWidth / 2f - text.size.width / 2f, plotHeight + 4.dp.toPx()))
            }
        }
    }
}

/** Barra con esquinas superiores de 4 dp y base recta pegada al eje. */
private fun DrawScope.roundedTopBar(x: Float, baseY: Float, width: Float, height: Float, color: androidx.compose.ui.graphics.Color) {
    val r = minOf(4.dp.toPx(), width / 2f, height)
    val path = Path().apply {
        moveTo(x, baseY)
        lineTo(x, baseY - height + r)
        quadraticTo(x, baseY - height, x + r, baseY - height)
        lineTo(x + width - r, baseY - height)
        quadraticTo(x + width, baseY - height, x + width, baseY - height + r)
        lineTo(x + width, baseY)
        close()
    }
    drawPath(path, color)
}

/** Fila de un ranking: etiqueta y valor arriba, barra proporcional abajo. */
data class BarRow(val label: String, val value: Long, val valueText: String)

/**
 * Ranking en barras horizontales (más vendidos, ventas por categoría). Una
 * sola serie: todas las barras del mismo color; el texto del valor va en
 * tinta de texto, al lado, para leerlo sin depender del color.
 */
@Composable
fun HorizontalBarList(rows: List<BarRow>, modifier: Modifier = Modifier) {
    val max = rows.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1
    Column(modifier) {
        rows.forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.size(NeuDimens.SpaceMd))
            Row {
                Text(
                    row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeuColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(NeuDimens.SpaceSm))
                Text(row.valueText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = NeuColors.TextPrimary)
            }
            Spacer(Modifier.size(NeuDimens.SpaceXs))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .neumorphicInset(NeuShapes.Pill, NeuDimens.ElevationSmall)
                    .padding(2.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((row.value.toFloat() / max).coerceIn(0.02f, 1f))
                        .background(NeuColors.Blue, NeuShapes.Pill),
                )
            }
        }
    }
}

/** Máximo "redondo" para el eje (1, 2, 2.5, 5 × 10ⁿ) para que la rejilla tenga cifras legibles. */
private fun niceMax(value: Long): Long {
    if (value <= 0) return 0
    val exp = 10.0.pow(ceil(log10(value.toDouble())) - 1)
    val n = value / exp
    val nice = when {
        n <= 1 -> 1.0
        n <= 2 -> 2.0
        n <= 2.5 -> 2.5
        n <= 5 -> 5.0
        else -> 10.0
    }
    return max(1L, (nice * exp).toLong())
}
