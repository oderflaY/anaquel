package com.eter.mitienda.feature.sale

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.domain.model.Sale
import com.eter.mitienda.domain.repository.SaleRepository
import kotlinx.coroutines.launch

sealed interface TicketUiState {
    data object Loading : TicketUiState
    data class Loaded(val sale: Sale) : TicketUiState
    data class Failed(val message: String) : TicketUiState
}

/** Carga la venta desde SQLite (no se pasa por la ruta: así el ticket sobrevive a rotaciones). */
class TicketViewModel(
    repository: SaleRepository,
    saleId: Long,
) : ViewModel() {

    var state by mutableStateOf<TicketUiState>(TicketUiState.Loading)
        private set

    init {
        viewModelScope.launch {
            state = repository.getSale(saleId).fold(
                onSuccess = { sale -> sale?.let { TicketUiState.Loaded(it) } ?: TicketUiState.Failed("La venta ya no existe.") },
                onFailure = { TicketUiState.Failed(it.message ?: "No se pudo cargar el ticket.") },
            )
        }
    }
}
