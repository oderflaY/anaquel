package com.eter.mitienda.data

import androidx.sqlite.driver.AndroidSQLiteDriver
import com.eter.mitienda.data.local.PapeleriaDatabase
import com.eter.mitienda.data.local.ProductDao
import com.eter.mitienda.data.repository.DatabaseChanges
import com.eter.mitienda.data.repository.SqliteProductRepository
import com.eter.mitienda.domain.model.DataError
import com.eter.mitienda.domain.model.Product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Prueba el esquema, el DAO y el repositorio reales en el dispositivo (SQLite del sistema, BD en memoria). */
class ProductRepositoryTest {

    private val database = PapeleriaDatabase(AndroidSQLiteDriver(), ":memory:")
    private val repository = SqliteProductRepository(ProductDao(database), DatabaseChanges())

    private val cuaderno = Product(
        barcode = "7501234567890",
        name = "Cuaderno profesional",
        description = "100 hojas",
        priceCents = 4_550,
        costCents = 3_010,
        stock = 24,
        category = null,
        location = "Pasillo 1",
        minStock = 3,
        imagePath = "/data/user/0/com.eter.mitienda/files/product_images/1.jpg",
    )

    @AfterTest
    fun tearDown() = runTest { database.close() }

    @Test
    fun insertAndReadBack_keepsAllFieldsAndExactCents() = runTest {
        val id = repository.insertProduct(cuaderno).getOrThrow()

        val stored = repository.getProductByBarcode(cuaderno.barcode).getOrThrow()!!
        assertEquals(cuaderno.copy(id = id, updatedAt = stored.updatedAt), stored)
        assertTrue(stored.updatedAt > 0, "fecha_actualizacion debe fijarse al insertar")
        assertEquals(listOf(stored), repository.observeProducts().first())
    }

    @Test
    fun duplicateBarcode_returnsDuplicateBarcodeError() = runTest {
        repository.insertProduct(cuaderno).getOrThrow()

        val error = repository.insertProduct(cuaderno.copy(name = "Otro")).exceptionOrNull()
        assertIs<DataError.DuplicateBarcode>(error)
    }

    @Test
    fun updateStock_andRejectNegativeStock() = runTest {
        val id = repository.insertProduct(cuaderno).getOrThrow()

        repository.updateProductStock(id, 7).getOrThrow()
        assertEquals(7, repository.getProductById(id).getOrThrow()!!.stock)

        assertIs<DataError.InvalidData>(repository.updateProductStock(id, -1).exceptionOrNull())
        assertIs<DataError.NotFound>(repository.updateProductStock(999, 1).exceptionOrNull())
    }

    @Test
    fun delete_removesRowAndSecondDeleteIsNotFound() = runTest {
        val id = repository.insertProduct(cuaderno).getOrThrow()

        repository.deleteProduct(id).getOrThrow()
        assertNull(repository.getProductById(id).getOrThrow())
        assertIs<DataError.NotFound>(repository.deleteProduct(id).exceptionOrNull())
    }

    @Test
    fun observeProducts_emitsAgainAfterWrites() = runTest {
        assertEquals(emptyList(), repository.observeProducts().first())
        repository.insertProduct(cuaderno).getOrThrow()
        assertEquals(1, repository.observeProducts().first().size)
    }

    @Test
    fun barcodeLookup_usesIndexInsteadOfFullScan() = runTest {
        repository.insertProduct(cuaderno).getOrThrow() // fuerza abrir y migrar
        val plan = database.withConnection { conn ->
            conn.prepare("EXPLAIN QUERY PLAN SELECT * FROM productos WHERE codigo_barras = ?").use { stmt ->
                stmt.bindText(1, "x")
                buildString { while (stmt.step()) append(stmt.getText(3)) }
            }
        }
        assertTrue("USING INDEX idx_productos_codigo_barras" in plan, "Plan: $plan")
    }

    @Test
    fun schema_hasVersionAndNoBlobColumns() = runTest {
        repository.observeProducts().first()
        val (version, columns) = database.withConnection { conn ->
            val v = conn.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
            val cols = conn.prepare("PRAGMA table_info(productos)").use { stmt ->
                buildMap { while (stmt.step()) put(stmt.getText(1), stmt.getText(2)) }
            }
            v to cols
        }
        assertEquals(PapeleriaDatabase.SCHEMA_VERSION.toLong(), version)
        assertEquals(
            mapOf(
                "id" to "INTEGER", "codigo_barras" to "TEXT", "nombre" to "TEXT",
                "descripcion" to "TEXT", "precio_venta" to "REAL", "costo" to "REAL",
                "stock" to "INTEGER", "categoria" to "TEXT", "ruta_imagen" to "TEXT",
                "fecha_actualizacion" to "INTEGER", "categoria_id" to "INTEGER",
                "ubicacion" to "TEXT", "stock_minimo" to "INTEGER",
            ),
            columns,
        )
    }
}
