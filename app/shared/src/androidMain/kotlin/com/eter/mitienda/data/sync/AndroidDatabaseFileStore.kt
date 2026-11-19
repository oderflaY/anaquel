package com.eter.mitienda.data.sync

import android.content.Context
import com.eter.mitienda.data.local.PapeleriaDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * - BD: `databases/papeleria_inventory.db` (carpeta estándar de Android para SQLite).
 * - Respaldos: `files/backups/` (persistente; el sistema no lo limpia).
 * - Envío y recepción: `cache/sync/` (temporales: se borran tras usarse).
 */
class AndroidDatabaseFileStore(context: Context) : DatabaseFileStore {

    override val databasePath: String =
        context.getDatabasePath(PapeleriaDatabase.NAME).apply { parentFile?.mkdirs() }.absolutePath

    override val exportPath: String = File(context.cacheDir, "sync/export_papeleria.db").absolutePath

    override val incomingDir: String = File(context.cacheDir, "sync/incoming").absolutePath

    override val backupDir: String = File(context.filesDir, "backups").absolutePath

    override fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    override fun exists(path: String) = File(path).exists()

    override fun copy(from: String, to: String) {
        val target = File(to)
        target.parentFile?.mkdirs()
        File(from).copyTo(target, overwrite = true)
    }

    override fun move(from: String, to: String) {
        val source = File(from)
        val target = File(to)
        target.parentFile?.mkdirs()
        // rename(2) reemplaza el destino de forma atómica dentro de la misma partición.
        if (!source.renameTo(target)) {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    override fun list(dir: String): List<String> = File(dir).list()?.toList().orEmpty()

    override fun readHeader(path: String, count: Int): ByteArray =
        File(path).inputStream().use { input ->
            val buffer = ByteArray(count)
            val read = input.read(buffer)
            if (read <= 0) ByteArray(0) else buffer.copyOf(read)
        }
}
