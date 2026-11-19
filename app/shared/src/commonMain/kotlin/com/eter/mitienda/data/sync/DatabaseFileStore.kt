package com.eter.mitienda.data.sync

/**
 * Operaciones de archivo que necesita la sincronización, implementadas por
 * cada plataforma (java.io en Android, NSFileManager en iOS). Todas son
 * bloqueantes: se llaman desde la cola de la base de datos.
 */
interface DatabaseFileStore {
    /** Ruta absoluta de `papeleria_inventory.db`. */
    val databasePath: String

    /** Copia temporal que se envía al otro teléfono. */
    val exportPath: String

    /** Carpeta de recepción de archivos entrantes. */
    val incomingDir: String

    /** Carpeta persistente de respaldos. */
    val backupDir: String

    /** Marca de tiempo local legible para nombres de archivo: `20261020_214530`. */
    fun timestamp(): String

    fun exists(path: String): Boolean

    /** Copia sobrescribiendo el destino. */
    fun copy(from: String, to: String)

    /** Mueve/renombra sobrescribiendo el destino (atómico en la misma carpeta). */
    fun move(from: String, to: String)

    fun delete(path: String)

    /** Nombres de archivo (no rutas) dentro de [dir]. */
    fun list(dir: String): List<String>

    /** Primeros [count] bytes del archivo (o menos si es más corto). */
    fun readHeader(path: String, count: Int): ByteArray
}
