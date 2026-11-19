package com.eter.mitienda.data.sync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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

/**
 * Permisos en tiempo de ejecución que Nearby Connections necesita en cada versión.
 * La ubicación va en todas: sin ella la búsqueda (emisor) falla aunque el
 * receptor sí pueda hacerse visible.
 */
internal fun requiredSyncPermissions(): Array<String> {
    val location = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> location + arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> location + arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        else -> location
    }
}

private class AndroidSyncPermissionsState(
    private val context: Context,
    private val activity: Activity?,
) : SyncPermissionsState {

    override var allGranted by mutableStateOf(checkAll())
        private set
    override var permanentlyDenied by mutableStateOf(false)
        private set

    var launch: () -> Unit = {}
    private var pendingAction: (() -> Unit)? = null

    override fun runWithPermissions(action: () -> Unit) {
        if (checkAll()) {
            allGranted = true
            action()
        } else {
            pendingAction = action
            launch()
        }
    }

    fun onResult() {
        allGranted = checkAll()
        val action = pendingAction
        pendingAction = null
        if (allGranted) {
            permanentlyDenied = false
            action?.invoke()
        } else {
            // Sin justificación tras rechazar = el sistema ya no mostrará el diálogo.
            permanentlyDenied = requiredSyncPermissions().any { permission ->
                !isGranted(permission) && activity?.shouldShowRequestPermissionRationale(permission) == false
            }
        }
    }

    fun refresh() {
        allGranted = checkAll()
        if (allGranted) permanentlyDenied = false
    }

    override fun openSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun checkAll() = requiredSyncPermissions().all(::isGranted)

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
actual fun rememberSyncPermissionsState(): SyncPermissionsState {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state = remember(context, activity) { AndroidSyncPermissionsState(context, activity) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        state.onResult()
    }
    state.launch = { launcher.launch(requiredSyncPermissions()) }
    LifecycleResumeEffect(state) {
        state.refresh() // al volver de Ajustes
        onPauseOrDispose { }
    }
    return state
}
