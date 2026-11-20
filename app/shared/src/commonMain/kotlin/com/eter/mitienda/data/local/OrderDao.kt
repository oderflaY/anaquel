package com.eter.mitienda.data.local

import androidx.sqlite.SQLiteStatement
import com.eter.mitienda.data.local.PedidosTable.CREATED_AT
import com.eter.mitienda.data.local.PedidosTable.CUSTOMER
import com.eter.mitienda.data.local.PedidosTable.DELIVERY_AT
import com.eter.mitienda.data.local.PedidosTable.DEPOSIT
import com.eter.mitienda.data.local.PedidosTable.DESCRIPTION
import com.eter.mitienda.data.local.PedidosTable.ID
import com.eter.mitienda.data.local.PedidosTable.KIND
import com.eter.mitienda.data.local.PedidosTable.NOTES
import com.eter.mitienda.data.local.PedidosTable.PHONE
import com.eter.mitienda.data.local.PedidosTable.PRICE
import com.eter.mitienda.data.local.PedidosTable.STATUS
import com.eter.mitienda.data.local.PedidosTable.TABLE
import com.eter.mitienda.data.local.PedidosTable.UPDATED_AT
import com.eter.mitienda.domain.model.Order
import com.eter.mitienda.domain.model.OrderStatus

/** SQL crudo de `pedidos`. Lanza las excepciones de SQLite tal cual. */
class OrderDao(private val db: PapeleriaDatabase) {

    suspend fun getAll(): List<Order> = db.withConnection { conn ->
        conn.prepare("$SELECT ORDER BY $DELIVERY_AT").use { stmt ->
            buildList { while (stmt.step()) add(stmt.toOrder()) }
        }
    }

    /** Pedidos abiertos (usa idx_pedidos_estado_fecha). */
    suspend fun getOpen(): List<Order> = db.withConnection { conn ->
        conn.prepare("$SELECT WHERE $STATUS IN (?, ?, ?) ORDER BY $DELIVERY_AT").use { stmt ->
            stmt.bindText(1, OrderStatus.PENDING.name)
            stmt.bindText(2, OrderStatus.IN_PROGRESS.name)
            stmt.bindText(3, OrderStatus.READY.name)
            buildList { while (stmt.step()) add(stmt.toOrder()) }
        }
    }

    suspend fun get(id: Long): Order? = db.withConnection { conn ->
        conn.prepare("$SELECT WHERE $ID = ?").use { stmt ->
            stmt.bindLong(1, id)
            if (stmt.step()) stmt.toOrder() else null
        }
    }

    suspend fun insert(order: Order): Long = db.withConnection { conn ->
        val now = nowMillis()
        conn.prepare(
            """
            INSERT INTO $TABLE ($CUSTOMER, $PHONE, $KIND, $DESCRIPTION, $DELIVERY_AT, $PRICE, $DEPOSIT, $STATUS, $NOTES, $CREATED_AT, $UPDATED_AT)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindOrder(order)
            stmt.bindLong(10, now)
            stmt.bindLong(11, now)
            stmt.step()
        }
        conn.idInsertado(TABLE)
    }

    suspend fun update(order: Order): Int = db.withConnection { conn ->
        val habia = conn.existe(TABLE, order.id)
        conn.prepare(
            """
            UPDATE $TABLE SET $CUSTOMER = ?, $PHONE = ?, $KIND = ?, $DESCRIPTION = ?, $DELIVERY_AT = ?, $PRICE = ?,
                $DEPOSIT = ?, $STATUS = ?, $NOTES = ?, $UPDATED_AT = ?
            WHERE $ID = ?
            """.trimIndent(),
        ).use { stmt ->
            stmt.bindOrder(order)
            stmt.bindLong(10, nowMillis())
            stmt.bindLong(11, order.id)
            stmt.step()
        }
        habia
    }

    suspend fun updateStatus(id: Long, status: OrderStatus): Int = db.withConnection { conn ->
        val habia = conn.existe(TABLE, id)
        conn.prepare("UPDATE $TABLE SET $STATUS = ?, $UPDATED_AT = ? WHERE $ID = ?").use { stmt ->
            stmt.bindText(1, status.name)
            stmt.bindLong(2, nowMillis())
            stmt.bindLong(3, id)
            stmt.step()
        }
        habia
    }

    suspend fun delete(id: Long): Int = db.withConnection { conn ->
        val habia = conn.existe(TABLE, id)
        conn.prepare("DELETE FROM $TABLE WHERE $ID = ?").use { stmt ->
            stmt.bindLong(1, id)
            stmt.step()
        }
        habia
    }

    suspend fun getKinds(): List<String> = db.withConnection { conn ->
        conn.prepare("SELECT $KIND FROM $TABLE GROUP BY $KIND COLLATE NOCASE ORDER BY COUNT(*) DESC LIMIT 8").use { stmt ->
            buildList { while (stmt.step()) add(stmt.getText(0)) }
        }
    }

    /** Columnas 1..9 en el orden de INSERT/UPDATE. */
    private fun SQLiteStatement.bindOrder(order: Order) {
        bindText(1, order.customer)
        bindTextOrNull(2, order.phone)
        bindText(3, order.kind)
        bindText(4, order.description)
        bindLong(5, order.deliveryAt)
        bindDouble(6, order.priceCents.toMoney())
        bindDouble(7, order.depositCents.toMoney())
        bindText(8, order.status.name)
        bindTextOrNull(9, order.notes)
    }

    private fun SQLiteStatement.toOrder() = Order(
        id = getLong(0),
        customer = getText(1),
        phone = getTextOrNull(2),
        kind = getText(3),
        description = getText(4),
        deliveryAt = getLong(5),
        priceCents = getDouble(6).toCents(),
        depositCents = getDouble(7).toCents(),
        status = OrderStatus.fromKey(getText(8)),
        notes = getTextOrNull(9),
        createdAt = getLong(10),
    )

    private companion object {
        val SELECT = """
            SELECT $ID, $CUSTOMER, $PHONE, $KIND, $DESCRIPTION, $DELIVERY_AT, $PRICE, $DEPOSIT, $STATUS, $NOTES, $CREATED_AT
            FROM $TABLE
        """.trimIndent()
    }
}
