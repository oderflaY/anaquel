package com.eter.mitienda.feature.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicChip
import com.eter.mitienda.core.designsystem.components.NeumorphicFab
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.core.notifications.rememberNotificationPermissionState
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.domain.model.Order
import com.eter.mitienda.domain.model.OrderStatus
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_add
import mitienda.shared.generated.resources.ic_arrow_forward
import mitienda.shared.generated.resources.ic_bell
import org.jetbrains.compose.resources.painterResource

/** Pedidos por encargo: lista por urgencia, avance rápido de estado y aviso de notificaciones. */
@Composable
fun OrdersRoute(
    viewModel: OrdersViewModel,
    onNewOrder: () -> Unit,
    onOpenOrder: (Order) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val notifications = rememberNotificationPermissionState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.message) {
        viewModel.message?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            viewModel.onMessageShown()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NeuColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = NeuDimens.SpaceLg)) {
                Text("Pedidos", style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
                Text(
                    buildString {
                        append(if (state.openCount == 1) "1 por entregar" else "${state.openCount} por entregar")
                        if (state.pendingBalanceCents > 0) append(" · Por cobrar ${formatPrice(state.pendingBalanceCents)}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeuColors.TextSecondary,
                )
            }

            LazyRow(
                contentPadding = PaddingValues(
                    start = NeuDimens.ScreenPadding,
                    end = NeuDimens.ScreenPadding,
                    top = NeuDimens.SpaceXl,
                    bottom = NeuDimens.SpaceSm,
                ),
                horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceMd),
            ) {
                items(OrdersFilter.entries) { f ->
                    NeumorphicChip(text = f.label, selected = state.filter == f, onClick = { viewModel.onFilterSelected(f) })
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = NeuDimens.ScreenPadding,
                    end = NeuDimens.ScreenPadding,
                    top = NeuDimens.SpaceXl,
                    bottom = NeuDimens.FabSize + NeuDimens.ScreenPadding * 2,
                ),
                verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceXl),
            ) {
                // Sin permiso de notificaciones los avisos no llegan: se pide aquí, con contexto.
                if (!notifications.granted && state.openCount > 0) {
                    item(key = "notif") {
                        NotificationPrompt(onEnable = notifications::request)
                    }
                }
                val orders = state.orders
                val empty = when {
                    state.error != null -> state.error
                    orders == null -> "Cargando…"
                    orders.isEmpty() && state.filter == OrdersFilter.OPEN ->
                        "No hay pedidos pendientes.\nToca + para anotar una piñata, manualidad o cualquier encargo."
                    orders.isEmpty() -> "No hay pedidos aquí."
                    else -> null
                }
                if (empty != null) {
                    item(key = "empty") {
                        Text(
                            empty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NeuColors.TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = NeuDimens.SpaceXxl),
                        )
                    }
                }
                items(orders.orEmpty(), key = { it.id }) { order ->
                    OrderCard(order, onClick = { onOpenOrder(order) }, onAdvance = { viewModel.advance(order) })
                }
            }
        }

        NeumorphicFab(
            icon = painterResource(Res.drawable.ic_add),
            contentDescription = "Nuevo pedido",
            onClick = onNewOrder,
            modifier = Modifier.align(Alignment.BottomEnd).padding(NeuDimens.ScreenPadding),
        )

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = NeuDimens.FabSize + NeuDimens.ScreenPadding)) {
            Snackbar(it, shape = NeuShapes.Large, containerColor = NeuColors.TextPrimary, contentColor = NeuColors.Background)
        }
    }
}

@Composable
private fun NotificationPrompt(onEnable: () -> Unit) {
    NeumorphicCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_bell), contentDescription = null, tint = NeuColors.Magenta, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(NeuDimens.SpaceMd))
            Text(
                "Activa las notificaciones para que te avise un día antes y el día de cada entrega.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeuColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.size(NeuDimens.SpaceLg))
        NeumorphicButton(text = "Activar avisos", onClick = onEnable, modifier = Modifier.fillMaxWidth())
    }
}

/** Colores de urgencia: siempre acompañados de texto ("Hoy", "Atrasado"…), nunca solo color. */
private fun DueUrgency.colors(): Pair<Color, Color> = when (this) {
    DueUrgency.OVERDUE -> NeuColors.LowStockContainer to NeuColors.OnLowStock
    DueUrgency.TODAY -> NeuColors.Magenta to NeuColors.OnAccent
    DueUrgency.TOMORROW -> NeuColors.Blue to NeuColors.OnAccent
    DueUrgency.SOON -> NeuColors.ChipBackground to NeuColors.OnChip
    DueUrgency.LATER -> NeuColors.Background to NeuColors.TextSecondary
}

@Composable
private fun OrderCard(order: Order, onClick: () -> Unit, onAdvance: () -> Unit) {
    val due = OrderDates.due(order.deliveryAt, order.status.isOpen)
    val (dueBg, dueFg) = due.urgency.colors()

    NeumorphicCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                due.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = dueFg,
                modifier = Modifier
                    .background(dueBg, NeuShapes.Pill)
                    .padding(horizontal = NeuDimens.SpaceMd, vertical = NeuDimens.SpaceXs),
            )
            Spacer(Modifier.weight(1f))
            Text(order.status.label, style = MaterialTheme.typography.labelMedium, color = NeuColors.TextSecondary)
        }
        Spacer(Modifier.size(NeuDimens.SpaceMd))
        Text(order.customer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
        Text(
            "${order.kind} · ${order.description}",
            style = MaterialTheme.typography.bodyMedium,
            color = NeuColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(NeuDimens.SpaceMd))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(formatPrice(order.priceCents), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
                Text(
                    if (order.balanceCents > 0) "Resta ${formatPrice(order.balanceCents)}" else "Pagado",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (order.balanceCents > 0) NeuColors.OnChip else NeuColors.Blue,
                )
            }
            order.status.next?.let { next ->
                NeumorphicButton(
                    text = when (next) {
                        OrderStatus.IN_PROGRESS -> "Empezar"
                        OrderStatus.READY -> "Marcar listo"
                        else -> "Entregar"
                    },
                    icon = painterResource(Res.drawable.ic_arrow_forward),
                    containerColor = if (next == OrderStatus.DELIVERED) NeuColors.Magenta else NeuColors.Blue,
                    elevation = NeuDimens.ElevationSmall,
                    onClick = onAdvance,
                )
            }
        }
    }
}
