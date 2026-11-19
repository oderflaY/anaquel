package com.eter.mitienda.data.local

import com.eter.mitienda.data.local.VentasTable.LINES_TABLE
import com.eter.mitienda.data.local.VentasTable.LINE_NAME
import com.eter.mitienda.data.local.VentasTable.LINE_PRODUCT_ID
import com.eter.mitienda.data.local.VentasTable.LINE_QUANTITY
import com.eter.mitienda.data.local.VentasTable.LINE_SALE_ID
import com.eter.mitienda.data.local.VentasTable.LINE_UNIT_PRICE

/** Fila de "ventas en el periodo" (sin renglones). */
data class SaleRow(val id: Long, val timestamp: Long, val totalCents: Long, val itemCount: Int)

/** Nombre + cantidad + importe, para rankings. */
data class RankRow(val label: String, val quantity: Int, val revenueCents: Long)

data class InventoryRow(
    val productCount: Int,
    val lowStockCount: Int,
    val costValueCents: Long,
    val saleValueCents: Long,
)

data class OpenOrdersRow(val count: Int, val pendingBalanceCents: Long)

/**
 * Consultas de solo lectura para la sección de gráficas. Las agregaciones
 * pesadas (SUM/GROUP BY) las hace SQLite; la agrupación por DÍA LOCAL se hace
 * en Kotlin porque depende de la zona horaria del teléfono.
 * Los rangos son [from, to) en epoch ms; usan idx_ventas_fecha.
 */
class StatsDao(private val db: PapeleriaDatabase) {

    private val v = VentasTable.TABLE
    private val p = ProductosTable.TABLE

    suspend fun sales(from: Long, to: Long): List<SaleRow> = db.withConnection { conn ->
        conn.prepare(
            """
            SELECT ${VentasTable.ID}, ${VentasTable.DATE}, ${VentasTable.TOTAL}, ${VentasTable.ITEM_COUNT}
            FROM $v WHERE ${VentasTable.DATE} >= ? AND ${VentasTable.DATE} < ?
            ORDER BY ${VentasTable.DATE} DESC
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, from)
            stmt.bindLong(2, to)
            buildList {
                while (stmt.step()) add(SaleRow(stmt.getLong(0), stmt.getLong(1), stmt.getDouble(2).toCents(), stmt.getLong(3).toInt()))
            }
        }
    }

    /**
     * Utilidad estimada = Σ (precio cobrado − costo ACTUAL) × cantidad.
     * Si el producto ya no existe se toma margen 0 (estimación conservadora).
     */
    suspend fun estimatedProfit(from: Long, to: Long): Long = db.withConnection { conn ->
        conn.prepare(
            """
            SELECT COALESCE(SUM((d.$LINE_UNIT_PRICE - COALESCE(pr.${ProductosTable.COST}, d.$LINE_UNIT_PRICE)) * d.$LINE_QUANTITY), 0)
            FROM $LINES_TABLE d
            JOIN $v s ON s.${VentasTable.ID} = d.$LINE_SALE_ID
            LEFT JOIN $p pr ON pr.${ProductosTable.ID} = d.$LINE_PRODUCT_ID
            WHERE s.${VentasTable.DATE} >= ? AND s.${VentasTable.DATE} < ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, from)
            stmt.bindLong(2, to)
            stmt.step()
            stmt.getDouble(0).toCents()
        }
    }

    /** Productos más vendidos por piezas (el nombre es el guardado en el ticket). */
    suspend fun topProducts(from: Long, to: Long, limit: Int): List<RankRow> = db.withConnection { conn ->
        conn.prepare(
            """
            SELECT MAX(d.$LINE_NAME), SUM(d.$LINE_QUANTITY), SUM(d.$LINE_UNIT_PRICE * d.$LINE_QUANTITY)
            FROM $LINES_TABLE d
            JOIN $v s ON s.${VentasTable.ID} = d.$LINE_SALE_ID
            WHERE s.${VentasTable.DATE} >= ? AND s.${VentasTable.DATE} < ?
            GROUP BY COALESCE(d.$LINE_PRODUCT_ID, d.$LINE_NAME)
            ORDER BY 2 DESC, 3 DESC
            LIMIT ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, from)
            stmt.bindLong(2, to)
            stmt.bindLong(3, limit.toLong())
            buildList {
                while (stmt.step()) add(RankRow(stmt.getText(0), stmt.getLong(1).toInt(), stmt.getDouble(2).toCents()))
            }
        }
    }

    /** Importe vendido por categoría (la categoría ACTUAL del producto). */
    suspend fun salesByCategory(from: Long, to: Long): List<RankRow> = db.withConnection { conn ->
        conn.prepare(
            """
            SELECT COALESCE(c.${CategoriasTable.NAME}, 'Sin categoría'), SUM(d.$LINE_QUANTITY), SUM(d.$LINE_UNIT_PRICE * d.$LINE_QUANTITY)
            FROM $LINES_TABLE d
            JOIN $v s ON s.${VentasTable.ID} = d.$LINE_SALE_ID
            LEFT JOIN $p pr ON pr.${ProductosTable.ID} = d.$LINE_PRODUCT_ID
            LEFT JOIN ${CategoriasTable.TABLE} c ON c.${CategoriasTable.ID} = pr.${ProductosTable.CATEGORY_ID}
            WHERE s.${VentasTable.DATE} >= ? AND s.${VentasTable.DATE} < ?
            GROUP BY 1
            ORDER BY 3 DESC
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindLong(1, from)
            stmt.bindLong(2, to)
            buildList {
                while (stmt.step()) add(RankRow(stmt.getText(0), stmt.getLong(1).toInt(), stmt.getDouble(2).toCents()))
            }
        }
    }

    suspend fun inventory(): InventoryRow = db.withConnection { conn ->
        conn.prepare(
            """
            SELECT COUNT(*),
                   COALESCE(SUM(${ProductosTable.STOCK} <= ${ProductosTable.MIN_STOCK}), 0),
                   COALESCE(SUM(${ProductosTable.STOCK} * ${ProductosTable.COST}), 0),
                   COALESCE(SUM(${ProductosTable.STOCK} * ${ProductosTable.PRICE}), 0)
            FROM $p
            """.trimIndent(),
        ).use { stmt ->
            stmt.step()
            InventoryRow(stmt.getLong(0).toInt(), stmt.getLong(1).toInt(), stmt.getDouble(2).toCents(), stmt.getDouble(3).toCents())
        }
    }

    suspend fun openOrders(): OpenOrdersRow = db.withConnection { conn ->
        conn.prepare(
            """
            SELECT COUNT(*), COALESCE(SUM(MAX(${PedidosTable.PRICE} - ${PedidosTable.DEPOSIT}, 0)), 0)
            FROM ${PedidosTable.TABLE}
            WHERE ${PedidosTable.STATUS} IN ('PENDING', 'IN_PROGRESS', 'READY')
            """.trimIndent(),
        ).use { stmt ->
            stmt.step()
            OpenOrdersRow(stmt.getLong(0).toInt(), stmt.getDouble(1).toCents())
        }
    }
}
