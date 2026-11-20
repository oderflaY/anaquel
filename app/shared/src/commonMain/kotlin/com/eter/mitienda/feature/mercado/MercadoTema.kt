package com.eter.mitienda.feature.mercado

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.toLocalDateTime

/**
 * Tokens de diseño del mercado: los mismos de la web (web/src/mercado/tokens.css)
 * para que app y web se vean como un solo producto. Paleta cálida y local:
 * verde Anaquel, terracota y arena. Todos los pares texto/fondo cumplen AA.
 */
@Immutable
data class ColoresMercado(
    val marca: Color, val marcaSuave: Color, val sobreMarca: Color,
    val acento: Color, val acentoSuave: Color,
    val fondo: Color, val superficie: Color, val superficie2: Color, val borde: Color, val bordeFuerte: Color,
    val texto: Color, val texto2: Color, val texto3: Color,
    val exito: Color, val exitoSuave: Color, val aviso: Color, val avisoSuave: Color,
    val error: Color, val errorSuave: Color, val info: Color, val infoSuave: Color,
    val esqueleto: Color, val esqueletoBrillo: Color,
)

val ColoresClaros = ColoresMercado(
    marca = Color(0xFF0E7C5A), marcaSuave = Color(0xFFEBF6F0), sobreMarca = Color.White,
    acento = Color(0xFFA3461F), acentoSuave = Color(0xFFFBE9DF),
    fondo = Color(0xFFF7F4EF), superficie = Color.White, superficie2 = Color(0xFFF1ECE4),
    borde = Color(0xFFE6DFD4), bordeFuerte = Color(0xFFCDC3B4),
    texto = Color(0xFF22201C), texto2 = Color(0xFF5C564D), texto3 = Color(0xFF6E675D),
    exito = Color(0xFF13795B), exitoSuave = Color(0xFFE1F3EA), aviso = Color(0xFF8F5D00), avisoSuave = Color(0xFFFBF0D9),
    error = Color(0xFFB42318), errorSuave = Color(0xFFFDE8E6), info = Color(0xFF2D6A7A), infoSuave = Color(0xFFE3F0F3),
    esqueleto = Color(0xFFEBE5DC), esqueletoBrillo = Color(0xFFF5F1EB),
)

val ColoresOscuros = ColoresMercado(
    marca = Color(0xFF4CC79A), marcaSuave = Color(0xFF173229), sobreMarca = Color(0xFF072519),
    acento = Color(0xFFEC9469), acentoSuave = Color(0xFF3A2318),
    fondo = Color(0xFF151412), superficie = Color(0xFF1E1C19), superficie2 = Color(0xFF27241F),
    borde = Color(0xFF36322C), bordeFuerte = Color(0xFF4A453D),
    texto = Color(0xFFF1ECE4), texto2 = Color(0xFFC4BBAD), texto3 = Color(0xFFA69D90),
    exito = Color(0xFF5FCF9F), exitoSuave = Color(0xFF16302A), aviso = Color(0xFFF0BF5A), avisoSuave = Color(0xFF35290F),
    error = Color(0xFFF28B80), errorSuave = Color(0xFF3D1C18), info = Color(0xFF7CC3D4), infoSuave = Color(0xFF18303A),
    esqueleto = Color(0xFF2A2722), esqueletoBrillo = Color(0xFF34302A),
)

/** Espaciado (base 4), bordes y tamaños: un solo lugar para toda la app. */
object E {
    val x1 = 4.dp; val x2 = 8.dp; val x3 = 12.dp; val x4 = 16.dp; val x6 = 24.dp; val x8 = 32.dp; val x12 = 48.dp
    val radioControl = 12.dp; val radioCard = 16.dp; val radioModal = 20.dp
    val tactil = 48.dp // objetivo táctil mínimo en Android
}

val LocalColores = staticCompositionLocalOf { ColoresClaros }

object M {
    val c: ColoresMercado @Composable get() = LocalColores.current
}

private val familia = FontFamily.SansSerif
val TipografiaMercado = Typography(
    headlineMedium = TextStyle(fontFamily = familia, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = familia, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = familia, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = familia, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = familia, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = familia, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = familia, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = familia, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = familia, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
)

/**
 * La app de Anaquel es de diseño claro (sus barras del sistema son claras), así
 * que el mercado usa la paleta clara por defecto; la oscura está definida y se
 * activa pasando oscuro = true cuando la app tenga tema oscuro.
 */
@Composable
fun MercadoTema(oscuro: Boolean = false, contenido: @Composable () -> Unit) {
    val c = if (oscuro) ColoresOscuros else ColoresClaros
    val esquema = if (oscuro) {
        darkColorScheme(primary = c.marca, onPrimary = c.sobreMarca, secondary = c.acento, background = c.fondo, onBackground = c.texto,
            surface = c.superficie, onSurface = c.texto, surfaceVariant = c.superficie2, onSurfaceVariant = c.texto2, error = c.error,
            outline = c.bordeFuerte)
    } else {
        lightColorScheme(primary = c.marca, onPrimary = c.sobreMarca, secondary = c.acento, background = c.fondo, onBackground = c.texto,
            surface = c.superficie, onSurface = c.texto, surfaceVariant = c.superficie2, onSurfaceVariant = c.texto2, error = c.error,
            outline = c.bordeFuerte)
    }
    CompositionLocalProvider(LocalColores provides c) {
        MaterialTheme(colorScheme = esquema, typography = TipografiaMercado, content = contenido)
    }
}

// ---- Formatos ----

fun pesos(c: Long): String {
    val entero = c / 100
    val cent = (c % 100).toInt()
    val miles = entero.toString().reversed().chunked(3).joinToString(",").reversed()
    return if (cent == 0) "$$miles" else "$$miles.${cent.toString().padStart(2, '0')}"
}

private val unidades = mapOf(
    "pieza" to ("pieza" to "piezas"), "kilo" to ("kilo" to "kilos"), "litro" to ("litro" to "litros"), "caja" to ("caja" to "cajas"),
    "paquete" to ("paquete" to "paquetes"), "bulto" to ("bulto" to "bultos"), "docena" to ("docena" to "docenas"),
    "metro" to ("metro" to "metros"), "servicio" to ("servicio" to "servicios"),
)
val UNIDADES = unidades.keys.toList()
fun unidad(u: String, n: Int = 1) = unidades[u]?.let { if (n == 1) it.first else it.second } ?: u
fun cantidadTxt(n: Int, u: String) = "${n.toString().reversed().chunked(3).joinToString(",").reversed()} ${unidad(u, n)}"

private val meses = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
/** "2026-11-20T04:01:12Z" → "19 nov, 22:01" en la zona horaria del teléfono. */
@OptIn(kotlin.time.ExperimentalTime::class)
fun fecha(iso: String): String = runCatching {
    val f = kotlin.time.Instant.parse(iso).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    "${f.day} ${meses[f.month.ordinal]}, ${f.hour.toString().padStart(2, '0')}:${f.minute.toString().padStart(2, '0')}"
}.getOrDefault(iso)

private val conectores = setOf("el", "la", "los", "las", "de", "del", "y", "e")
fun iniciales(n: String) = n.split(' ').filter { it.isNotBlank() && it.lowercase() !in conectores }.take(2).joinToString("") { it.first().uppercase() }

data class EstadoTxt(val txt: String, val tono: String)
fun estadoOrden(e: String) = when (e) {
    "pendiente" -> EstadoTxt("Apartado", "aviso")
    "por_cobrar" -> EstadoTxt("Cobrando", "info")
    "pagada" -> EstadoTxt("Pagado", "exito")
    "entregada" -> EstadoTxt("Entregado", "neutro")
    "cancelada" -> EstadoTxt("Cancelado", "error")
    else -> EstadoTxt(e, "neutro")
}

/** "65.50" → 6550 sin flotantes. */
fun aCentavos(t: String): Long? {
    val m = Regex("""^(\d{1,7})(?:\.(\d{1,2}))?$""").matchEntire(t.replace(",", "").replace("$", "").trim()) ?: return null
    return m.groupValues[1].toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong()
}
