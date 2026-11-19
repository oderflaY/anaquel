package com.eter.mitienda.core.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** En iOS el usuario solo ve el diálogo una vez: cualquier rechazo es definitivo. */
private class IosCameraPermissionState : CameraPermissionState {

    override var status by mutableStateOf(currentStatus())

    override fun request() {
        if (status != CameraPermissionStatus.NotDetermined) return
        AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
            // El callback llega en un hilo arbitrario; el estado de Compose se toca en el principal.
            dispatch_async(dispatch_get_main_queue()) { refresh() }
        }
    }

    override fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    fun refresh() {
        status = currentStatus()
    }

    private fun currentStatus(): CameraPermissionStatus =
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> CameraPermissionStatus.Granted
            AVAuthorizationStatusNotDetermined -> CameraPermissionStatus.NotDetermined
            else -> CameraPermissionStatus.PermanentlyDenied // Denied o Restricted (control parental)
        }
}

@Composable
actual fun rememberCameraPermissionState(): CameraPermissionState {
    val state = remember { IosCameraPermissionState() }
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}
