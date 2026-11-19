package com.eter.mitienda.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Redimensiona a ≤ [ImageSpec.MAX_SIZE_PX] y comprime a JPEG [ImageSpec.JPEG_QUALITY].
 *
 * Memoria: una foto de 50 MP decodificada completa ocuparía ~200 MB. Primero se
 * leen solo las dimensiones y se decodifica con `inSampleSize` (submuestreo en
 * potencias de 2 que hace el propio decodificador), y luego se escala al tamaño
 * exacto. Así el pico de memoria queda en pocos MB.
 */
internal object ImageCompressor {

    /** Bloqueante: llamar desde Dispatchers.IO. */
    fun compress(context: Context, source: Uri, destination: File) {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "El archivo no es una imagen válida." }

        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= ImageSpec.MAX_SIZE_PX) {
            sampleSize *= 2
        }
        val sampled = resolver.openInputStream(source).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: error("No se pudo leer la imagen.")

        // Las cámaras guardan la foto "de lado" y anotan el giro en EXIF. Al
        // recomprimir se pierde el EXIF, así que el giro se aplica a los píxeles.
        val exif = resolver.openInputStream(source)?.use { ExifInterface(it) }
        val matrix = Matrix().apply {
            val scale = min(1f, ImageSpec.MAX_SIZE_PX.toFloat() / max(sampled.width, sampled.height))
            postScale(scale, scale)
            if (exif != null) {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
        }
        val result = Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)

        try {
            destination.parentFile?.mkdirs()
            destination.outputStream().use { out ->
                check(result.compress(Bitmap.CompressFormat.JPEG, ImageSpec.JPEG_QUALITY, out)) {
                    "No se pudo comprimir la imagen."
                }
            }
        } finally {
            if (result !== sampled) result.recycle()
            sampled.recycle()
        }
    }
}
