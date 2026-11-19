package com.eter.mitienda.data.repository

import com.eter.mitienda.core.notifications.OrderReminderScheduler
import com.eter.mitienda.data.local.OrderDao
import com.eter.mitienda.domain.model.DataError
import com.eter.mitienda.domain.model.Order
import com.eter.mitienda.domain.model.OrderStatus
import com.eter.mitienda.domain.repository.OrderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

class SqliteOrderRepository(
    private val dao: OrderDao,
    private val changes: DatabaseChanges,
    private val reminders: OrderReminderScheduler,
) : OrderRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeOrders(): Flow<List<Order>> =
        changes.version.mapLatest {
            try {
                dao.getAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.toDataError()
            }
        }

    override suspend fun getOrder(id: Long): Result<Order?> = call { dao.get(id) }

    override suspend fun insertOrder(order: Order): Result<Long> = write {
        dao.insert(order).also { id -> reminders.schedule(order.copy(id = id)) }
    }

    override suspend fun updateOrder(order: Order): Result<Unit> = write {
        if (dao.update(order) == 0) throw DataError.NotFound(order.id)
        reminders.schedule(order)
    }

    override suspend fun updateStatus(id: Long, status: OrderStatus): Result<Unit> = write {
        if (dao.updateStatus(id, status) == 0) throw DataError.NotFound(id)
        // Entregado/cancelado → schedule() solo cancela los avisos pendientes.
        dao.get(id)?.let(reminders::schedule)
    }

    override suspend fun deleteOrder(id: Long): Result<Unit> = write {
        if (dao.delete(id) == 0) throw DataError.NotFound(id)
        reminders.cancel(id)
    }

    override suspend fun getKnownKinds(): Result<List<String>> = call { dao.getKinds() }

    override suspend fun rescheduleAllReminders() {
        call { dao.getOpen() }.onSuccess { open ->
            reminders.cancelAll()
            open.forEach(reminders::schedule)
        }
    }

    private suspend inline fun <T> write(crossinline block: suspend () -> T): Result<T> =
        call(block).onSuccess { changes.notifyChanged() }

    private suspend inline fun <T> call(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e.toDataError())
    }
}
