package com.eter.mitienda.feature.orders

import com.eter.mitienda.data.local.nowMillis
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Qué tan urgente es una entrega (define color y orden de lectura). */
enum class DueUrgency { OVERDUE, TODAY, TOMORROW, SOON, LATER }

data class DueInfo(val label: String, val urgency: DueUrgency)

/** Utilidades de fechas de entrega, siempre en la zona horaria del teléfono. */
object OrderDates {
    private val zone get() = TimeZone.currentSystemDefault()

    fun toLocal(epochMillis: Long): LocalDateTime =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)

    fun toEpochMillis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).toInstant(zone).toEpochMilliseconds()

    fun today(): LocalDate = toLocal(nowMillis()).date

    /** "Hoy · 16:00", "Mañana · 10:30", "En 4 días · vie 24", "Atrasado 2 días". */
    fun due(deliveryAt: Long, isOpen: Boolean): DueInfo {
        val delivery = toLocal(deliveryAt)
        val days = today().daysUntil(delivery.date)
        val time = timeLabel(delivery.hour, delivery.minute)
        if (!isOpen) return DueInfo(dateLabel(delivery.date), DueUrgency.LATER)
        return when {
            days < 0 || (days == 0 && deliveryAt < nowMillis()) -> DueInfo(
                if (days < 0) "Atrasado ${-days} ${if (days == -1) "día" else "días"}" else "Atrasado · $time",
                DueUrgency.OVERDUE,
            )
            days == 0 -> DueInfo("Hoy · $time", DueUrgency.TODAY)
            days == 1 -> DueInfo("Mañana · $time", DueUrgency.TOMORROW)
            days <= 3 -> DueInfo("En $days días · ${weekday(delivery.date)} ${delivery.date.day}", DueUrgency.SOON)
            else -> DueInfo("${weekday(delivery.date)} ${dateLabel(delivery.date)} · $time", DueUrgency.LATER)
        }
    }

    fun dateLabel(date: LocalDate): String =
        "${date.day.toString().padStart(2, '0')}/${date.month.ordinal.plus(1).toString().padStart(2, '0')}/${date.year}"

    fun timeLabel(hour: Int, minute: Int): String = "$hour:${minute.toString().padStart(2, '0')}"

    fun weekday(date: LocalDate): String = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "lun"
        DayOfWeek.TUESDAY -> "mar"
        DayOfWeek.WEDNESDAY -> "mié"
        DayOfWeek.THURSDAY -> "jue"
        DayOfWeek.FRIDAY -> "vie"
        DayOfWeek.SATURDAY -> "sáb"
        DayOfWeek.SUNDAY -> "dom"
    }
}
