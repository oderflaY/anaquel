package com.eter.mitienda.domain.model

/** Renglón del carrito: el producto (con su precio actual) y cuántas piezas. */
data class CartItem(
    val product: Product,
    val quantity: Int,
) {
    val subtotalCents: Long get() = product.priceCents * quantity

    /** Se quiere vender más de lo que hay registrado en inventario. */
    val exceedsStock: Boolean get() = quantity > product.stock
}

/** Venta registrada (el ticket). */
data class Sale(
    val id: Long,
    /** Epoch en milisegundos. */
    val timestamp: Long,
    val lines: List<SaleLine>,
    val totalCents: Long,
    /** Efectivo recibido; `null` si no se capturó. */
    val receivedCents: Long?,
) {
    val itemCount: Int get() = lines.sumOf { it.quantity }
    val changeCents: Long? get() = receivedCents?.let { it - totalCents }

    /** Folio legible de 6 dígitos: 12 → "000012". */
    val folio: String get() = id.toString().padStart(6, '0')
}

/** Renglón del ticket. Copia de los datos del producto en el momento de la venta. */
data class SaleLine(
    val productId: Long?,
    val barcode: String,
    val name: String,
    val unitPriceCents: Long,
    val quantity: Int,
) {
    val subtotalCents: Long get() = unitPriceCents * quantity
}
