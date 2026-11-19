package com.eter.mitienda.core.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

@Stable
interface NotificationPermissionState {
    val granted: Boolean

    /** Pide el permiso; si ya se negó de forma definitiva, abre los ajustes de notificaciones. */
    fun request()
}

@Composable
expect fun rememberNotificationPermissionState(): NotificationPermissionState
