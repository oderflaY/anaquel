package com.eter.mitienda.core.image

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Ciclo de vida de las fotos en disco. SQLite solo guarda el String que
 * devuelve [saveImageLocally]; los bytes viven en el sistema de archivos.
 *
 * Las rutas guardadas son RELATIVAS al directorio de la app
 * ("product_images/<uuid>.jpg"): en iOS la ruta absoluta del contenedor cambia
 * con cada actualización de la app, y una ruta absoluta guardada se rompería.
 */
interface ImageStorage {

    /**
     * Copia una imagen temporal (ya comprimida) al directorio persistente con un
     * nombre único (UUID). @return ruta relativa para guardar en SQLite.
     * @throws Exception si no se pudo mover (disco lleno, temporal borrado).
     */
    suspend fun saveImageLocally(tempPath: String): String

    /** Borra la foto persistente. Nunca lanza: si ya no existía, no pasa nada. */
    suspend fun deleteLocalImage(path: String)

    /** Borra un temporal que no llegó a guardarse (selección descartada). */
    fun discardTemp(tempPath: String)

    /** Convierte la ruta de SQLite (relativa) o un temporal (absoluto) en ruta absoluta. */
    fun resolve(path: String): String
}

/** Reglas de negocio de las imágenes de producto. */
object ImageSpec {
    /** Lado mayor máximo tras redimensionar. */
    const val MAX_SIZE_PX = 800

    /** Calidad JPEG (0-100). 80 ≈ 60-120 KB por foto a 800 px. */
    const val JPEG_QUALITY = 80

    /** Subcarpeta persistente dentro del directorio interno de la app. */
    const val DIRECTORY = "product_images"

    @OptIn(ExperimentalUuidApi::class)
    fun newFileName(): String = "${Uuid.random()}.jpg"
}
