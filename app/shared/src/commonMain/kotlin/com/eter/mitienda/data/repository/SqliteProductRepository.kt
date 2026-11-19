package com.eter.mitienda.data.repository

import com.eter.mitienda.data.local.ProductDao
import com.eter.mitienda.domain.model.DataError
import com.eter.mitienda.domain.model.Product
import com.eter.mitienda.domain.repository.ProductRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

/**
 * Repositorio respaldado por SQLite.
 *
 * Cada escritura exitosa avisa a [changes]; quien observe [observeProducts]
 * vuelve a leer la tabla. Como hay una única instancia (AppContainer), el
 * Dashboard se entera de los cambios hechos desde cualquier pantalla.
 */
class SqliteProductRepository(
    private val dao: ProductDao,
    private val changes: DatabaseChanges,
) : ProductRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeProducts(): Flow<List<Product>> =
        changes.version.mapLatest {
            try {
                dao.getAllProducts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.toDataError()
            }
        }

    override suspend fun getProductById(id: Long): Result<Product?> =
        dbCall { dao.getProductById(id) }

    override suspend fun getProductByBarcode(barcode: String): Result<Product?> =
        dbCall { dao.getProductByBarcode(barcode.trim()) }

    override suspend fun insertProduct(product: Product): Result<Long> =
        dbCall(barcode = product.barcode) { dao.insertProduct(product) }
            .onSuccess { invalidate() }

    override suspend fun updateProduct(product: Product): Result<Unit> =
        dbCall(barcode = product.barcode) {
            if (dao.updateProduct(product) == 0) throw DataError.NotFound(product.id)
        }.onSuccess { invalidate() }

    override suspend fun updateProductStock(id: Long, newStock: Int): Result<Unit> =
        dbCall {
            if (dao.updateProductStock(id, newStock) == 0) throw DataError.NotFound(id)
        }.onSuccess { invalidate() }

    override suspend fun adjustProductStock(id: Long, delta: Int): Result<Unit> =
        dbCall {
            if (dao.adjustProductStock(id, delta) == 0) throw DataError.NotFound(id)
        }.onSuccess { invalidate() }

    override suspend fun getKnownLocations(): Result<List<String>> =
        dbCall { dao.getKnownLocations() }

    override suspend fun deleteProduct(id: Long): Result<Unit> =
        dbCall {
            if (dao.deleteProduct(id) == 0) throw DataError.NotFound(id)
        }.onSuccess { invalidate() }

    private fun invalidate() = changes.notifyChanged()

    /**
     * Ejecuta una operación y convierte cualquier fallo en `Result.failure(DataError)`.
     * La cancelación de corrutinas se relanza: no es un error, es el usuario saliendo.
     */
    private suspend inline fun <T> dbCall(
        barcode: String? = null,
        crossinline block: suspend () -> T,
    ): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e.toDataError(barcode))
    }
}

/**
 * Traduce excepciones de SQLite por su mensaje: los drivers de Android e iOS
 * lanzan clases distintas, pero ambos incluyen el texto estándar de SQLite.
 */
internal fun Throwable.toDataError(barcode: String? = null): DataError {
    if (this is DataError) return this
    val msg = message.orEmpty().lowercase()
    return when {
        "unique constraint failed" in msg -> DataError.DuplicateBarcode(barcode.orEmpty())
        "check constraint failed" in msg -> DataError.InvalidData(this)
        "database is locked" in msg ||
            "database table is locked" in msg ||
            "sqlite_busy" in msg ||
            "sqlite_locked" in msg -> DataError.DatabaseBusy(this)
        else -> DataError.Unknown(this)
    }
}
