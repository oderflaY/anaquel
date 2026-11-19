package com.eter.mitienda.core.scanner

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Vista previa de la cámara trasera que decodifica códigos de barras.
 *
 * Formatos: EAN-13, EAN-8, UPC-A, UPC-E y Code 128 (los de papelería). No se
 * habilitan más formatos a propósito: cada formato extra hace el análisis más
 * lento y sube la probabilidad de lecturas falsas.
 *
 * Ciclo de vida: la cámara se enciende al entrar en la composición y se APAGA
 * al salir de ella. Para apagarla basta con dejar de mostrar este composable.
 *
 * @param onBarcodeDetected se invoca en el hilo principal con cada código
 * confirmado (leído igual en dos cuadros seguidos). Una sola vez, salvo en
 * modo [continuous].
 * @param onError la cámara no pudo iniciarse (ocupada, sin cámara, fallo del HAL).
 * @param continuous modo caja: sigue leyendo tras cada código (ver [ScanConfirmation]).
 */
@Composable
expect fun BarcodeCameraView(
    onBarcodeDetected: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    continuous: Boolean = false,
)

/**
 * Feedback háptico de "lectura correcta". Se usa una vibración real (y no
 * `LocalHapticFeedback`) porque esta respeta el ajuste de "vibrar al tocar", que
 * muchos usuarios desactivan, y la confirmación de escaneo debe sentirse siempre.
 */
@Composable
expect fun rememberScanSuccessFeedback(): () -> Unit

/**
 * Lectura confirmada solo si el mismo valor aparece en dos análisis seguidos.
 *
 * - Modo normal: entrega un único código y después ignora todo.
 * - Modo continuo (caja): entrega cada código nuevo. El MISMO código no se
 *   vuelve a entregar mientras siga frente a la cámara: tiene que desaparecer
 *   del cuadro al menos [rearmGap] para contar otra pieza. Así un producto que
 *   se queda quieto no suma piezas solo; dos piezas iguales se pasan una tras otra.
 */
internal class ScanConfirmation(
    private val continuous: Boolean = false,
    private val requiredReads: Int = 2,
    private val rearmGap: Duration = 700.milliseconds,
) {
    private var candidate: String? = null
    private var count = 0
    private var delivered: String? = null
    private var lastSeenDelivered: TimeMark? = null

    /** Devuelve el código cuando queda confirmado; si no, `null`. */
    fun onRead(value: String): String? {
        if (!continuous && delivered != null) return null

        if (continuous && value == delivered) {
            val gone = lastSeenDelivered?.elapsedNow()?.let { it >= rearmGap } ?: true
            lastSeenDelivered = TimeSource.Monotonic.markNow()
            if (!gone) return null
        }

        if (value == candidate) count++ else { candidate = value; count = 1 }
        if (count < requiredReads) return null

        count = 0
        candidate = null
        delivered = value
        lastSeenDelivered = TimeSource.Monotonic.markNow()
        return value
    }
}
