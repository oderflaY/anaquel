package com.eter.mitienda.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import com.eter.mitienda.data.local.ProductosTable.BARCODE
import com.eter.mitienda.data.local.ProductosTable.CATEGORY_ID
import com.eter.mitienda.data.local.ProductosTable.COST
import com.eter.mitienda.data.local.ProductosTable.DESCRIPTION
import com.eter.mitienda.data.local.ProductosTable.ID
import com.eter.mitienda.data.local.ProductosTable.IMAGE_PATH
import com.eter.mitienda.data.local.ProductosTable.LOCATION
import com.eter.mitienda.data.local.ProductosTable.MIN_STOCK
import com.eter.mitienda.data.local.ProductosTable.NAME
import com.eter.mitienda.data.local.ProductosTable.PRICE
import com.eter.mitienda.data.local.ProductosTable.STOCK
import com.eter.mitienda.data.local.ProductosTable.TABLE
import com.eter.mitienda.data.local.ProductosTable.UPDATED_AT
import com.eter.mitienda.domain.model.Category
import com.eter.mitienda.domain.model.Product
import kotlin.math.roundToLong
import kotlin.time.Clock

/**
 * DAO con SQL crudo y sentencias preparadas (parámetros `?`, nunca
 * concatenación: inmune a inyección SQL). Todas las funciones son `suspend` y
 * se ejecutan en la cola serial de [PapeleriaDatabase].
 *
 * Lanza las excepciones de SQLite tal cual; el repositorio las traduce.
 */
class ProductDao(private val db: PapeleriaDatabase) {

    suspend fun insertProduct(product: Product): Long = db.withConnection { conn ->
        conn.prepare(
            """
            INSERT INTO $TABLE ($BARCODE, $NAME, $DESCRIPTION, $PRICE, $COST, $STOCK, $MIN_STOCK,
                $CATEGORY_ID, $LOCATION, $IMAGE_PATH, $UPDATED_AT)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindProductColumns(product)
            stmt.step()
        }
        conn.lastInsertRowId()
    }

    suspend fun getAllProducts(): List<Product> = db.withConnection { conn ->
        conn.prepare("$SELECT_ALL ORDER BY p.$NAME COLLATE NOCASE").use { stmt ->
            buildList { while (stmt.step()) add(stmt.toProduct()) }
        }
    }

    suspend fun getProductById(id: Long): Product? = db.withConnection { conn ->
        conn.prepare("$SELECT_ALL WHERE p.$ID = ?").use { stmt ->
            stmt.bindLong(1, id)
            if (stmt.step()) stmt.toProduct() else null
        }
    }

    /** Usa el índice UNIQUE: `SEARCH productos USING INDEX idx_productos_codigo_barras`. */
    suspend fun getProductByBarcode(barcode: String): Product? = db.withConnection { conn ->
        conn.prepare("$SELECT_ALL WHERE p.$BARCODE = ?").use { stmt ->
            stmt.bindText(1, barcode)
            if (stmt.step()) stmt.toProduct() else null
        }
    }

    /** @return filas afectadas (0 si el producto ya no existe). */
    suspend fun updateProduct(product: Product): Int = db.withConnection { conn ->
        conn.prepare(
            """
            UPDATE $TABLE SET $BARCODE = ?, $NAME = ?, $DESCRIPTION = ?, $PRICE = ?, $COST = ?,
                $STOCK = ?, $MIN_STOCK = ?, $CATEGORY_ID = ?, $LOCATION = ?, $IMAGE_PATH = ?, $UPDATED_AT = ?
            WHERE $ID = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindProductColumns(product)
            stmt.bindLong(12, product.id)
            stmt.step()
        }
        conn.changes()
    }

    /** Actualización mínima (2 columnas) para ajustes rápidos de inventario. */
    suspend fun updateProductStock(id: Long, newStock: Int): Int = db.withConnection { conn ->
        conn.prepare("UPDATE $TABLE SET $STOCK = ?, $UPDATED_AT = ? WHERE $ID = ?").use { stmt ->
            stmt.bindLong(1, newStock.toLong())
            stmt.bindLong(2, nowMillis())
            stmt.bindLong(3, id)
            stmt.step()
        }
        conn.changes()
    }

    /** Suma/resta en la propia sentencia: no depende de un valor leído antes. */
    suspend fun adjustProductStock(id: Long, delta: Int): Int = db.withConnection { conn ->
        conn.prepare("UPDATE $TABLE SET $STOCK = MAX(0, $STOCK + ?), $UPDATED_AT = ? WHERE $ID = ?").use { stmt ->
            stmt.bindLong(1, delta.toLong())
            stmt.bindLong(2, nowMillis())
            stmt.bindLong(3, id)
            stmt.step()
        }
        conn.changes()
    }

    suspend fun getKnownLocations(): List<String> = db.withConnection { conn ->
        conn.prepare(
            """
            SELECT DISTINCT $LOCATION FROM $TABLE
            WHERE $LOCATION IS NOT NULL AND $LOCATION <> ''
            ORDER BY $LOCATION COLLATE NOCASE
            """.trimIndent(),
        ).use { stmt ->
            buildList { while (stmt.step()) add(stmt.getText(0)) }
        }
    }

    suspend fun deleteProduct(id: Long): Int = db.withConnection { conn ->
        conn.prepare("DELETE FROM $TABLE WHERE $ID = ?").use { stmt ->
            stmt.bindLong(1, id)
            stmt.step()
        }
        conn.changes()
    }

    // --- Mapeo fila <-> dominio -------------------------------------------------

    /** Enlaza las columnas 1..11 en el orden de INSERT/UPDATE. */
    private fun SQLiteStatement.bindProductColumns(product: Product) {
        bindText(1, product.barcode)
        bindText(2, product.name)
        bindTextOrNull(3, product.description)
        bindDouble(4, product.priceCents.toMoney())
        bindDouble(5, product.costCents.toMoney())
        bindLong(6, product.stock.toLong())
        bindLong(7, product.minStock.toLong())
        product.category?.let { bindLong(8, it.id) } ?: bindNull(8)
        bindTextOrNull(9, product.location)
        bindTextOrNull(10, product.imagePath)
        bindLong(11, nowMillis())
    }

    /** Lee una fila con el orden de columnas de [SELECT_ALL]. */
    private fun SQLiteStatement.toProduct() = Product(
        id = getLong(0),
        barcode = getTextOrNull(1).orEmpty(),
        name = getText(2),
        description = getTextOrNull(3),
        priceCents = getDouble(4).toCents(),
        costCents = getDouble(5).toCents(),
        stock = getLong(6).toInt(),
        minStock = getLong(7).toInt(),
        category = if (isNull(8)) null else Category(id = getLong(8), name = getText(9)),
        location = getTextOrNull(10),
        imagePath = getTextOrNull(11),
        updatedAt = if (isNull(12)) 0 else getLong(12),
    )

    private companion object {
        /** LEFT JOIN: los productos sin categoría también aparecen (con NULL). */
        val SELECT_ALL = """
            SELECT p.$ID, p.$BARCODE, p.$NAME, p.$DESCRIPTION, p.$PRICE, p.$COST, p.$STOCK, p.$MIN_STOCK,
                c.${CategoriasTable.ID}, c.${CategoriasTable.NAME}, p.$LOCATION, p.$IMAGE_PATH, p.$UPDATED_AT
            FROM $TABLE p
            LEFT JOIN ${CategoriasTable.TABLE} c ON c.${CategoriasTable.ID} = p.$CATEGORY_ID
        """.trimIndent()
    }
}

// --- Utilidades compartidas por los DAO -------------------------------------------

internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

// El esquema pide REAL; dentro de la app se trabaja en centavos exactos.
internal fun Long.toMoney(): Double = this / 100.0
internal fun Double.toCents(): Long = (this * 100).roundToLong()

internal fun SQLiteStatement.bindTextOrNull(index: Int, value: String?) {
    if (value == null) bindNull(index) else bindText(index, value)
}

internal fun SQLiteStatement.getTextOrNull(index: Int): String? =
    if (isNull(index)) null else getText(index)

internal fun SQLiteConnection.lastInsertRowId(): Long =
    prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }

internal fun SQLiteConnection.changes(): Int =
    prepare("SELECT changes()").use { it.step(); it.getLong(0).toInt() }
