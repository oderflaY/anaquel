package com.eter.mitienda.feature.anaquel

import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Animaciones de toda la app, en un solo lugar para que se sientan iguales:
// rebote al tocar, entrada en cascada, latido de pendientes, números que cuentan.

/** Se encoge un poco al presionar y regresa con resorte (usa el mismo interactionSource que el clickable). */
fun Modifier.rebote(fuente: MutableInteractionSource, cuanto: Float = 0.9f): Modifier = composed {
    val presionado by fuente.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (presionado) cuanto else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
    )
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Aparece desde abajo con desvanecido; con [indice] se escalona (efecto cascada). */
fun Modifier.entrada(indice: Int = 0, desdeY: Float = 40f, retrasoMs: Int = 45): Modifier = composed {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((indice.coerceAtMost(12) * retrasoMs).toLong())
        p.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer {
        alpha = p.value.coerceIn(0f, 1f)
        translationY = (1f - p.value) * desdeY
        val s = 0.94f + 0.06f * p.value
        scaleX = s; scaleY = s
    }
}

/** Entra con un «pop» (crece pasándose un poquito y se asienta). */
fun Modifier.pop(indice: Int = 0): Modifier = composed {
    val p = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) {
        delay(indice.coerceAtMost(12) * 40L)
        p.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    graphicsLayer { scaleX = p.value; scaleY = p.value; alpha = ((p.value - 0.4f) / 0.6f).coerceIn(0f, 1f) }
}

/** Late suavemente mientras [activo] (para globos de pendientes). */
fun Modifier.latido(activo: Boolean = true, fuerza: Float = 0.14f): Modifier = composed {
    if (!activo) return@composed this
    val t = rememberInfiniteTransition()
    val s by t.animateFloat(1f, 1f + fuerza, infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    graphicsLayer { scaleX = s; scaleY = s }
}

/** Flota arriba y abajo con un leve giro (para los dibujos grandes). */
fun Modifier.flotar(amplitud: Float = 14f, periodoMs: Int = 2200): Modifier = composed {
    val t = rememberInfiniteTransition()
    val y by t.animateFloat(-amplitud, amplitud, infiniteRepeatable(tween(periodoMs, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val r by t.animateFloat(-4f, 4f, infiniteRepeatable(tween(periodoMs * 3 / 2, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    graphicsLayer { translationY = y; rotationZ = r }
}

/** Brillo que recorre un recuadro mientras carga (esqueleto). */
fun Modifier.brillo(forma: Shape, base: Color = Color(0xFFE4E9E6)): Modifier = composed {
    val t = rememberInfiniteTransition()
    val x by t.animateFloat(-400f, 1200f, infiniteRepeatable(tween(1300, easing = LinearEasing)))
    background(
        Brush.linearGradient(listOf(base, Color.White.copy(alpha = 0.85f), base), start = Offset(x, 0f), end = Offset(x + 400f, 200f)),
        forma,
    )
}

/** Número que «cuenta» hasta su valor cuando cambia. */
@Composable
fun NumeroAnimado(valor: Int, color: Color, tamano: TextUnit, peso: FontWeight = FontWeight.ExtraBold, estilo: TextStyle = TextStyle.Default) {
    val v by animateIntAsState(valor, tween(900, easing = FastOutSlowInEasing))
    Text("$v", color = color, fontSize = tamano, fontWeight = peso, style = estilo)
}

/** Un rectángulo de carga con brillo, del tamaño que le des. */
@Composable
fun Esqueleto(modifier: Modifier, forma: Shape) = Box(modifier.brillo(forma))

/** Agita a los lados una vez cada que cambia [llave] (para avisar algo). */
fun Modifier.sacudir(llave: Any?): Modifier = composed {
    val x = remember { Animatable(0f) }
    LaunchedEffect(llave) {
        if (llave == null) return@LaunchedEffect
        launch {
            for (d in listOf(14f, -12f, 9f, -6f, 3f, 0f)) x.animateTo(d, tween(55))
        }
    }
    graphicsLayer { translationX = x.value }
}

/** Tarjetas de carga con brillo (en lugar de solo decir «Cargando…»). */
@Composable
fun TarjetasCargando(texto: String, cuantas: Int = 3) {
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        Text(texto, color = Color(0xFF6B7A72), fontSize = 13.sp, modifier = Modifier.latido(fuerza = 0.03f))
        repeat(cuantas) { i ->
            androidx.compose.foundation.layout.Row(Modifier.entrada(i), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Esqueleto(Modifier.size(52.dp), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Esqueleto(Modifier.fillMaxWidth(0.7f).height(14.dp), androidx.compose.foundation.shape.RoundedCornerShape(7.dp))
                    Esqueleto(Modifier.fillMaxWidth(0.45f).height(12.dp), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                }
            }
        }
    }
}
