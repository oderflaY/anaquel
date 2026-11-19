package com.eter.mitienda.data.sync

import com.eter.mitienda.data.local.PapeleriaDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.posix.memcpy

/**
 * - BD: `Documents/papeleria_inventory.db` (donde la creó la Fase 2).
 * - Respaldos: `Library/Application Support/backups`.
 * - Envío y recepción: `tmp/sync`.
 */
@OptIn(ExperimentalForeignApi::class)
class IosDatabaseFileStore : DatabaseFileStore {

    private val fileManager get() = NSFileManager.defaultManager

    private fun dir(directory: ULong): String =
        NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).first() as String

    override val databasePath: String = "${dir(NSDocumentDirectory)}/${PapeleriaDatabase.NAME}"
    override val exportPath: String = NSTemporaryDirectory().trimEnd('/') + "/sync/export_papeleria.db"
    override val incomingDir: String = NSTemporaryDirectory().trimEnd('/') + "/sync/incoming"
    override val backupDir: String = "${dir(NSApplicationSupportDirectory)}/backups"

    override fun timestamp(): String = NSDateFormatter().apply {
        dateFormat = "yyyyMMdd_HHmmss"
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
    }.stringFromDate(NSDate())

    override fun exists(path: String) = fileManager.fileExistsAtPath(path)

    override fun copy(from: String, to: String) {
        ensureParent(to)
        fileManager.removeItemAtPath(to, error = null)
        check(fileManager.copyItemAtPath(from, toPath = to, error = null)) { "No se pudo copiar $from" }
    }

    override fun move(from: String, to: String) {
        ensureParent(to)
        fileManager.removeItemAtPath(to, error = null)
        check(fileManager.moveItemAtPath(from, toPath = to, error = null)) { "No se pudo mover $from" }
    }

    override fun delete(path: String) {
        fileManager.removeItemAtPath(path, error = null)
    }

    override fun list(dir: String): List<String> =
        fileManager.contentsOfDirectoryAtPath(dir, error = null)?.map { it as String }.orEmpty()

    override fun readHeader(path: String, count: Int): ByteArray {
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return ByteArray(0)
        try {
            val data = handle.readDataOfLength(count.toULong())
            val length = data.length.toInt()
            if (length == 0) return ByteArray(0)
            return ByteArray(length).apply {
                usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
            }
        } finally {
            handle.closeFile()
        }
    }

    private fun ensureParent(path: String) {
        fileManager.createDirectoryAtPath(
            path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }
}
