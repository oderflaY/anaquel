package com.eter.mitienda.core.scanner

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_camera
import org.jetbrains.compose.resources.painterResource
import kotlin.math.min

/**
 * Escáner a pantalla completa: cámara en vivo + recuadro de enfoque + panel
 * neumórfico inferior con instrucciones y acciones.
 *
 * Es un overlay controlado: el padre lo muestra con `if (visible)` y lo quita
 * en [onDismiss]. Al salir de la composición la cámara se apaga (ver
 * [BarcodeCameraView]), así que nunca queda encendida en segundo plano.
 *
 * Debe colocarse como ÚLTIMO hijo del `Box` raíz de la pantalla para quedar
 * por encima de todo.
 *
 * @param onBarcodeScanned recibe el código leído. Justo después se llama a
 * [onDismiss], así que el modal se cierra solo tras una lectura correcta
 * (salvo en modo [continuous]).
 * @param onDismiss se llama siempre al cerrar: tras leer, al cancelar o con "atrás".
 * @param continuous modo caja: la cámara sigue abierta y cada código se entrega
 * por separado; se cierra con "Terminar".
 * @param statusText en modo continuo, lo que el panel muestra tras cada lectura
 * (p. ej. el último producto agregado y el total).
 */
@Composable
fun NeumorphicScannerModal(
    onBarcodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    continuous: Boolean = false,
    statusText: String? = null,
) {
    val permission = rememberCameraPermissionState()
    val scanFeedback = rememberScanSuccessFeedback()
    var cameraError by remember { mutableStateOf<String?>(null) }
    // Cambiar la clave recrea BarcodeCameraView: así funciona "Reintentar".
    var cameraAttempt by remember { mutableIntStateOf(0) }

    // Primera apertura: pedir el permiso directamente, sin pantallas intermedias.
    LaunchedEffect(Unit) {
        if (permission.status == CameraPermissionStatus.NotDetermined) permission.request()
    }

    // "Atrás" cierra el escáner en lugar de salir de la pantalla de abajo.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = onDismiss,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Bloquea toques y scroll hacia la pantalla que queda debajo.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        val cameraActive = permission.status == CameraPermissionStatus.Granted && cameraError == null
        if (cameraActive) {
            key(cameraAttempt) {
                BarcodeCameraView(
                    onBarcodeDetected = { code ->
                        scanFeedback()
                        onBarcodeScanned(code)
                        if (!continuous) onDismiss()
                    },
                    onError = { cameraError = it },
                    modifier = Modifier.fillMaxSize(),
                    continuous = continuous,
                )
            }
            ScannerOverlay(Modifier.fillMaxSize())
        }

        ScannerPanel(
            permissionStatus = permission.status,
            cameraError = cameraError,
            onRequestPermission = permission::request,
            onOpenSettings = permission::openSettings,
            onRetryCamera = {
                cameraError = null
                cameraAttempt++
            },
            onCancel = onDismiss,
            continuous = continuous,
            statusText = statusText,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Fracción vertical donde se centra el recuadro (por encima del panel inferior). */
private const val FRAME_CENTER_Y = 0.38f

/**
 * Oscurece todo excepto un recuadro central "recortado". El recorte se hace con
 * BlendMode.Clear sobre una capa offscreen: la capa se pinta de negro al 60 %
 * y luego se borra el rectángulo, dejando ver la cámara solo ahí.
 */
@Composable
private fun ScannerOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scanLine")
    val lineProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanLineProgress",
    )

    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        // Recuadro apaisado: los códigos 1D son anchos y bajos.
        val frameWidth = min(size.width * 0.82f, 340.dp.toPx())
        val frameHeight = frameWidth * 0.55f
        val topLeft = Offset(
            x = (size.width - frameWidth) / 2f,
            y = size.height * FRAME_CENTER_Y - frameHeight / 2f,
        )
        val frameSize = Size(frameWidth, frameHeight)
        val radius = CornerRadius(NeuDimens.RadiusLarge.toPx())

        drawRect(Color.Black.copy(alpha = 0.6f))
        drawRoundRect(Color.Transparent, topLeft, frameSize, radius, blendMode = BlendMode.Clear)
        drawRoundRect(
            color = NeuColors.MagentaOnDark,
            topLeft = topLeft,
            size = frameSize,
            cornerRadius = radius,
            style = Stroke(width = 3.dp.toPx()),
        )

        // Línea de escaneo animada: indica que la cámara está "buscando".
        val inset = 16.dp.toPx()
        val y = topLeft.y + inset + (frameHeight - inset * 2) * lineProgress
        drawLine(
            color = NeuColors.MagentaOnDark.copy(alpha = 0.85f),
            start = Offset(topLeft.x + inset, y),
            end = Offset(topLeft.x + frameWidth - inset, y),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/** Panel inferior claro (estilo bottom sheet) cuyo contenido depende del estado. */
@Composable
private fun ScannerPanel(
    permissionStatus: CameraPermissionStatus,
    cameraError: String?,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryCamera: () -> Unit,
    onCancel: () -> Unit,
    continuous: Boolean,
    statusText: String?,
    modifier: Modifier = Modifier,
) {
    val manualHint = "También puedes escribir el código con el teclado."
    val (title, message, primaryAction) = when {
        cameraError != null -> Triple(
            "La cámara no respondió",
            "$cameraError\n$manualHint",
            "Reintentar" to onRetryCamera,
        )
        permissionStatus == CameraPermissionStatus.Granted && continuous -> Triple(
            "Escaneando productos",
            statusText ?: "Pasa los productos uno por uno frente al recuadro.",
            null,
        )
        permissionStatus == CameraPermissionStatus.Granted -> Triple(
            "Escanear código",
            "Centra el código de barras dentro del recuadro.",
            null,
        )
        permissionStatus == CameraPermissionStatus.NotDetermined -> Triple(
            "Permiso de cámara",
            "Autoriza la cámara para leer códigos de barras.",
            null,
        )
        permissionStatus == CameraPermissionStatus.Denied -> Triple(
            "Se necesita la cámara",
            "Sin acceso a la cámara no se pueden escanear códigos.\n$manualHint",
            "Permitir cámara" to onRequestPermission,
        )
        else -> Triple(
            "Cámara bloqueada",
            "Activa el permiso de Cámara en los ajustes de la app.\n$manualHint",
            "Abrir ajustes" to onOpenSettings,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                NeuColors.Background,
                RoundedCornerShape(topStart = NeuDimens.SpaceXl, topEnd = NeuDimens.SpaceXl),
            )
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(NeuDimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (permissionStatus != CameraPermissionStatus.Granted || cameraError != null) {
            Icon(
                painter = painterResource(Res.drawable.ic_camera),
                contentDescription = null,
                tint = NeuColors.TextSecondary,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.size(NeuDimens.SpaceMd))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NeuColors.TextPrimary,
        )
        Spacer(Modifier.size(NeuDimens.SpaceXs))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = NeuColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(NeuDimens.SpaceXl))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg),
        ) {
            NeumorphicButton(
                text = if (continuous) "Terminar" else "Cancelar",
                onClick = onCancel,
                containerColor = NeuColors.Background,
                modifier = Modifier.weight(1f),
            )
            if (primaryAction != null) {
                NeumorphicButton(
                    text = primaryAction.first,
                    onClick = primaryAction.second,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
