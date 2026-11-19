package com.eter.mitienda.core.scanner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

private class AndroidCameraPermissionState(
    private val context: Context,
    private val activity: Activity?,
) : CameraPermissionState {

    override var status by mutableStateOf(currentStatus(asked = false))

    /** Lo asigna el composable: el launcher debe registrarse en composición. */
    var launch: () -> Unit = {}

    /** `true` tras haber mostrado el diálogo al menos una vez en esta sesión. */
    private var asked = false

    override fun request() {
        if (status != CameraPermissionStatus.PermanentlyDenied) launch()
    }

    override fun openSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun onResult() {
        asked = true
        refresh()
    }

    fun refresh() {
        status = currentStatus(asked)
    }

    /**
     * Android no expone "denegado para siempre" directamente: se deduce. Tras un
     * rechazo, si el sistema ya no quiere mostrar la justificación
     * (`shouldShowRequestPermissionRationale == false`), el diálogo no volverá
     * a aparecer y la única vía es Ajustes.
     */
    private fun currentStatus(asked: Boolean): CameraPermissionStatus {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return CameraPermissionStatus.Granted
        val canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true
        return when {
            canAskAgain -> CameraPermissionStatus.Denied
            asked -> CameraPermissionStatus.PermanentlyDenied
            else -> CameraPermissionStatus.NotDetermined
        }
    }
}

@Composable
actual fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember(context, activity) { AndroidCameraPermissionState(context, activity) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state.onResult()
    }
    state.launch = { launcher.launch(Manifest.permission.CAMERA) }

    // Al volver de Ajustes el usuario pudo haber concedido el permiso.
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}
