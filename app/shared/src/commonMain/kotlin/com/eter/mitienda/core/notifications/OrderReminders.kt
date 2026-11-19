package com.eter.mitienda.core.notifications

import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.domain.model.Order
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Programa/cancela los avisos locales de un pedido. Implementación por
 * plataforma: WorkManager en Android, UNUserNotificationCenter en iOS.
 * Todo es local: no hay servidor ni push.
 */
interface OrderReminderScheduler {
    /** Reemplaza los avisos que tuviera el pedido. Si está cerrado, solo los cancela. */
    fun schedule(order: Order)

    fun cancel(orderId: Long)

    fun cancelAll()
}

/** Cuándo avisar de un pedido. */
enum class ReminderKind { DAY_BEFORE, SAME_DAY }

data class PlannedReminder(val kind: ReminderKind, val fireAtMillis: Long)

/**
 * Regla de negocio de los avisos (común a ambas plataformas):
 * - Un día antes a las 9:00 → "Mañana se entrega…" (hay tiempo de terminarlo).
 * - El mismo día a las 8:00, o 2 h antes si la entrega es antes de las 10:00.
 * Los avisos cuya hora ya pasó no se programan.
 */
object OrderReminderPlan {

    private const val TWO_HOURS = 2 * 60 * 60 * 1000L

    fun plan(order: Order, nowMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): List<PlannedReminder> {
        if (!order.status.isOpen) return emptyList()
        val deliveryDate = Instant.fromEpochMilliseconds(order.deliveryAt).toLocalDateTime(zone).date

        val dayBefore = deliveryDate.minus(1, DateTimeUnit.DAY).atTime(9, 0).toInstant(zone).toEpochMilliseconds()
        val morning = deliveryDate.atTime(8, 0).toInstant(zone).toEpochMilliseconds()
        val sameDay = if (morning <= order.deliveryAt - TWO_HOURS) morning else order.deliveryAt - TWO_HOURS

        return listOf(
            PlannedReminder(ReminderKind.DAY_BEFORE, dayBefore),
            PlannedReminder(ReminderKind.SAME_DAY, sameDay),
        ).filter { it.fireAtMillis > nowMillis }
    }

    fun title(order: Order, kind: ReminderKind): String = when (kind) {
        ReminderKind.DAY_BEFORE -> "Mañana se entrega: ${order.kind}"
        ReminderKind.SAME_DAY -> "Hoy se entrega: ${order.kind}"
    }

    /** "Ana López · 16:00 · Piñata de Spiderman · En proceso · Resta $350.00" */
    fun body(order: Order, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val time = Instant.fromEpochMilliseconds(order.deliveryAt).toLocalDateTime(zone).time
        val hour = "${time.hour}:${time.minute.toString().padStart(2, '0')}"
        val balance = if (order.balanceCents > 0) " · Resta ${formatPrice(order.balanceCents)}" else " · Pagado"
        return "${order.customer} · $hour · ${order.description} · ${order.status.label}$balance"
    }
}
