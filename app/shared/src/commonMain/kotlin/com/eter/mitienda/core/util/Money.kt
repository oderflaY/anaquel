package com.eter.mitienda.core.util

import kotlin.math.abs

/**
 * Formatea centavos como precio en pesos: 129000 → "$1,290.00".
 * Implementación manual porque `String.format` no existe en código común de KMP.
 */
fun formatPrice(cents: Long): String {
    val absolute = abs(cents)
    val pesos = (absolute / 100).toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
    val centavos = (absolute % 100).toString().padStart(2, '0')
    val sign = if (cents < 0) "-" else ""
    return "$sign\$$pesos.$centavos"
}

/** Hasta 9 dígitos enteros y 2 decimales; acepta coma o punto como separador. */
private val MONEY_INPUT = Regex("""^\d{0,9}([.,]\d{0,2})?$""")

/** `true` si [text] es un estado intermedio válido mientras se escribe ("12", "12.", "12.5"). */
fun isValidMoneyInput(text: String): Boolean = MONEY_INPUT.matches(text)

/**
 * Convierte el texto de un campo de precio a centavos sin pasar por Double
 * ("45.5" → 4550). Devuelve `null` si está vacío o mal formado.
 */
fun parseMoneyToCents(text: String): Long? {
    val normalized = text.trim().replace(',', '.')
    if (normalized.isEmpty() || normalized == "." || !MONEY_INPUT.matches(normalized)) return null
    val pesos = normalized.substringBefore('.').ifEmpty { "0" }.toLong()
    val centavos = normalized.substringAfter('.', "").padEnd(2, '0').toLong()
    return pesos * 100 + centavos
}

/** Inverso para precargar un formulario: 4550 → "45.50". */
fun centsToInput(cents: Long): String =
    "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"

/** Importe corto para ejes de gráficas: 85000 → "$850", 125000 → "$1.3k". */
fun formatCompactPrice(cents: Long): String {
    val pesos = cents / 100.0
    return when {
        pesos >= 1_000_000 -> "$" + oneDecimal(pesos / 1_000_000) + "M"
        pesos >= 1_000 -> "$" + oneDecimal(pesos / 1_000) + "k"
        else -> "$" + pesos.toLong()
    }
}

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10).toLong()
    return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
