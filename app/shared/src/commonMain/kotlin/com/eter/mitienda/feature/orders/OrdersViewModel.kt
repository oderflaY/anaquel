package com.eter.mitienda.feature.orders

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.domain.model.Order
import com.eter.mitienda.domain.model.OrderStatus
import com.eter.mitienda.domain.repository.OrderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class OrdersFilter(val label: String) {
    OPEN("Por entregar"),
    DELIVERED("Entregados"),
    CANCELLED("Cancelados"),
}

data class OrdersUiState(
    val orders: List<Order>? = null,
    val filter: OrdersFilter = OrdersFilter.OPEN,
    val openCount: Int = 0,
    /** Lo que falta por cobrar de los pedidos abiertos. */
    val pendingBalanceCents: Long = 0,
    val error: String? = null,
)

class OrdersViewModel(
    private val repository: OrderRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(OrdersFilter.OPEN)

    var message by mutableStateOf<String?>(null)
        private set

    val uiState: StateFlow<OrdersUiState> = combine(
        repository.observeOrders(),
        filter,
    ) { all, selected ->
        val open = all.filter { it.status.isOpen }
        val shown = when (selected) {
            // Los abiertos, del más urgente al más lejano.
            OrdersFilter.OPEN -> open.sortedBy { it.deliveryAt }
            OrdersFilter.DELIVERED -> all.filter { it.status == OrderStatus.DELIVERED }.sortedByDescending { it.deliveryAt }
            OrdersFilter.CANCELLED -> all.filter { it.status == OrderStatus.CANCELLED }.sortedByDescending { it.deliveryAt }
        }
        OrdersUiState(
            orders = shown,
            filter = selected,
            openCount = open.size,
            pendingBalanceCents = open.sumOf { it.balanceCents },
        )
    }
        .catch { e -> emit(OrdersUiState(orders = emptyList(), error = e.message)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrdersUiState())

    fun onFilterSelected(newFilter: OrdersFilter) {
        filter.value = newFilter
    }

    /** Botón rápido: Pendiente → En proceso → Listo → Entregado. */
    fun advance(order: Order) {
        val next = order.status.next ?: return
        viewModelScope.launch {
            repository.updateStatus(order.id, next)
                .onSuccess { if (next == OrderStatus.DELIVERED) message = "Pedido de ${order.customer} entregado" }
                .onFailure { message = it.message }
        }
    }

    fun onMessageShown() {
        message = null
    }
}
