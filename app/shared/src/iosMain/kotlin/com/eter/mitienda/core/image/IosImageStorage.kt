package com.eter.mitienda.core.image

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/**
 * Directorios:
 * - Persistente: `Library/Application Support/product_images`. Es el sitio que
 *   Apple indica para datos que la app crea y necesita: iOS no lo purga, entra
 *   en el respaldo de iCloud y no se muestra en la app Archivos (a diferencia
 *   de Documents, pensado para documentos del usuario).
 * - Temporal: `tmp/` (NSTemporaryDirectory), que iOS puede vaciar en cualquier
 *   momento cuando la app no está en uso.
 */
@OptIn(ExperimentalForeignApi::class)
class IosImageStorage : ImageStorage {

    private val fileManager get() = NSFileManager.defaultManager

    private val baseDir: String by lazy {
        NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            .first() as String
    }

    override suspend fun saveImageLocally(tempPath: String): String = withContext(Dispatchers.IO) {
        check(fileManager.fileExistsAtPath(tempPath)) { "La imagen temporal ya no existe." }
        val dir = "$baseDir/${ImageSpec.DIRECTORY}"
        fileManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val relative = "${ImageSpec.DIRECTORY}/${ImageSpec.newFileName()}"
        // Se COPIA (no se mueve): si el INSERT falla, el temporal sigue disponible.
        check(fileManager.copyItemAtPath(tempPath, toPath = "$baseDir/$relative", error = null)) {
            "No se pudo guardar la imagen."
        }
        relative
    }

    override suspend fun deleteLocalImage(path: String) {
        withContext(Dispatchers.IO) {
            val absolute = resolve(path)
            if (absolute.startsWith("$baseDir/${ImageSpec.DIRECTORY}/")) {
                fileManager.removeItemAtPath(absolute, error = null)
            }
        }
    }

    override fun discardTemp(tempPath: String) {
        fileManager.removeItemAtPath(tempPath, error = null)
    }

    override fun resolve(path: String): String =
        if (path.startsWith("/")) path else "$baseDir/$path"

    internal companion object {
        fun newTempPath(): String =
            NSTemporaryDirectory().trimEnd('/') + "/image_temp_${ImageSpec.newFileName()}"
    }
}
