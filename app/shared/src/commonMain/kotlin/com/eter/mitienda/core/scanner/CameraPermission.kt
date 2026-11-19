package com.eter.mitienda.core.scanner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/** Estados del permiso de cámara que la UI distingue. */
enum class CameraPermissionStatus {
    /** Aún no se le ha preguntado al usuario: se puede mostrar el diálogo del sistema. */
    NotDetermined,
    Granted,
    /** Lo rechazó una vez; todavía se le puede volver a preguntar (solo Android). */
    Denied,
    /** Rechazo definitivo: el sistema ya no muestra el diálogo, hay que ir a Ajustes. */
    PermanentlyDenied,
}

@Stable
interface CameraPermissionState {
    /** Observable: la UI se recompone sola al cambiar (también al volver de Ajustes). */
    val status: CameraPermissionStatus

    /** Muestra el diálogo del sistema. Sin efecto si ya no se puede preguntar. */
    fun request()

    /** Abre la pantalla de permisos de la app en los Ajustes del sistema. */
    fun openSettings()
}

/** Implementación por plataforma (ActivityResult en Android, AVCaptureDevice en iOS). */
@Composable
expect fun rememberCameraPermissionState(): CameraPermissionState
