package com.eter.mitienda.core.image

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

internal actual fun decodeLocalImage(path: String, maxSizePx: Int): ImageBitmap? {
    return try {
        if (!File(path).isFile) return null

        // 1ª pasada: solo dimensiones, sin reservar memoria para los píxeles.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 2ª pasada: submuestreo en potencias de 2 hasta acercarse a maxSizePx.
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxSizePx &&
            bounds.outHeight / (sampleSize * 2) >= maxSizePx
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeFile(path, options)?.asImageBitmap()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
