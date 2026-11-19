package com.eter.mitienda.core.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeCode128Code
import platform.AVFoundation.AVMetadataObjectTypeEAN13Code
import platform.AVFoundation.AVMetadataObjectTypeEAN8Code
import platform.AVFoundation.AVMetadataObjectTypeUPCECode
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.QuartzCore.CATransaction
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

/**
 * AVFoundation nativo: AVCaptureMetadataOutput decodifica en hardware, sin
 * librerías externas. Nota: iOS no tiene tipo UPC-A propio; lo reporta como
 * EAN-13 con un 0 inicial, que aquí se normaliza para obtener el mismo valor
 * de 12 dígitos que devuelve ML Kit en Android.
 */
@Composable
actual fun BarcodeCameraView(
    onBarcodeDetected: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
    continuous: Boolean,
) {
    val currentOnBarcode by rememberUpdatedState(onBarcodeDetected)
    val currentOnError by rememberUpdatedState(onError)
    val controller = remember(continuous) {
        IosBarcodeScanner(
            continuous = continuous,
            onCode = { currentOnBarcode(it) },
            onError = { currentOnError(it) },
        )
    }

    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }

    UIKitView(factory = { controller.previewView }, modifier = modifier)
}

@OptIn(ExperimentalForeignApi::class)
private class CameraPreviewView(
    private val previewLayer: AVCaptureVideoPreviewLayer,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {

    init {
        layer.addSublayer(previewLayer)
    }

    /** La capa de video no sigue el tamaño de la vista por sí sola. */
    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.frame = bounds
        CATransaction.commit()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosBarcodeScanner(
    continuous: Boolean,
    private val onCode: (String) -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    private val session = AVCaptureSession()
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }
    val previewView = CameraPreviewView(previewLayer)

    /** startRunning/stopRunning bloquean: Apple pide hacerlo fuera del hilo principal. */
    private val sessionQueue = dispatch_queue_create("com.eter.mitienda.camera", null)
    private val confirmation = ScanConfirmation(continuous)
    private var configured = false

    fun start() {
        dispatch_async(sessionQueue) {
            if (!configured) {
                val error = configure()
                if (error != null) {
                    dispatch_async(dispatch_get_main_queue()) { onError(error) }
                    return@dispatch_async
                }
                configured = true
            }
            session.startRunning()
        }
    }

    fun stop() {
        dispatch_async(sessionQueue) {
            if (session.running) session.stopRunning()
        }
    }

    /** @return mensaje de error, o `null` si la sesión quedó lista. */
    private fun configure(): String? {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            ?: return "Este dispositivo no tiene cámara."
        val input = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            AVCaptureDeviceInput.deviceInputWithDevice(device, error.ptr)
        } ?: return "No se pudo acceder a la cámara."

        session.beginConfiguration()
        try {
            if (session.canSetSessionPreset(AVCaptureSessionPreset1280x720)) {
                session.sessionPreset = AVCaptureSessionPreset1280x720
            }
            if (!session.canAddInput(input)) return "La cámara está siendo usada por otra app."
            session.addInput(input)

            val output = AVCaptureMetadataOutput()
            if (!session.canAddOutput(output)) return "No se pudo iniciar el lector de códigos."
            session.addOutput(output)
            output.setMetadataObjectsDelegate(this, dispatch_get_main_queue())
            // Los tipos se fijan DESPUÉS de añadir la salida (si no, iOS lanza excepción).
            output.metadataObjectTypes = listOf(
                AVMetadataObjectTypeEAN13Code, // también cubre UPC-A
                AVMetadataObjectTypeEAN8Code,
                AVMetadataObjectTypeUPCECode,
                AVMetadataObjectTypeCode128Code,
            )
        } finally {
            session.commitConfiguration()
        }
        return null
    }

    /** Llamado en el hilo principal (cola indicada en setMetadataObjectsDelegate). */
    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        val code = didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { obj -> obj.stringValue?.let { normalize(obj.type, it) } }
            ?: return
        confirmation.onRead(code)?.let(onCode)
    }

    private fun normalize(type: String?, value: String): String =
        if (type == AVMetadataObjectTypeEAN13Code && value.length == 13 && value.startsWith('0')) {
            value.drop(1) // UPC-A
        } else {
            value
        }
}

@Composable
actual fun rememberScanSuccessFeedback(): () -> Unit = remember {
    val generator = UINotificationFeedbackGenerator()
    val feedback: () -> Unit = {
        generator.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
    }
    feedback
}
