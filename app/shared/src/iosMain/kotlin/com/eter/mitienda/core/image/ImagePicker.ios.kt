package com.eter.mitienda.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.writeToFile
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.max
import kotlin.math.min

/**
 * - Cámara: UIImagePickerController (usa NSCameraUsageDescription).
 * - Galería: PHPickerViewController. Corre fuera del proceso de la app y solo
 *   entrega la foto elegida, por lo que no muestra diálogo de permiso.
 */
@Composable
actual fun rememberImagePickerLauncher(onResult: (ImagePickResult) -> Unit): ImagePickerLauncher {
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    // Los delegados de UIKit son referencias débiles: hay que retenerlos aquí.
    return remember { IosImagePickerLauncher(scope) { currentOnResult(it) } }
}

@OptIn(ExperimentalForeignApi::class)
private class IosImagePickerLauncher(
    private val scope: CoroutineScope,
    private val deliver: (ImagePickResult) -> Unit,
) : ImagePickerLauncher {

    override var isProcessing by mutableStateOf(false)
        private set

    private val cameraDelegate = CameraDelegate(
        onImage = ::process,
        onCancel = { deliver(ImagePickResult.Cancelled) },
    )

    private val galleryDelegate = GalleryDelegate(
        onImage = ::process,
        onCancel = { deliver(ImagePickResult.Cancelled) },
        onError = { deliver(ImagePickResult.Error(it)) },
    )

    override fun takePhoto() {
        if (isProcessing) return
        val source = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        if (!UIImagePickerController.isSourceTypeAvailable(source)) {
            deliver(ImagePickResult.Error("Este dispositivo no tiene cámara."))
            return
        }
        val picker = UIImagePickerController().apply {
            sourceType = source
            delegate = cameraDelegate
        }
        present(picker)
    }

    override fun pickFromGallery() {
        if (isProcessing) return
        val config = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration = config).apply { delegate = galleryDelegate }
        present(picker)
    }

    private fun present(controller: UIViewController) {
        val top = topViewController()
        if (top == null) {
            deliver(ImagePickResult.Error("No se pudo abrir el selector."))
            return
        }
        top.presentViewController(controller, animated = true, completion = null)
    }

    private fun process(image: UIImage) {
        isProcessing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { compressToTemp(image) }
                    .fold(
                        onSuccess = { ImagePickResult.Success(it) },
                        onFailure = { ImagePickResult.Error("No se pudo procesar la imagen.") },
                    )
            }
            isProcessing = false
            deliver(result)
        }
    }

    /** Redimensiona a ≤ 800 px y guarda JPEG 80 en tmp/. drawInRect aplica la orientación. */
    private fun compressToTemp(image: UIImage): String {
        val (width, height) = image.size.useContents { width to height }
        val scale = min(1.0, ImageSpec.MAX_SIZE_PX / max(width, height))
        val targetWidth = width * scale
        val targetHeight = height * scale

        val format = UIGraphicsImageRendererFormat.defaultFormat().apply { this.scale = 1.0 }
        val renderer = UIGraphicsImageRenderer(size = CGSizeMake(targetWidth, targetHeight), format = format)
        val resized = renderer.imageWithActions { _ ->
            image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
        }
        val data: NSData = UIImageJPEGRepresentation(resized, ImageSpec.JPEG_QUALITY / 100.0)
            ?: error("JPEG")
        val path = IosImageStorage.newTempPath()
        check(data.writeToFile(path, atomically = true)) { "write" }
        return path
    }
}

private class CameraDelegate(
    private val onImage: (UIImage) -> Unit,
    private val onCancel: () -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        if (image != null) onImage(image) else onCancel()
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onCancel()
    }
}

private class GalleryDelegate(
    private val onImage: (UIImage) -> Unit,
    private val onCancel: () -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val provider = (didFinishPicking.firstOrNull() as? PHPickerResult)?.itemProvider
        if (provider == null) {
            onCancel()
            return
        }
        provider.loadDataRepresentationForTypeIdentifier("public.image") { data, _ ->
            // Llega en un hilo de fondo; los callbacks de la UI van al principal.
            val image = data?.let { UIImage(data = it) }
            dispatch_async(dispatch_get_main_queue()) {
                if (image != null) onImage(image) else onError("No se pudo leer la foto elegida.")
            }
        }
    }
}

/** Controlador visible más alto, para presentar encima de Compose. */
internal fun topViewController(): UIViewController? {
    @Suppress("DEPRECATION")
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
