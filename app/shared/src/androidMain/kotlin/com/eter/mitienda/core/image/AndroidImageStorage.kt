package com.eter.mitienda.core.image

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Directorios:
 * - Persistente: `filesDir/product_images` (/data/data/<paquete>/files). Es
 *   privado de la app, no necesita permisos, el sistema NUNCA lo limpia y se
 *   borra solo al desinstalar.
 * - Temporal: `cacheDir/image_temp`. El sistema puede vaciarlo si falta espacio,
 *   por eso ahí solo vive la foto mientras el usuario no pulsa "Guardar".
 */
class AndroidImageStorage(private val context: Context) : ImageStorage {

    private val persistentDir get() = File(context.filesDir, ImageSpec.DIRECTORY)

    override suspend fun saveImageLocally(tempPath: String): String = withContext(Dispatchers.IO) {
        val temp = File(tempPath)
        check(temp.isFile) { "La imagen temporal ya no existe." }
        persistentDir.mkdirs()
        val fileName = ImageSpec.newFileName()
        // Se COPIA (no se mueve): si el INSERT posterior falla, el temporal sigue
        // ahí para reintentar y la copia se borra sin perder la foto elegida.
        temp.copyTo(File(persistentDir, fileName), overwrite = false)
        "${ImageSpec.DIRECTORY}/$fileName"
    }

    override suspend fun deleteLocalImage(path: String) {
        withContext(Dispatchers.IO) {
            val file = File(resolve(path))
            // Defensa: solo se borran archivos de nuestra carpeta de fotos.
            if (file.canonicalPath.startsWith(persistentDir.canonicalPath)) {
                runCatching { file.delete() }
            }
        }
    }

    override fun discardTemp(tempPath: String) {
        runCatching { File(tempPath).delete() }
    }

    override fun resolve(path: String): String =
        if (path.startsWith("/")) path else File(context.filesDir, path).absolutePath

    internal companion object {
        /** Foto comprimida a la espera de "Guardar". */
        fun newTempFile(context: Context) = File(File(context.cacheDir, "image_temp"), ImageSpec.newFileName())

        /** Foto cruda de la cámara (compartida vía FileProvider, ver res/xml/file_paths.xml). */
        fun newCameraFile(context: Context) = File(File(context.cacheDir, "camera"), ImageSpec.newFileName())
    }
}
