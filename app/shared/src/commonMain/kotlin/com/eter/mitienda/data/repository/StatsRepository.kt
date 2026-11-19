package com.eter.mitienda.data.repository

import com.eter.mitienda.data.local.InventoryRow
import com.eter.mitienda.data.local.OpenOrdersRow
import com.eter.mitienda.data.local.RankRow
import com.eter.mitienda.data.local.SaleRow
import com.eter.mitienda.data.local.StatsDao
import com.eter.mitienda.data.local.nowMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

enum class StatsPeriod(val days: Int, val label: String) {
    WEEK(7, "7 días"),
    MONTH(30, "30 días"),
}

/** Ventas de un día local (para la gráfica de barras). */
data class DayTotal(val date: LocalDate, val totalCents: Long, val tickets: Int)

data class StatsSnapshot(
    val period: StatsPeriod,
    val days: List<DayTotal>,
    val totalCents: Long,
    val tickets: Int,
    val itemsSold: Int,
    val estimatedProfitCents: Long,
    val topProducts: List<RankRow>,
    val byCategory: List<RankRow>,
    val inventory: InventoryRow,
    val openOrders: OpenOrdersRow,
    val recentSales: List<SaleRow>,
) {
    val averageTicketCents: Long get() = if (tickets == 0) 0 else totalCents / tickets
}

/** Agrega los datos de la sección de gráficas y se recalcula con cada cambio en la BD. */
class StatsRepository(
    private val dao: StatsDao,
    private val changes: DatabaseChanges,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(period: StatsPeriod): Flow<StatsSnapshot> =
        changes.version.mapLatest { load(period) }

    private suspend fun load(period: StatsPeriod, zone: TimeZone = TimeZone.currentSystemDefault()): StatsSnapshot {
        val today = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(zone).date
        val firstDay = today.minus(period.days - 1, DateTimeUnit.DAY)
        val from = firstDay.atStartOfDayIn(zone).toEpochMilliseconds()
        val to = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()

        val sales = dao.sales(from, to)
        // Cubeta por día local; los días sin ventas quedan en 0 (la gráfica no "salta" días).
        val byDay = sales.groupBy { Instant.fromEpochMilliseconds(it.timestamp).toLocalDateTime(zone).date }
        val days = (0 until period.days).map { offset ->
            val date = firstDay.plus(offset, DateTimeUnit.DAY)
            val list = byDay[date].orEmpty()
            DayTotal(date, list.sumOf { it.totalCents }, list.size)
        }

        return StatsSnapshot(
            period = period,
            days = days,
            totalCents = sales.sumOf { it.totalCents },
            tickets = sales.size,
            itemsSold = sales.sumOf { it.itemCount },
            estimatedProfitCents = dao.estimatedProfit(from, to),
            topProducts = dao.topProducts(from, to, limit = 5),
            byCategory = dao.salesByCategory(from, to),
            inventory = dao.inventory(),
            openOrders = dao.openOrders(),
            recentSales = sales.take(10),
        )
    }
}
