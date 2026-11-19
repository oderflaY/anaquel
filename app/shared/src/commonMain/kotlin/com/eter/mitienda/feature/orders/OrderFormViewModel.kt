package com.eter.mitienda.feature.orders

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.core.util.centsToInput
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.core.util.isValidMoneyInput
import com.eter.mitienda.core.util.parseMoneyToCents
import com.eter.mitienda.data.local.nowMillis
import com.eter.mitienda.domain.model.Order
import com.eter.mitienda.domain.model.OrderStatus
import com.eter.mitienda.domain.repository.OrderRepository
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

data class OrderFormUiState(
    val isEditing: Boolean = false,
    val customer: String = "",
    val phone: String = "",
    val kind: String = "",
    val description: String = "",
    val deliveryDate: LocalDate? = null,
    val deliveryHour: Int = 12,
    val deliveryMinute: Int = 0,
    val price: String = "",
    val deposit: String = "",
    val status: OrderStatus = OrderStatus.PENDING,
    val notes: String = "",
    /** Tipos sugeridos: los ya usados primero, luego los comunes de papelería. */
    val kindSuggestions: List<String> = DEFAULT_KINDS,
    val customerError: String? = null,
    val kindError: String? = null,
    val descriptionError: String? = null,
    val dateError: String? = null,
    val priceError: String? = null,
    val depositError: String? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val isFinished: Boolean = false,
) {
    /** Resta en vivo mientras se escribe precio/anticipo. */
    val balanceText: String?
        get() {
            val p = parseMoneyToCents(price) ?: return null
            val d = parseMoneyToCents(deposit) ?: 0
            return formatPrice((p - d).coerceAtLeast(0))
        }

    companion object {
        val DEFAULT_KINDS = listOf("Piñata", "Manualidad", "Decoración", "Recuerdos", "Engargolado")
    }
}

class OrderFormViewModel(
    private val repository: OrderRepository,
    private val orderId: Long?,
) : ViewModel() {

    var state by mutableStateOf(OrderFormUiState(isEditing = orderId != null))
        private set

    private var createdAt = 0L

    init {
        viewModelScope.launch {
            repository.getKnownKinds().onSuccess { used ->
                state = state.copy(kindSuggestions = (used + OrderFormUiState.DEFAULT_KINDS).distinctBy { it.lowercase() }.take(8))
            }
        }
        if (orderId != null) {
            viewModelScope.launch {
                repository.getOrder(orderId)
                    .onSuccess { order ->
                        if (order == null) {
                            state = state.copy(errorMessage = "El pedido ya no existe.")
                            return@onSuccess
                        }
                        createdAt = order.createdAt
                        val local = OrderDates.toLocal(order.deliveryAt)
                        state = state.copy(
                            customer = order.customer,
                            phone = order.phone.orEmpty(),
                            kind = order.kind,
                            description = order.description,
                            deliveryDate = local.date,
                            deliveryHour = local.hour,
                            deliveryMinute = local.minute,
                            price = centsToInput(order.priceCents),
                            deposit = if (order.depositCents > 0) centsToInput(order.depositCents) else "",
                            status = order.status,
                            notes = order.notes.orEmpty(),
                        )
                    }
                    .onFailure { state = state.copy(errorMessage = it.message) }
            }
        }
    }

    fun onCustomerChange(v: String) { if (v.length <= 60) state = state.copy(customer = v, customerError = null) }
    fun onPhoneChange(v: String) { if (v.length <= 20 && v.all { it.isDigit() || it in "+ -()" }) state = state.copy(phone = v) }
    fun onKindChange(v: String) { if (v.length <= 30) state = state.copy(kind = v, kindError = null) }
    fun onDescriptionChange(v: String) { if (v.length <= 300) state = state.copy(description = v, descriptionError = null) }
    fun onDateChange(date: LocalDate) { state = state.copy(deliveryDate = date, dateError = null) }
    fun onTimeChange(hour: Int, minute: Int) { state = state.copy(deliveryHour = hour, deliveryMinute = minute) }
    fun onPriceChange(v: String) { if (isValidMoneyInput(v)) state = state.copy(price = v, priceError = null, depositError = null) }
    fun onDepositChange(v: String) { if (isValidMoneyInput(v)) state = state.copy(deposit = v, depositError = null) }
    fun onStatusChange(status: OrderStatus) { state = state.copy(status = status) }
    fun onNotesChange(v: String) { if (v.length <= 300) state = state.copy(notes = v) }

    fun save() {
        if (state.isSaving) return
        val order = validate() ?: return
        state = state.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            val result = if (orderId == null) repository.insertOrder(order).map { } else repository.updateOrder(order)
            state = result.fold(
                onSuccess = { state.copy(isSaving = false, isFinished = true) },
                onFailure = { state.copy(isSaving = false, errorMessage = it.message) },
            )
        }
    }

    fun delete() {
        val id = orderId ?: return
        viewModelScope.launch {
            state = repository.deleteOrder(id).fold(
                onSuccess = { state.copy(isFinished = true) },
                onFailure = { state.copy(errorMessage = it.message) },
            )
        }
    }

    private fun validate(): Order? {
        val s = state
        val price = parseMoneyToCents(s.price)
        val deposit = if (s.deposit.isBlank()) 0L else parseMoneyToCents(s.deposit)
        val deliveryAt = s.deliveryDate?.let { OrderDates.toEpochMillis(it, s.deliveryHour, s.deliveryMinute) }

        val errors = s.copy(
            customerError = if (s.customer.isBlank()) "Escribe el nombre del cliente" else null,
            kindError = if (s.kind.isBlank()) "¿Qué se va a hacer?" else null,
            descriptionError = if (s.description.isBlank()) "Describe el pedido" else null,
            dateError = when {
                deliveryAt == null -> "Elige la fecha de entrega"
                orderId == null && deliveryAt < nowMillis() -> "Esa fecha y hora ya pasaron"
                else -> null
            },
            priceError = when {
                s.price.isBlank() -> "Escribe el precio"
                price == null -> "Precio no válido"
                else -> null
            },
            depositError = when {
                deposit == null -> "Anticipo no válido"
                price != null && deposit > price -> "El anticipo no puede ser mayor al precio"
                else -> null
            },
        )
        state = errors
        if (listOf(errors.customerError, errors.kindError, errors.descriptionError, errors.dateError, errors.priceError, errors.depositError).any { it != null }) {
            return null
        }
        return Order(
            id = orderId ?: 0,
            customer = s.customer.trim(),
            phone = s.phone.trim().ifEmpty { null },
            kind = s.kind.trim(),
            description = s.description.trim(),
            deliveryAt = deliveryAt!!,
            priceCents = price!!,
            depositCents = deposit!!,
            status = s.status,
            notes = s.notes.trim().ifEmpty { null },
            createdAt = createdAt,
        )
    }
}
