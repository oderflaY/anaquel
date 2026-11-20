package com.eter.mitienda.feature.sale

import com.eter.mitienda.core.util.formatDateTime
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.domain.model.Sale

/** Nombre que encabeza los tickets. */
const val STORE_NAME = "Anaquel"

/**
 * Ticket en texto plano de 32 columnas (el ancho de una impresora térmica de
 * 58 mm), para compartir por WhatsApp/SMS y que se vea alineado con fuente fija.
 */
object TicketFormatter {
    private const val WIDTH = 32
    private val SEPARATOR = "-".repeat(WIDTH)

    fun toText(sale: Sale): String = buildString {
        appendLine(center(STORE_NAME))
        appendLine(center("Ticket #${sale.folio}"))
        appendLine(center(formatDateTime(sale.timestamp)))
        appendLine(SEPARATOR)
        sale.lines.forEach { line ->
            appendLine("${line.quantity} x ${line.name}".take(WIDTH))
            appendLine(row("   ${formatPrice(line.unitPriceCents)} c/u", formatPrice(line.subtotalCents)))
        }
        appendLine(SEPARATOR)
        appendLine(row("ARTÍCULOS", sale.itemCount.toString()))
        appendLine(row("TOTAL", formatPrice(sale.totalCents)))
        sale.receivedCents?.let { appendLine(row("Recibido", formatPrice(it))) }
        sale.changeCents?.let { appendLine(row("Cambio", formatPrice(it))) }
        appendLine(SEPARATOR)
        append(center("¡Gracias por su compra!"))
    }

    /** Texto a la izquierda y monto a la derecha, en una línea de [WIDTH]. */
    private fun row(left: String, right: String): String {
        val space = WIDTH - right.length
        return left.take(space - 1).padEnd(space) + right
    }

    private fun center(text: String): String {
        val trimmed = text.take(WIDTH)
        return " ".repeat((WIDTH - trimmed.length) / 2) + trimmed
    }
}
