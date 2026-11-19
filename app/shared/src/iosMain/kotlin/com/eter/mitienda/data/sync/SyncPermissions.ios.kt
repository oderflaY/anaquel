package com.eter.mitienda.data.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** En iOS la transferencia P2P no está disponible: no hay permisos que pedir. */
@Composable
actual fun rememberSyncPermissionsState(): SyncPermissionsState = remember {
    object : SyncPermissionsState {
        override val allGranted = true
        override val permanentlyDenied = false
        override fun runWithPermissions(action: () -> Unit) = action()
        override fun openSettings() = Unit
    }
}
