package com.eter.mitienda.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Punto único de acceso al archivo SQLite `papeleria_inventory.db`.
 *
 * Concurrencia: se usa UNA sola conexión y todas las operaciones pasan por un
 * dispatcher con paralelismo 1. Así nunca hay dos escrituras simultáneas
 * dentro de la app (no hay "database is locked" interno) y el hilo principal
 * jamás toca el disco. `busy_timeout` cubre bloqueos externos (p. ej. la
 * transferencia por Bluetooth de la Fase 3 copiando el archivo).
 *
 * @param driver `AndroidSQLiteDriver` en Android, `NativeSQLiteDriver` en iOS:
 * ambos usan el SQLite del sistema operativo, sin librerías nativas extra.
 * @param path ruta absoluta del archivo, resuelta por cada plataforma.
 */
class PapeleriaDatabase(
    private val driver: SQLiteDriver,
    private val path: String,
) {
    /** Cola serial sobre el pool de IO: un solo acceso a la vez, fuera del hilo de UI. */
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Se abre en el primer uso, ya dentro de [dispatcher]. Si la apertura falla
     * (disco lleno, archivo corrupto) la excepción llega al repositorio, que la
     * convierte en DataError, y el siguiente intento vuelve a probar.
     */
    private var connection: SQLiteConnection? = null

    /** Ejecuta [block] con la conexión, en la cola serial de la BD. */
    suspend fun <T> withConnection(block: (SQLiteConnection) -> T): T =
        withContext(dispatcher) {
            val conn = connection ?: open().also { connection = it }
            block(conn)
        }

    /**
     * Acceso exclusivo al ARCHIVO con la conexión cerrada (para copiarlo o
     * reemplazarlo). Cerrar la última conexión vuelca el WAL al .db, así que el
     * archivo queda autocontenido. Como corre en la cola serial, ninguna
     * consulta puede ejecutarse entretanto; la siguiente reabre la BD sola.
     */
    suspend fun <T> withExclusiveAccess(block: (driver: SQLiteDriver) -> T): T =
        withContext(dispatcher) {
            connection?.close()
            connection = null
            block(driver)
        }

    private fun open(): SQLiteConnection {
        val conn = driver.open(path)
        try {
            configure(conn)
            migrate(conn)
        } catch (e: Throwable) {
            conn.close()
            throw e
        }
        return conn
    }

    private fun configure(conn: SQLiteConnection) {
        // Espera hasta 5 s si otro proceso tiene el archivo bloqueado, en vez de fallar al instante.
        conn.execSQL("PRAGMA busy_timeout = 5000")
        // WAL: lecturas que no bloquean escrituras y escrituras más rápidas.
        conn.prepare("PRAGMA journal_mode = WAL").use { it.step() }
        conn.execSQL("PRAGMA synchronous = NORMAL")
        conn.execSQL("PRAGMA foreign_keys = ON")
    }

    /**
     * Migraciones con `PRAGMA user_version`. Para cambiar el esquema en el futuro:
     * subir [SCHEMA_VERSION] y añadir un bloque `if (version < N)`.
     */
    private fun migrate(conn: SQLiteConnection) {
        val version = conn.prepare("PRAGMA user_version").use { stmt ->
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }
        if (version >= SCHEMA_VERSION) return

        conn.execSQL("BEGIN IMMEDIATE")
        try {
            if (version < 1) {
                ProductosTable.CREATE_STATEMENTS.forEach(conn::execSQL)
            }
            if (version < 2) {
                // v2: categorías propias, ubicación y stock mínimo por producto.
                CategoriasTable.MIGRATION_V2.forEach(conn::execSQL)
            }
            if (version < 3) {
                // v3: ventas del punto de venta (ticket + renglones).
                VentasTable.MIGRATION_V3.forEach(conn::execSQL)
            }
            if (version < 4) {
                // v4: pedidos por encargo (piñatas, manualidades…).
                PedidosTable.MIGRATION_V4.forEach(conn::execSQL)
            }
            conn.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
            conn.execSQL("COMMIT")
        } catch (e: Throwable) {
            conn.execSQL("ROLLBACK")
            throw e
        }
    }

    /**
     * Vuelca el WAL al archivo principal. Necesario antes de copiar/enviar el
     * .db completo (Fase 3), o los últimos cambios quedarían en el `-wal`.
     */
    suspend fun checkpoint() = withConnection { conn ->
        conn.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
        Unit
    }

    suspend fun close() = withContext(dispatcher) {
        connection?.close()
        connection = null
    }

    companion object {
        const val NAME = "papeleria_inventory.db"
        const val SCHEMA_VERSION = 4
    }
}

/** Esquema de la tabla `productos`. Nombres de columna centralizados para el DAO. */
object ProductosTable {
    const val TABLE = "productos"
    const val ID = "id"
    const val BARCODE = "codigo_barras"
    const val NAME = "nombre"
    const val DESCRIPTION = "descripcion"
    const val PRICE = "precio_venta"
    const val COST = "costo"
    const val STOCK = "stock"
    /** v1: clave fija de categoría (NOTEBOOKS…). Desde v2 solo se lee para migrar. */
    const val LEGACY_CATEGORY = "categoria"
    /** v2: FK a [CategoriasTable]; al borrar la categoría queda en NULL. */
    const val CATEGORY_ID = "categoria_id"
    /** v2: dónde está físicamente el producto ("Pasillo 2 · Estante B"). */
    const val LOCATION = "ubicacion"
    /** v2: por debajo o igual a este stock se marca "Stock bajo". */
    const val MIN_STOCK = "stock_minimo"
    const val IMAGE_PATH = "ruta_imagen"
    const val UPDATED_AT = "fecha_actualizacion"

    /** Índice B-tree sobre el código de barras: búsqueda O(log n). */
    const val BARCODE_INDEX = "idx_productos_codigo_barras"

    val CREATE_STATEMENTS = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE (
            $ID          INTEGER PRIMARY KEY AUTOINCREMENT,
            $BARCODE     TEXT,
            $NAME        TEXT    NOT NULL,
            $DESCRIPTION TEXT,
            $PRICE       REAL    NOT NULL CHECK ($PRICE >= 0),
            $COST        REAL    NOT NULL CHECK ($COST >= 0),
            $STOCK       INTEGER NOT NULL DEFAULT 0 CHECK ($STOCK >= 0),
            $LEGACY_CATEGORY TEXT,
            $IMAGE_PATH  TEXT,   -- solo la ruta del archivo; jamás un BLOB
            $UPDATED_AT  INTEGER -- epoch en milisegundos
        )
        """.trimIndent(),
        // Equivale a `codigo_barras TEXT UNIQUE`: SQLite implementa UNIQUE con un
        // índice. Declararlo aparte le da un nombre explícito y evita crear un
        // segundo índice redundante que duplicaría espacio y coste de escritura.
        "CREATE UNIQUE INDEX IF NOT EXISTS $BARCODE_INDEX ON $TABLE($BARCODE)",
    )
}

/** Categorías creadas por el usuario (v2). */
object CategoriasTable {
    const val TABLE = "categorias"
    const val ID = "id"
    const val NAME = "nombre"

    val MIGRATION_V2 = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE (
            $ID   INTEGER PRIMARY KEY AUTOINCREMENT,
            $NAME TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim($NAME)) > 0)
        )
        """.trimIndent(),
        // Las categorías fijas de v1 pasan a ser categorías editables.
        "INSERT OR IGNORE INTO $TABLE ($NAME) VALUES ('Cuadernos'), ('Lápices'), ('Oficina')",
        "ALTER TABLE ${ProductosTable.TABLE} ADD COLUMN ${ProductosTable.CATEGORY_ID} " +
            "INTEGER REFERENCES $TABLE($ID) ON DELETE SET NULL",
        "ALTER TABLE ${ProductosTable.TABLE} ADD COLUMN ${ProductosTable.LOCATION} TEXT",
        "ALTER TABLE ${ProductosTable.TABLE} ADD COLUMN ${ProductosTable.MIN_STOCK} " +
            "INTEGER NOT NULL DEFAULT 5 CHECK (${ProductosTable.MIN_STOCK} >= 0)",
        """
        UPDATE ${ProductosTable.TABLE} SET ${ProductosTable.CATEGORY_ID} = (
            SELECT c.$ID FROM $TABLE c WHERE c.$NAME = CASE ${ProductosTable.LEGACY_CATEGORY}
                WHEN 'NOTEBOOKS' THEN 'Cuadernos'
                WHEN 'PENCILS' THEN 'Lápices'
                WHEN 'OFFICE' THEN 'Oficina'
            END
        )
        """.trimIndent(),
        // Filtrar por categoría y contar productos por categoría usan este índice.
        "CREATE INDEX IF NOT EXISTS idx_productos_categoria ON ${ProductosTable.TABLE}(${ProductosTable.CATEGORY_ID})",
    )
}

/**
 * Ventas (v3). Cada renglón guarda una COPIA del nombre, código y precio del
 * momento de la venta: si después cambia el precio o se borra el producto, el
 * ticket histórico sigue diciendo lo que se cobró.
 */
object VentasTable {
    const val TABLE = "ventas"
    const val ID = "id"
    const val DATE = "fecha"
    const val TOTAL = "total"
    const val RECEIVED = "recibido"
    const val ITEM_COUNT = "articulos"

    const val LINES_TABLE = "venta_detalle"
    const val LINE_SALE_ID = "venta_id"
    const val LINE_PRODUCT_ID = "producto_id"
    const val LINE_BARCODE = "codigo_barras"
    const val LINE_NAME = "nombre"
    const val LINE_UNIT_PRICE = "precio_unitario"
    const val LINE_QUANTITY = "cantidad"

    val MIGRATION_V3 = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE (
            $ID         INTEGER PRIMARY KEY AUTOINCREMENT,
            $DATE       INTEGER NOT NULL, -- epoch en milisegundos
            $TOTAL      REAL    NOT NULL CHECK ($TOTAL >= 0),
            $RECEIVED   REAL,             -- efectivo recibido; NULL si no se capturó
            $ITEM_COUNT INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS $LINES_TABLE (
            $ID              INTEGER PRIMARY KEY AUTOINCREMENT,
            $LINE_SALE_ID    INTEGER NOT NULL REFERENCES $TABLE($ID) ON DELETE CASCADE,
            $LINE_PRODUCT_ID INTEGER REFERENCES ${ProductosTable.TABLE}(${ProductosTable.ID}) ON DELETE SET NULL,
            $LINE_BARCODE    TEXT,
            $LINE_NAME       TEXT    NOT NULL,
            $LINE_UNIT_PRICE REAL    NOT NULL CHECK ($LINE_UNIT_PRICE >= 0),
            $LINE_QUANTITY   INTEGER NOT NULL CHECK ($LINE_QUANTITY > 0)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS idx_detalle_venta ON $LINES_TABLE($LINE_SALE_ID)",
        "CREATE INDEX IF NOT EXISTS idx_ventas_fecha ON $TABLE($DATE)",
    )
}

/** Pedidos por encargo (v4). */
object PedidosTable {
    const val TABLE = "pedidos"
    const val ID = "id"
    const val CUSTOMER = "cliente"
    const val PHONE = "telefono"
    const val KIND = "tipo"
    const val DESCRIPTION = "descripcion"
    const val DELIVERY_AT = "fecha_entrega"
    const val PRICE = "precio"
    const val DEPOSIT = "anticipo"
    const val STATUS = "estado"
    const val NOTES = "notas"
    const val CREATED_AT = "creado"
    const val UPDATED_AT = "actualizado"

    val MIGRATION_V4 = listOf(
        """
        CREATE TABLE IF NOT EXISTS $TABLE (
            $ID          INTEGER PRIMARY KEY AUTOINCREMENT,
            $CUSTOMER    TEXT    NOT NULL,
            $PHONE       TEXT,
            $KIND        TEXT    NOT NULL,          -- "Piñata", "Manualidad"… (texto libre)
            $DESCRIPTION TEXT    NOT NULL,
            $DELIVERY_AT INTEGER NOT NULL,          -- epoch en milisegundos
            $PRICE       REAL    NOT NULL CHECK ($PRICE >= 0),
            $DEPOSIT     REAL    NOT NULL DEFAULT 0 CHECK ($DEPOSIT >= 0),
            $STATUS      TEXT    NOT NULL,          -- clave de OrderStatus
            $NOTES       TEXT,
            $CREATED_AT  INTEGER NOT NULL,
            $UPDATED_AT  INTEGER NOT NULL
        )
        """.trimIndent(),
        // La lista principal es "abiertos ordenados por fecha de entrega".
        "CREATE INDEX IF NOT EXISTS idx_pedidos_estado_fecha ON $TABLE($STATUS, $DELIVERY_AT)",
    )
}
