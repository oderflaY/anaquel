package com.eter.mitienda.data.local

import androidx.sqlite.execSQL
import com.eter.mitienda.data.local.VentasTable.DATE
import com.eter.mitienda.data.local.VentasTable.ID
import com.eter.mitienda.data.local.VentasTable.ITEM_COUNT
import com.eter.mitienda.data.local.VentasTable.LINES_TABLE
import com.eter.mitienda.data.local.VentasTable.LINE_BARCODE
import com.eter.mitienda.data.local.VentasTable.LINE_NAME
import com.eter.mitienda.data.local.VentasTable.LINE_PRODUCT_ID
import com.eter.mitienda.data.local.VentasTable.LINE_QUANTITY
import com.eter.mitienda.data.local.VentasTable.LINE_SALE_ID
import com.eter.mitienda.data.local.VentasTable.LINE_UNIT_PRICE
import com.eter.mitienda.data.local.VentasTable.RECEIVED
import com.eter.mitienda.data.local.VentasTable.TABLE
import com.eter.mitienda.data.local.VentasTable.TOTAL
import com.eter.mitienda.domain.model.Sale
import com.eter.mitienda.domain.model.SaleLine

/** SQL crudo de ventas. Lanza las excepciones de SQLite tal cual. */
class SaleDao(private val db: PapeleriaDatabase) {

    /**
     * Inserta la venta, sus renglones y descuenta el stock en una sola
     * transacción. Si algo falla (BD llena, bloqueo…) se revierte todo: nunca
     * queda una venta sin renglones ni un stock descontado sin venta.
     *
     * @return id de la venta.
     */
    suspend fun insertSale(lines: List<SaleLine>, totalCents: Long, receivedCents: Long?, timestamp: Long): Long =
        db.withConnection { conn ->
            conn.execSQL("BEGIN IMMEDIATE")
            try {
                conn.prepare("INSERT INTO $TABLE ($DATE, $TOTAL, $RECEIVED, $ITEM_COUNT) VALUES (?, ?, ?, ?)").use { stmt ->
                    stmt.bindLong(1, timestamp)
                    stmt.bindDouble(2, totalCents.toMoney())
                    receivedCents?.let { stmt.bindDouble(3, it.toMoney()) } ?: stmt.bindNull(3)
                    stmt.bindLong(4, lines.sumOf { it.quantity }.toLong())
                    stmt.step()
                }
                val saleId = conn.lastInsertRowId()

                conn.prepare(
                    """
                    INSERT INTO $LINES_TABLE ($LINE_SALE_ID, $LINE_PRODUCT_ID, $LINE_BARCODE, $LINE_NAME, $LINE_UNIT_PRICE, $LINE_QUANTITY)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { stmt ->
                    lines.forEach { line ->
                        stmt.reset()
                        stmt.clearBindings()
                        stmt.bindLong(1, saleId)
                        line.productId?.let { stmt.bindLong(2, it) } ?: stmt.bindNull(2)
                        stmt.bindText(3, line.barcode)
                        stmt.bindText(4, line.name)
                        stmt.bindDouble(5, line.unitPriceCents.toMoney())
                        stmt.bindLong(6, line.quantity.toLong())
                        stmt.step()
                    }
                }

                // Descuento atómico; nunca baja de 0 aunque se venda más de lo registrado.
                conn.prepare(
                    """
                    UPDATE ${ProductosTable.TABLE}
                    SET ${ProductosTable.STOCK} = MAX(0, ${ProductosTable.STOCK} - ?), ${ProductosTable.UPDATED_AT} = ?
                    WHERE ${ProductosTable.ID} = ?
                    """.trimIndent(),
                ).use { stmt ->
                    lines.filter { it.productId != null }.forEach { line ->
                        stmt.reset()
                        stmt.clearBindings()
                        stmt.bindLong(1, line.quantity.toLong())
                        stmt.bindLong(2, timestamp)
                        stmt.bindLong(3, line.productId!!)
                        stmt.step()
                    }
                }

                conn.execSQL("COMMIT")
                saleId
            } catch (e: Throwable) {
                conn.execSQL("ROLLBACK")
                throw e
            }
        }

    suspend fun getSale(id: Long): Sale? = db.withConnection { conn ->
        val header = conn.prepare("SELECT $DATE, $TOTAL, $RECEIVED FROM $TABLE WHERE $ID = ?").use { stmt ->
            stmt.bindLong(1, id)
            if (stmt.step()) {
                Triple(stmt.getLong(0), stmt.getDouble(1).toCents(), if (stmt.isNull(2)) null else stmt.getDouble(2).toCents())
            } else {
                null
            }
        } ?: return@withConnection null

        val lines = conn.prepare(
            """
            SELECT $LINE_PRODUCT_ID, $LINE_BARCODE, $LINE_NAME, $LINE_UNIT_PRICE, $LINE_QUANTITY
            FROM $LINES_TABLE WHERE $LINE_SALE_ID = ? ORDER BY $ID
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, id)
            buildList {
                while (stmt.step()) {
                    add(
                        SaleLine(
                            productId = if (stmt.isNull(0)) null else stmt.getLong(0),
                            barcode = stmt.getTextOrNull(1).orEmpty(),
                            name = stmt.getText(2),
                            unitPriceCents = stmt.getDouble(3).toCents(),
                            quantity = stmt.getLong(4).toInt(),
                        ),
                    )
                }
            }
        }
        Sale(id = id, timestamp = header.first, lines = lines, totalCents = header.second, receivedCents = header.third)
    }
}
