package com.eter.mitienda.feature.sync

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eter.mitienda.core.designsystem.components.NeumorphicButton
import com.eter.mitienda.core.designsystem.components.NeumorphicCard
import com.eter.mitienda.core.designsystem.components.NeumorphicIconButton
import com.eter.mitienda.core.designsystem.neumorphism.animateInsetProgress
import com.eter.mitienda.core.designsystem.neumorphism.neumorphic
import com.eter.mitienda.core.designsystem.neumorphism.neumorphicInset
import com.eter.mitienda.core.designsystem.theme.NeuColors
import com.eter.mitienda.core.designsystem.theme.NeuDimens
import com.eter.mitienda.core.designsystem.theme.NeuShapes
import com.eter.mitienda.data.sync.SyncPermissionsState
import com.eter.mitienda.data.sync.rememberSyncPermissionsState
import com.eter.mitienda.domain.device.Peer
import com.eter.mitienda.domain.device.SyncState
import mitienda.shared.generated.resources.Res
import mitienda.shared.generated.resources.ic_arrow_back
import mitienda.shared.generated.resources.ic_bluetooth
import mitienda.shared.generated.resources.ic_check_circle
import mitienda.shared.generated.resources.ic_download
import mitienda.shared.generated.resources.ic_phone
import mitienda.shared.generated.resources.ic_upload
import org.jetbrains.compose.resources.painterResource

@Composable
fun SyncRoute(
    viewModel: SyncViewModel,
    onBack: () -> Unit,
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val permissions = rememberSyncPermissionsState()

    SyncScreen(
        isSupported = viewModel.isSupported,
        deviceName = viewModel.localDeviceName,
        mode = viewModel.mode,
        connection = connection,
        peers = peers,
        importStatus = viewModel.importStatus,
        permissions = permissions,
        onBack = onBack,
        // Los permisos se piden justo antes de usar las radios, no al abrir la pantalla.
        onSelectMode = { mode ->
            if (mode == SyncMode.Receiver) {
                permissions.runWithPermissions { viewModel.selectMode(mode) }
            } else {
                viewModel.selectMode(mode)
            }
        },
        onSearch = { permissions.runWithPermissions(viewModel::startDiscovery) },
        onSend = viewModel::sendTo,
        onRetry = { permissions.runWithPermissions(viewModel::retry) },
        onConfirmImport = viewModel::confirmImport,
        onCancelImport = viewModel::cancelImport,
    )
}

@Composable
fun SyncScreen(
    isSupported: Boolean,
    deviceName: String,
    mode: SyncMode?,
    connection: SyncState,
    peers: List<Peer>,
    importStatus: ImportStatus,
    permissions: SyncPermissionsState,
    onBack: () -> Unit,
    onSelectMode: (SyncMode) -> Unit,
    onSearch: () -> Unit,
    onSend: (Peer) -> Unit,
    onRetry: () -> Unit,
    onConfirmImport: (String) -> Unit,
    onCancelImport: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NeuColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NeuDimens.ScreenPadding, end = NeuDimens.ScreenPadding, top = NeuDimens.SpaceLg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeumorphicIconButton(
                icon = painterResource(Res.drawable.ic_arrow_back),
                contentDescription = "Volver",
                onClick = onBack,
            )
            Spacer(Modifier.width(NeuDimens.SpaceLg))
            Column {
                Text("Sincronizar", style = MaterialTheme.typography.headlineMedium, color = NeuColors.TextPrimary)
                Text(
                    "Este teléfono: $deviceName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeuColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = NeuDimens.ScreenPadding),
        ) {
            Spacer(Modifier.size(NeuDimens.SpaceXl))
            if (!isSupported) {
                InfoCard(
                    title = "No disponible en este dispositivo",
                    message = "La sincronización entre teléfonos funciona en Android. " +
                        "En iOS estará disponible en una versión futura.",
                )
                return@Column
            }

            ModeSelector(selected = mode, enabled = connection.isIdleLike(), onSelect = onSelectMode)
            Spacer(Modifier.size(NeuDimens.SpaceXl))

            when {
                !permissions.allGranted && permissions.permanentlyDenied -> InfoCard(
                    title = "Permisos bloqueados",
                    message = "Para encontrar teléfonos cercanos, activa \"Dispositivos cercanos\" " +
                        "y \"Ubicación\" en los ajustes de la app.",
                    actionLabel = "Abrir ajustes",
                    onAction = permissions::openSettings,
                )
                mode == SyncMode.Sender -> SenderPanel(connection, peers, onSearch, onSend, onRetry)
                mode == SyncMode.Receiver -> ReceiverPanel(deviceName, connection, importStatus, onRetry)
                else -> InfoCard(
                    title = "¿Qué quieres hacer?",
                    message = "El administrador envía su inventario completo. El empleado lo recibe y " +
                        "reemplaza el suyo (antes se guarda una copia de seguridad).\n\n" +
                        "Mantén ambos teléfonos cerca, con Bluetooth y Wi-Fi encendidos. No se necesita internet.",
                )
            }
        }
    }

    // Confirmación en el receptor al terminar de llegar el archivo.
    if (connection is SyncState.Received && importStatus == ImportStatus.None) {
        ConfirmImportDialog(
            peerName = connection.peerName,
            onConfirm = { onConfirmImport(connection.filePath) },
            onCancel = { onCancelImport(connection.filePath) },
        )
    }
}

/** Mientras hay una conexión en curso no se permite cambiar de modo. */
private fun SyncState.isIdleLike() = this is SyncState.Idle || this is SyncState.Discovering ||
    this is SyncState.Advertising || this is SyncState.Failed || this is SyncState.Sent

// --- Selector de modo ----------------------------------------------------------

@Composable
private fun ModeSelector(selected: SyncMode?, enabled: Boolean, onSelect: (SyncMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
        ModeCard(
            icon = painterResource(Res.drawable.ic_upload),
            title = "Soy Administrador",
            subtitle = "Enviar",
            selected = selected == SyncMode.Sender,
            enabled = enabled,
            onClick = { onSelect(SyncMode.Sender) },
            modifier = Modifier.weight(1f),
        )
        ModeCard(
            icon = painterResource(Res.drawable.ic_download),
            title = "Soy Empleado",
            subtitle = "Recibir",
            selected = selected == SyncMode.Receiver,
            enabled = enabled,
            onClick = { onSelect(SyncMode.Receiver) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Botón grande: extruido en reposo, hundido y en azul DIF cuando está elegido. */
@Composable
private fun ModeCard(
    icon: Painter,
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val insetProgress by animateInsetProgress(interactionSource, forceInset = selected)
    val content = if (selected) NeuColors.OnAccent else NeuColors.TextPrimary
    Column(
        modifier = modifier
            .neumorphic(
                shape = NeuShapes.Large,
                elevation = NeuDimens.ElevationLarge,
                surfaceColor = if (selected) NeuColors.Blue else NeuColors.Background,
                insetProgress = { insetProgress },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = NeuDimens.SpaceXl, horizontal = NeuDimens.SpaceMd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(32.dp))
        Spacer(Modifier.size(NeuDimens.SpaceSm))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = content,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) NeuColors.OnAccent.copy(alpha = 0.85f) else NeuColors.TextSecondary,
        )
    }
}

// --- Emisor --------------------------------------------------------------------

@Composable
private fun SenderPanel(
    connection: SyncState,
    peers: List<Peer>,
    onSearch: () -> Unit,
    onSend: (Peer) -> Unit,
    onRetry: () -> Unit,
) {
    when (connection) {
        is SyncState.Connecting, is SyncState.Transferring -> {
            TransferCard(connection)
            return
        }
        is SyncState.Sent -> {
            InfoCard(
                title = "Inventario enviado",
                message = "${connection.peerName} recibió la base de datos. Ahora debe confirmar la actualización en su teléfono.",
                icon = painterResource(Res.drawable.ic_check_circle),
                actionLabel = "Enviar a otro",
                onAction = onSearch,
            )
            return
        }
        is SyncState.Failed -> {
            ErrorCard(connection.message, onRetry)
            Spacer(Modifier.size(NeuDimens.SpaceXl))
        }
        else -> Unit
    }

    val searching = connection is SyncState.Discovering
    NeumorphicButton(
        text = if (searching) "Buscando dispositivos…" else "Buscar dispositivos",
        onClick = onSearch,
        enabled = !searching,
        icon = painterResource(Res.drawable.ic_bluetooth),
        modifier = Modifier.fillMaxWidth(),
    )

    if (searching) {
        Spacer(Modifier.size(NeuDimens.SpaceLg))
        Text(
            text = if (peers.isEmpty()) {
                "Pide al empleado que abra Sincronizar → \"Soy Empleado\"."
            } else {
                "Dispositivos encontrados"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = NeuColors.TextSecondary,
        )
    }

    LazyColumn(
        // Margen para que las sombras de las tarjetas no se recorten.
        contentPadding = PaddingValues(vertical = NeuDimens.SpaceXl),
        verticalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg),
    ) {
        items(peers, key = { it.id }) { peer ->
            PeerCard(peer, onSend = { onSend(peer) })
        }
    }
}

@Composable
private fun PeerCard(peer: Peer, onSend: () -> Unit) {
    NeumorphicCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).neumorphicInset(NeuShapes.Circle, NeuDimens.ElevationSmall),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(Res.drawable.ic_phone),
                    contentDescription = null,
                    tint = NeuColors.TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(NeuDimens.SpaceMd))
            Text(
                peer.name,
                style = MaterialTheme.typography.titleMedium,
                color = NeuColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(NeuDimens.SpaceSm))
            NeumorphicButton(text = "Enviar datos", onClick = onSend, elevation = NeuDimens.ElevationSmall)
        }
    }
}

// --- Receptor ------------------------------------------------------------------

@Composable
private fun ReceiverPanel(
    deviceName: String,
    connection: SyncState,
    importStatus: ImportStatus,
    onRetry: () -> Unit,
) {
    when (importStatus) {
        ImportStatus.Importing -> {
            InfoCard(title = "Actualizando inventario…", message = "Haciendo copia de seguridad y reemplazando los datos.")
            return
        }
        is ImportStatus.Done -> {
            InfoCard(
                title = "Inventario actualizado",
                message = "Tus datos anteriores se guardaron como\n${importStatus.backupFileName}",
                icon = painterResource(Res.drawable.ic_check_circle),
            )
            return
        }
        is ImportStatus.Failed -> {
            ErrorCard(importStatus.message, onRetry)
            return
        }
        ImportStatus.None -> Unit
    }

    when (connection) {
        is SyncState.Connecting, is SyncState.Transferring -> TransferCard(connection)
        is SyncState.Failed -> ErrorCard(connection.message, onRetry)
        is SyncState.Received -> InfoCard(title = "Archivo recibido", message = "Confirma si quieres actualizar tu inventario.")
        else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.size(NeuDimens.SpaceXl))
            RadarPulse()
            Spacer(Modifier.size(NeuDimens.SpaceXl))
            Text(
                "Esperando conexión…",
                style = MaterialTheme.typography.titleMedium,
                color = NeuColors.TextPrimary,
            )
            Text(
                "Visible como \"$deviceName\"",
                style = MaterialTheme.typography.bodyMedium,
                color = NeuColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Ícono hundido con ondas magenta que se expanden: "este teléfono está visible". */
@Composable
private fun RadarPulse() {
    val transition = rememberInfiniteTransition(label = "radar")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_000, easing = LinearEasing), RepeatMode.Restart),
        label = "radarWave",
    )
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxRadius = size.minDimension / 2
            // Dos ondas desfasadas medio ciclo para un pulso continuo.
            listOf(wave, (wave + 0.5f) % 1f).forEach { p ->
                drawCircle(
                    color = NeuColors.Magenta.copy(alpha = (1f - p) * 0.8f),
                    radius = maxRadius * (0.4f + 0.6f * p),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        Box(
            modifier = Modifier.size(84.dp).neumorphic(NeuShapes.Circle, NeuDimens.ElevationMedium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(Res.drawable.ic_bluetooth),
                contentDescription = null,
                tint = NeuColors.TextPrimary,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

// --- Estados compartidos ----------------------------------------------------------

/** Conectando / transfiriendo, con código de verificación y barra de progreso. */
@Composable
private fun TransferCard(connection: SyncState) {
    NeumorphicCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(NeuDimens.SpaceXl)) {
        when (connection) {
            is SyncState.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        color = NeuColors.Magenta,
                        trackColor = NeuColors.ShadowDark,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(NeuDimens.SpaceMd))
                    Text(
                        "Conectando con ${connection.peerName}…",
                        style = MaterialTheme.typography.titleMedium,
                        color = NeuColors.TextPrimary,
                    )
                }
                connection.authCode?.let { code ->
                    Spacer(Modifier.size(NeuDimens.SpaceLg))
                    Text(
                        "Código de verificación (debe coincidir en ambos teléfonos):",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NeuColors.TextSecondary,
                    )
                    Text(
                        code,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.TextPrimary,
                    )
                }
            }
            is SyncState.Transferring -> {
                Text(
                    if (connection.isSending) "Enviando a ${connection.peerName}" else "Recibiendo de ${connection.peerName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = NeuColors.TextPrimary,
                )
                Spacer(Modifier.size(NeuDimens.SpaceLg))
                NeumorphicProgressBar(connection.progress)
                Spacer(Modifier.size(NeuDimens.SpaceSm))
                Text(
                    "${(connection.progress * 100).toInt()} %",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeuColors.TextSecondary,
                )
            }
            else -> Unit
        }
    }
}

/** Canal hundido con relleno magenta. */
@Composable
private fun NeumorphicProgressBar(progress: Float) {
    val animated by animateFloatAsState(progress, tween(250), label = "progress")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .neumorphicInset(NeuShapes.Pill, NeuDimens.ElevationSmall)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated.coerceIn(0.02f, 1f))
                .background(NeuColors.Magenta, NeuShapes.Pill),
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    message: String,
    icon: Painter? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    NeumorphicCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(NeuDimens.SpaceXl)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = NeuColors.TextPrimary, modifier = Modifier.size(36.dp))
            Spacer(Modifier.size(NeuDimens.SpaceMd))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.TextPrimary)
        Spacer(Modifier.size(NeuDimens.SpaceXs))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = NeuColors.TextSecondary)
        if (actionLabel != null) {
            Spacer(Modifier.size(NeuDimens.SpaceXl))
            NeumorphicButton(text = actionLabel, onClick = onAction, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    NeumorphicCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(NeuDimens.SpaceXl)) {
        Text("Algo salió mal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeuColors.OnLowStock)
        Spacer(Modifier.size(NeuDimens.SpaceXs))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = NeuColors.TextSecondary)
        Spacer(Modifier.size(NeuDimens.SpaceXl))
        NeumorphicButton(text = "Reintentar", onClick = onRetry, modifier = Modifier.fillMaxWidth())
    }
}

/** Modal neumórfico: el reemplazo de datos nunca ocurre sin confirmación explícita. */
@Composable
private fun ConfirmImportDialog(peerName: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NeuDimens.SpaceLg)
                .neumorphic(NeuShapes.Large, NeuDimens.ElevationLarge)
                .padding(NeuDimens.SpaceXl),
        ) {
            Text(
                "Inventario recibido de $peerName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = NeuColors.TextPrimary,
            )
            Spacer(Modifier.size(NeuDimens.SpaceMd))
            Text(
                "¿Deseas actualizar tu inventario? Se hará una copia de seguridad de tus datos actuales.",
                style = MaterialTheme.typography.bodyMedium,
                color = NeuColors.TextSecondary,
            )
            Spacer(Modifier.size(NeuDimens.SpaceXl))
            Row(horizontalArrangement = Arrangement.spacedBy(NeuDimens.SpaceLg)) {
                NeumorphicButton(
                    text = "Cancelar",
                    onClick = onCancel,
                    containerColor = NeuColors.Background,
                    modifier = Modifier.weight(1f),
                )
                NeumorphicButton(text = "Actualizar", onClick = onConfirm, modifier = Modifier.weight(1f))
            }
        }
    }
}
