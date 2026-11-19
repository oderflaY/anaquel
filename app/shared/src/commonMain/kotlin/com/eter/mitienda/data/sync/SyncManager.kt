package com.eter.mitienda.data.sync

import androidx.sqlite.SQLiteDriver
import com.eter.mitienda.data.local.PapeleriaDatabase
import com.eter.mitienda.data.local.ProductosTable

/**
 * Empaqueta la BD para enviarla y reemplaza la local con una recibida.
 *
 * Todo se ejecuta dentro de [PapeleriaDatabase.withExclusiveAccess]: la cola
 * serial de la BD queda tomada, así que ninguna lectura/escritura de la app se
 * cuela mientras se copia o se reemplaza el archivo.
 *
 * @param onDatabaseReplaced avisa al repositorio para que las pantallas
 * vuelvan a leer (el Dashboard se refresca solo tras importar).
 */
class SyncManager(
    private val database: PapeleriaDatabase,
    private val files: DatabaseFileStore,
    private val onDatabaseReplaced: () -> Unit,
) {

    /**
     * Deja una copia consistente lista para enviar y devuelve su ruta.
     *
     * Con WAL, los últimos cambios pueden estar aún en `-wal`. Se cierra la
     * conexión (lo que vuelca el WAL al .db y lo borra) y se copia un solo
     * archivo autocontenido. La conexión se reabre sola en el siguiente uso.
     */
    suspend fun prepareDatabaseForExport(): String = database.withExclusiveAccess { _ ->
        files.delete(files.exportPath)
        files.copy(files.databasePath, files.exportPath)
        files.exportPath
    }

    /** Respalda la BD actual como `backup_papeleria_<fecha>.db` y conserva las [MAX_BACKUPS] más recientes. */
    suspend fun createLocalBackup(): String = database.withExclusiveAccess { _ -> backupLocked() }

    /**
     * Reemplaza la BD local por la recibida:
     * 1. Verifica que el archivo sea una BD válida de esta app (no la abre la app todavía).
     * 2. Respalda la BD actual.
     * 3. Con la conexión cerrada, sustituye el archivo de forma atómica
     *    (copia a `.db.incoming` en la misma carpeta + rename).
     * 4. Al soltar la cola, la siguiente consulta reabre la BD nueva.
     *
     * @return ruta del respaldo creado.
     * @throws InvalidDatabaseException si el archivo recibido no sirve; en ese
     * caso la BD local queda intacta.
     */
    suspend fun importAndReplaceDatabase(newDbPath: String): String {
        val backup = database.withExclusiveAccess { driver ->
            validate(driver, newDbPath)
            val backupPath = backupLocked()
            val staging = files.databasePath + ".incoming"
            files.copy(newDbPath, staging)
            // Restos del WAL de la BD vieja: si quedaran, SQLite los aplicaría a la nueva.
            files.delete(files.databasePath + "-wal")
            files.delete(files.databasePath + "-shm")
            files.move(staging, files.databasePath)
            backupPath
        }
        files.delete(newDbPath)
        onDatabaseReplaced()
        return backup
    }

    /** Descarta un archivo recibido que el usuario no quiso importar. */
    fun discardReceived(path: String) = files.delete(path)

    private fun backupLocked(): String {
        val path = "${files.backupDir}/backup_papeleria_${files.timestamp()}.db"
        files.copy(files.databasePath, path)
        files.list(files.backupDir)
            .filter { it.startsWith("backup_papeleria_") && it.endsWith(".db") }
            .sortedDescending() // el nombre lleva la fecha: orden alfabético = cronológico
            .drop(MAX_BACKUPS)
            .forEach { files.delete("${files.backupDir}/$it") }
        return path
    }

    /**
     * Abre el candidato con una conexión aparte y comprueba cabecera, integridad,
     * tabla `productos` y versión de esquema compatible.
     */
    private fun validate(driver: SQLiteDriver, path: String) {
        val header = files.readHeader(path, SQLITE_HEADER.size)
        if (!header.contentEquals(SQLITE_HEADER)) throw InvalidDatabaseException("El archivo recibido no es una base de datos.")

        val connection = driver.open(path)
        try {
            val integrity = connection.prepare("PRAGMA quick_check").use { if (it.step()) it.getText(0) else "" }
            if (integrity != "ok") throw InvalidDatabaseException("El archivo recibido está dañado.")

            val hasTable = connection.prepare(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
            ).use {
                it.bindText(1, ProductosTable.TABLE)
                it.step() && it.getLong(0) > 0
            }
            if (!hasTable) throw InvalidDatabaseException("El archivo no es un inventario de esta app.")

            val version = connection.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0 }
            if (version > PapeleriaDatabase.SCHEMA_VERSION) {
                throw InvalidDatabaseException("El inventario viene de una versión más nueva de la app. Actualiza la app primero.")
            }
        } finally {
            connection.close()
        }
    }

    private companion object {
        const val MAX_BACKUPS = 5

        /** Los 16 bytes con los que empieza todo archivo SQLite 3. */
        val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    }
}

class InvalidDatabaseException(message: String) : Exception(message)
