package com.eter.mitienda.data.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * Permisos de radio que exige la sincronización P2P (cambian según la versión
 * de Android). Se piden justo antes de buscar o de hacerse visible.
 */
@Stable
interface SyncPermissionsState {
    val allGranted: Boolean

    /** Algún permiso se rechazó "para siempre": solo se puede conceder desde Ajustes. */
    val permanentlyDenied: Boolean

    /** Ejecuta [action] si ya hay permisos; si no, los pide y la ejecuta al concederlos. */
    fun runWithPermissions(action: () -> Unit)

    fun openSettings()
}

@Composable
expect fun rememberSyncPermissionsState(): SyncPermissionsState
