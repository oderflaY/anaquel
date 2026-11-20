package com.eter.mitienda.data.local

import com.eter.mitienda.data.local.CategoriasTable.ID
import com.eter.mitienda.data.local.CategoriasTable.NAME
import com.eter.mitienda.data.local.CategoriasTable.TABLE
import com.eter.mitienda.domain.model.Category
import com.eter.mitienda.domain.model.CategoryWithCount

/** SQL crudo de la tabla `categorias`. Lanza las excepciones de SQLite tal cual. */
class CategoryDao(private val db: PapeleriaDatabase) {

    /** Una sola consulta con el conteo (LEFT JOIN + GROUP BY), usando idx_productos_categoria. */
    suspend fun getCategoriesWithCount(): List<CategoryWithCount> = db.withConnection { conn ->
        conn.prepare(
            """
            SELECT c.$ID, c.$NAME, COUNT(p.${ProductosTable.ID})
            FROM $TABLE c
            LEFT JOIN ${ProductosTable.TABLE} p ON p.${ProductosTable.CATEGORY_ID} = c.$ID
            GROUP BY c.$ID
            ORDER BY c.$NAME COLLATE NOCASE
            """.trimIndent(),
        ).use { stmt ->
            buildList {
                while (stmt.step()) {
                    add(CategoryWithCount(Category(stmt.getLong(0), stmt.getText(1)), stmt.getLong(2).toInt()))
                }
            }
        }
    }

    suspend fun insert(name: String): Long = db.withConnection { conn ->
        conn.prepare("INSERT INTO $TABLE ($NAME) VALUES (?)").use { stmt ->
            stmt.bindText(1, name)
            stmt.step()
        }
        conn.idInsertado(TABLE)
    }

    suspend fun rename(id: Long, name: String): Int = db.withConnection { conn ->
        val habia = conn.existe(TABLE, id)
        conn.prepare("UPDATE $TABLE SET $NAME = ? WHERE $ID = ?").use { stmt ->
            stmt.bindText(1, name)
            stmt.bindLong(2, id)
            stmt.step()
        }
        habia
    }

    /** Los productos quedan con categoria_id = NULL por la FK `ON DELETE SET NULL`. */
    suspend fun delete(id: Long): Int = db.withConnection { conn ->
        val habia = conn.existe(TABLE, id)
        conn.prepare("DELETE FROM $TABLE WHERE $ID = ?").use { stmt ->
            stmt.bindLong(1, id)
            stmt.step()
        }
        habia
    }
}
