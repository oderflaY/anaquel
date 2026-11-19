package com.eter.mitienda.domain.repository

import com.eter.mitienda.domain.model.Product
import kotlinx.coroutines.flow.Flow

/**
 * Puerto de acceso a productos. La UI depende de esta interfaz, nunca de SQLite.
 *
 * Las operaciones puntuales devuelven [Result]: en caso de fallo contienen un
 * [com.eter.mitienda.domain.model.DataError], nunca lanzan.
 */
interface ProductRepository {
    /**
     * Lista completa y reactiva: emite al suscribirse y de nuevo tras cada
     * escritura. Es lo que mantiene el Dashboard actualizado solo.
     * Si la lectura falla, el flujo termina con un DataError.
     */
    fun observeProducts(): Flow<List<Product>>

    suspend fun getProductById(id: Long): Result<Product?>

    /** Búsqueda exacta por índice (escaneo). `null` si no existe. */
    suspend fun getProductByBarcode(barcode: String): Result<Product?>

    /** Devuelve el id generado. */
    suspend fun insertProduct(product: Product): Result<Long>

    suspend fun updateProduct(product: Product): Result<Unit>

    suspend fun updateProductStock(id: Long, newStock: Int): Result<Unit>

    /**
     * Suma [delta] al stock de forma atómica en SQLite (nunca baja de 0). A
     * diferencia de [updateProductStock], varios toques rápidos en "+" no se
     * pisan entre sí aunque la lista aún no se haya refrescado.
     */
    suspend fun adjustProductStock(id: Long, delta: Int): Result<Unit>

    /** Ubicaciones ya usadas (sin repetir), para sugerirlas al capturar. */
    suspend fun getKnownLocations(): Result<List<String>>

    suspend fun deleteProduct(id: Long): Result<Unit>
}
