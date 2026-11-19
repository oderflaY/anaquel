package com.eter.mitienda.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eter.mitienda.core.designsystem.charts.BarRow
import com.eter.mitienda.core.designsystem.charts.HorizontalBarList
import com.eter.mitienda.core.designsystem.charts.StatTile
import com.eter.mitienda.core.designsystem.charts.VerticalBarChart
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicChip
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.util.formatCompactPrice
import com.eter.mitienda.core.util.formatDateTime
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.data.repository.StatsPeriod
import com.eter.mitienda.data.repository.StatsSnapshot
import com.eter.mitienda.feature.orders.OrderDates

/** Gráficas de ventas, inventario y pedidos. Se recalculan solas con cada venta. */
@Composable
fun StatsRoute(
    viewModel: StatsViewModel,
    onOpenTicket: (saleId: Long) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(NeuColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(NeuDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
    ) {
        Column {
            Text("Gráficas", style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
            Text("Cómo va la papelería", style = MaterialTheme.typography.bodyMedium, color = NeuColors.TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd)) {
            StatsPeriod.entries.forEach { p ->
                NeumorphicChip(text = p.label, selected = viewModel.currentPeriod == p, onClick = { viewModel.onPeriodSelected(p) })
            }
        }

        when (val s = state) {
            StatsUiState.Loading -> Text("Calculando…", style = MaterialTheme.typography.bodyMedium, color = NeuColors.TextSecondary)
            is StatsUiState.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = NeuColors.OnLowStock)
            is StatsUiState.Ready -> StatsContent(
                snapshot = s.snapshot,
                selectedDay = viewModel.selectedDay,
                onDaySelected = viewModel::onDaySelected,
                onOpenTicket = onOpenTicket,
            )
        }
    }
}

@Composable
private fun StatsContent(
    snapshot: StatsSnapshot,
    selectedDay: Int?,
    onDaySelected: (Int?) -> Unit,
    onOpenTicket: (Long) -> Unit,
) {
    // Indicadores principales: cifras, no gráficas (un número se lee más rápido que una barra).
    Column(verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
        Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
            StatTile("Ventas", formatPrice(snapshot.totalCents), Modifier.weight(1f), caption = "${snapshot.itemsSold} artículos")
            StatTile("Tickets", snapshot.tickets.toString(), Modifier.weight(1f), caption = "Promedio ${formatPrice(snapshot.averageTicketCents)}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
            StatTile("Utilidad estimada", formatPrice(snapshot.estimatedProfitCents), Modifier.weight(1f), caption = "Con costos actuales")
            StatTile(
                "Pedidos abiertos",
                snapshot.openOrders.count.toString(),
                Modifier.weight(1f),
                caption = "Por cobrar ${formatPrice(snapshot.openOrders.pendingBalanceCents)}",
            )
        }
    }

    ChartCard("Ventas por día") {
        val days = snapshot.days
        val detail = selectedDay?.let { days.getOrNull(it) }?.let {
            "${OrderDates.weekday(it.date)} ${OrderDates.dateLabel(it.date)}: ${formatPrice(it.totalCents)} · ${it.tickets} ${if (it.tickets == 1) "ticket" else "tickets"}"
        } ?: "Toca una barra para ver el detalle"
        Text(detail, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = NeuColors.TextPrimary)
        Spacer(Modifier.size(NeuDimens.SpaceMd))
        VerticalBarChart(
            values = days.map { it.totalCents },
            labels = days.map { if (snapshot.period == StatsPeriod.WEEK) OrderDates.weekday(it.date) else it.date.day.toString() },
            selectedIndex = selectedDay,
            onSelect = onDaySelected,
            axisFormatter = ::formatCompactPrice,
            labelEvery = if (snapshot.period == StatsPeriod.WEEK) 1 else 5,
            description = "Ventas por día de los últimos ${snapshot.period.days} días",
        )
        if (snapshot.totalCents == 0L) {
            Text("Sin ventas en este periodo.", style = MaterialTheme.typography.bodySmall, color = NeuColors.TextSecondary)
        }
    }

    ChartCard("Más vendidos") {
        if (snapshot.topProducts.isEmpty()) {
            EmptyText()
        } else {
            HorizontalBarList(
                snapshot.topProducts.map { BarRow(it.label, it.quantity.toLong(), "${it.quantity} pzas") },
            )
        }
    }

    ChartCard("Ventas por categoría") {
        if (snapshot.byCategory.isEmpty()) {
            EmptyText()
        } else {
            val total = snapshot.byCategory.sumOf { it.revenueCents }.coerceAtLeast(1)
            HorizontalBarList(
                snapshot.byCategory.map {
                    BarRow(it.label, it.revenueCents, "${formatPrice(it.revenueCents)} · ${it.revenueCents * 100 / total}%")
                },
            )
        }
    }

    ChartCard("Inventario") {
        val inv = snapshot.inventory
        InfoRow("Productos", inv.productCount.toString())
        InfoRow("Con stock bajo", inv.lowStockCount.toString())
        InfoRow("Valor a costo", formatPrice(inv.costValueCents))
        InfoRow("Valor a precio de venta", formatPrice(inv.saleValueCents))
    }

    ChartCard("Últimas ventas") {
        if (snapshot.recentSales.isEmpty()) {
            EmptyText()
        } else {
            snapshot.recentSales.forEach { sale ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenTicket(sale.id) }
                        .padding(vertical = NeuDimens.SpaceSm),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ticket #${sale.id.toString().padStart(6, '0')}", style = MaterialTheme.typography.bodyMedium, color = NeuColors.TextPrimary)
                        Text(
                            "${formatDateTime(sale.timestamp)} · ${sale.itemCount} art.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeuColors.TextSecondary,
                        )
                    }
                    Text(formatPrice(sale.totalCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    NeumorphicCard(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
        Spacer(Modifier.size(NeuDimens.SpaceLg))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = NeuDimens.SpaceXs)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = NeuColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = NeuColors.TextPrimary)
    }
}

@Composable
private fun EmptyText() {
    Text("Sin ventas en este periodo.", style = MaterialTheme.typography.bodyMedium, color = NeuColors.TextSecondary)
}
