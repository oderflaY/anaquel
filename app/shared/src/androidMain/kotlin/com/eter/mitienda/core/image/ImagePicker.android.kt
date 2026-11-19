package com.eter.mitienda.core.image

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * - Galería: Photo Picker del sistema (`PickVisualMedia`). No pide permisos: el
 *   usuario elige una foto y la app solo recibe acceso a esa.
 * - Cámara: app de cámara del sistema (`TakePicture`), que escribe la foto en un
 *   archivo nuestro compartido por FileProvider. Como la app declara CAMERA
 *   (para el escáner), Android exige tenerlo concedido también para este intent.
 */
@Composable
actual fun rememberImagePickerLauncher(onResult: (ImagePickResult) -> Unit): ImagePickerLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = remember { AndroidImagePickerLauncher() }

    // Sobrevive a que Android mate el proceso mientras la cámara está abierta.
    var cameraFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            currentOnResult(ImagePickResult.Cancelled)
        } else {
            launcher.process(scope, context, uri, rawFileToDelete = null) { currentOnResult(it) }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val raw = cameraFilePath?.let(::File)
        cameraFilePath = null
        if (saved && raw != null && raw.length() > 0) {
            launcher.process(scope, context, Uri.fromFile(raw), rawFileToDelete = raw) { currentOnResult(it) }
        } else {
            raw?.delete()
            currentOnResult(ImagePickResult.Cancelled)
        }
    }

    fun launchCamera() {
        val raw = AndroidImageStorage.newCameraFile(context).apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", raw)
        cameraFilePath = raw.absolutePath
        try {
            cameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            cameraFilePath = null
            currentOnResult(ImagePickResult.Error("No hay una app de cámara disponible."))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCamera()
        } else {
            currentOnResult(
                ImagePickResult.Error("Sin permiso de cámara. Puedes activarlo en Ajustes o elegir de la galería."),
            )
        }
    }

    launcher.takePhotoAction = {
        if (context.hasCameraPermission()) launchCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    launcher.pickFromGalleryAction = {
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    return launcher
}

private class AndroidImagePickerLauncher : ImagePickerLauncher {
    override var isProcessing by mutableStateOf(false)
        private set

    var takePhotoAction: () -> Unit = {}
    var pickFromGalleryAction: () -> Unit = {}

    override fun takePhoto() {
        if (!isProcessing) takePhotoAction()
    }

    override fun pickFromGallery() {
        if (!isProcessing) pickFromGalleryAction()
    }

    /** Comprime fuera del hilo principal y entrega el temporal resultante. */
    fun process(
        scope: CoroutineScope,
        context: Context,
        source: Uri,
        rawFileToDelete: File?,
        deliver: (ImagePickResult) -> Unit,
    ) {
        isProcessing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val temp = AndroidImageStorage.newTempFile(context)
                try {
                    ImageCompressor.compress(context, source, temp)
                    ImagePickResult.Success(temp.absolutePath)
                } catch (e: CancellationException) {
                    temp.delete()
                    throw e
                } catch (e: Exception) {
                    temp.delete()
                    ImagePickResult.Error("No se pudo procesar la imagen.")
                } catch (e: OutOfMemoryError) {
                    temp.delete()
                    ImagePickResult.Error("La imagen es demasiado grande.")
                } finally {
                    rawFileToDelete?.delete() // la foto cruda (varios MB) ya no hace falta
                }
            }
            isProcessing = false
            deliver(result)
        }
    }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
