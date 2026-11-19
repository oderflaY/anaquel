package com.eter.mitienda.core.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal actual fun decodeLocalImage(path: String, maxSizePx: Int): ImageBitmap? {
    return try {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        val length = data.length.toInt()
        if (length == 0) return null

        val bytes = ByteArray(length)
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        // Skia decodifica a tamaño completo; el submuestreo se hará al guardar la
        // foto (Fase 3), que la redimensionará antes de escribirla en disco.
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}
