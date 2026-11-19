package com.eter.mitienda.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

sealed interface ImagePickResult {
    /** Imagen ya redimensionada (≤ 800 px) y comprimida (JPEG 80) en un archivo TEMPORAL. */
    data class Success(val tempPath: String) : ImagePickResult

    /** El usuario cerró la cámara o la galería sin elegir: no se hace nada. */
    data object Cancelled : ImagePickResult

    data class Error(val message: String) : ImagePickResult
}

/**
 * Abre la cámara o la galería del sistema. El resultado llega por el callback
 * de [rememberImagePickerLauncher] porque ambas son pantallas externas a la app.
 */
@Stable
interface ImagePickerLauncher {
    /** `true` mientras se redimensiona/comprime la imagen elegida. */
    val isProcessing: Boolean

    /** Cámara del sistema → comprime → [ImagePickResult.Success] con la ruta temporal. */
    fun takePhoto()

    /** Galería del sistema → comprime → [ImagePickResult.Success] con la ruta temporal. */
    fun pickFromGallery()
}

@Composable
expect fun rememberImagePickerLauncher(onResult: (ImagePickResult) -> Unit): ImagePickerLauncher
