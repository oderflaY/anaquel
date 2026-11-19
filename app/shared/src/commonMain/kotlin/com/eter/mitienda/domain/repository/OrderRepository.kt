package com.eter.mitienda.domain.repository

import com.eter.mitienda.domain.model.Order
import com.eter.mitienda.domain.model.OrderStatus
import kotlinx.coroutines.flow.Flow

/**
 * Pedidos por encargo. Las implementaciones mantienen los recordatorios al
 * día: cada alta, cambio de fecha/estado o borrado reprograma o cancela los
 * avisos de ese pedido.
 */
interface OrderRepository {
    /** Todos los pedidos, ordenados por fecha de entrega. Reactivo. */
    fun observeOrders(): Flow<List<Order>>

    suspend fun getOrder(id: Long): Result<Order?>

    suspend fun insertOrder(order: Order): Result<Long>

    suspend fun updateOrder(order: Order): Result<Unit>

    suspend fun updateStatus(id: Long, status: OrderStatus): Result<Unit>

    suspend fun deleteOrder(id: Long): Result<Unit>

    /** Tipos ya usados ("Piñata", "Manualidad"…), para sugerirlos. */
    suspend fun getKnownKinds(): Result<List<String>>

    /** Vuelve a programar los avisos de todos los pedidos abiertos (tras importar una BD). */
    suspend fun rescheduleAllReminders()
}
