package com.eter.mitienda.domain.model

/** Estados de un pedido por encargo, en el orden en que avanza. */
enum class OrderStatus(val label: String) {
    PENDING("Pendiente"),
    IN_PROGRESS("En proceso"),
    READY("Listo para entregar"),
    DELIVERED("Entregado"),
    CANCELLED("Cancelado"),
    ;

    /** Abierto = aún hay que hacerlo o entregarlo (genera recordatorios). */
    val isOpen: Boolean get() = this == PENDING || this == IN_PROGRESS || this == READY

    /** Siguiente paso natural, para el botón rápido de la tarjeta. */
    val next: OrderStatus?
        get() = when (this) {
            PENDING -> IN_PROGRESS
            IN_PROGRESS -> READY
            READY -> DELIVERED
            DELIVERED, CANCELLED -> null
        }

    companion object {
        fun fromKey(key: String): OrderStatus = entries.firstOrNull { it.name == key } ?: PENDING
    }
}

/** Pedido por encargo: una piñata, una manualidad, recuerdos para una fiesta… */
data class Order(
    val id: Long = 0,
    val customer: String,
    val phone: String? = null,
    /** Tipo libre ("Piñata", "Manualidad"); se sugieren los ya usados. */
    val kind: String,
    val description: String,
    /** Fecha y hora de entrega, epoch en milisegundos. */
    val deliveryAt: Long,
    val priceCents: Long,
    val depositCents: Long = 0,
    val status: OrderStatus = OrderStatus.PENDING,
    val notes: String? = null,
    val createdAt: Long = 0,
) {
    /** Lo que falta por cobrar al entregar. */
    val balanceCents: Long get() = (priceCents - depositCents).coerceAtLeast(0)
}
