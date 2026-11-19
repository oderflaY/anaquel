package com.eter.mitienda.data.repository

import com.eter.mitienda.data.local.SaleDao
import com.eter.mitienda.data.local.nowMillis
import com.eter.mitienda.domain.model.CartItem
import com.eter.mitienda.domain.model.DataError
import com.eter.mitienda.domain.model.Sale
import com.eter.mitienda.domain.model.SaleLine
import com.eter.mitienda.domain.repository.SaleRepository
import kotlinx.coroutines.CancellationException

class SqliteSaleRepository(
    private val dao: SaleDao,
    private val changes: DatabaseChanges,
) : SaleRepository {

    override suspend fun registerSale(items: List<CartItem>, receivedCents: Long?): Result<Sale> = call {
        val lines = items.map { item ->
            SaleLine(
                productId = item.product.id,
                barcode = item.product.barcode,
                name = item.product.name,
                unitPriceCents = item.product.priceCents,
                quantity = item.quantity,
            )
        }
        val total = lines.sumOf { it.subtotalCents }
        val timestamp = nowMillis()
        val id = dao.insertSale(lines, total, receivedCents, timestamp)
        // El stock cambió: el Dashboard debe releer.
        changes.notifyChanged()
        Sale(id = id, timestamp = timestamp, lines = lines, totalCents = total, receivedCents = receivedCents)
    }

    override suspend fun getSale(id: Long): Result<Sale?> = call { dao.getSale(id) }

    private suspend inline fun <T> call(crossinline block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(if (e is DataError) e else e.toDataError())
    }
}
