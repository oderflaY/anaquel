package com.eter.mitienda.feature.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.data.repository.StatsPeriod
import com.eter.mitienda.data.repository.StatsRepository
import com.eter.mitienda.data.repository.StatsSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface StatsUiState {
    data object Loading : StatsUiState
    data class Ready(val snapshot: StatsSnapshot) : StatsUiState
    data class Failed(val message: String) : StatsUiState
}

class StatsViewModel(repository: StatsRepository) : ViewModel() {

    private val period = MutableStateFlow(StatsPeriod.WEEK)

    /** Barra tocada en la gráfica diaria (se limpia al cambiar de periodo). */
    var selectedDay by mutableStateOf<Int?>(null)
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<StatsUiState> = period
        .flatMapLatest { p ->
            repository.observe(p)
                .map<StatsSnapshot, StatsUiState> { StatsUiState.Ready(it) }
                .catch { emit(StatsUiState.Failed(it.message ?: "No se pudieron calcular las gráficas.")) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState.Loading)

    val currentPeriod: StatsPeriod get() = period.value

    fun onPeriodSelected(p: StatsPeriod) {
        period.value = p
        selectedDay = null
    }

    fun onDaySelected(index: Int?) {
        selectedDay = index
    }
}
