package com.eter.mitienda.data.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.eter.mitienda.domain.device.Peer
import com.eter.mitienda.domain.device.PeerSyncService
import com.eter.mitienda.domain.device.SyncState
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Transferencia P2P con Google Nearby Connections.
 *
 * - Descubrimiento y conexión por Bluetooth/BLE (bajo consumo, sin emparejar).
 * - El archivo viaja por Wi-Fi directo: Nearby "sube" la conexión a Wi-Fi en
 *   cuanto puede (decenas de MB/s frente a ~100 KB/s del Bluetooth clásico).
 * - Todo sin internet ni router.
 *
 * Estrategia P2P_POINT_TO_POINT: una sola conexión 1 a 1, la de mayor ancho de
 * banda. Todos los callbacks de Nearby llegan en el hilo principal.
 */
class NearbyPeerSyncService(
    private val context: Context,
    private val syncManager: SyncManager,
    private val files: DatabaseFileStore,
) : PeerSyncService {

    override val isSupported = true

    override val localDeviceName: String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    override val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    override val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val client = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Conexión en curso (solo una por la estrategia punto a punto). */
    private var activeEndpointId: String? = null
    private var activePeerName: String = ""
    private var isSender = false

    /** Payload del archivo .db entrante (receptor) o saliente (emisor). */
    private var filePayload: Payload? = null

    // --- Emisor ---------------------------------------------------------------

    override fun startDiscovery() {
        stop()
        isSender = true
        _state.value = SyncState.Discovering
        client.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { fail("No se pudo buscar dispositivos", it) }
    }

    override fun sendDatabase(peer: Peer) {
        isSender = true
        activePeerName = peer.name
        client.stopDiscovery()
        _state.value = SyncState.Connecting(peer.name)
        client.requestConnection(localDeviceName, peer.id, connectionCallback)
            .addOnFailureListener { fail("No se pudo conectar con ${peer.name}", it) }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            _peers.update { list -> list.filterNot { it.id == endpointId } + Peer(endpointId, info.endpointName) }
        }

        override fun onEndpointLost(endpointId: String) {
            _peers.update { list -> list.filterNot { it.id == endpointId } }
        }
    }

    private fun sendFile(endpointId: String) {
        scope.launch {
            try {
                // Copia consistente del .db (con el WAL ya volcado), fuera del hilo principal.
                val path = syncManager.prepareDatabaseForExport()
                val payload = withContext(Dispatchers.IO) { Payload.fromFile(File(path)) }
                filePayload = payload
                _state.value = SyncState.Transferring(activePeerName, 0f, isSending = true)
                client.sendPayload(endpointId, payload)
                    .addOnFailureListener { fail("Falló el envío", it) }
            } catch (e: Exception) {
                fail("No se pudo preparar la base de datos", e)
            }
        }
    }

    // --- Receptor -------------------------------------------------------------

    override fun startAdvertising() {
        stop()
        isSender = false
        _state.value = SyncState.Advertising
        client.startAdvertising(
            localDeviceName,
            SERVICE_ID,
            connectionCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnFailureListener { fail("No se pudo hacer visible este teléfono", it) }
    }

    /** Copia el archivo que dejó Nearby a nuestra carpeta y borra el original. */
    private fun storeReceivedFile(payload: Payload) {
        scope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching {
                    val received = payload.asFile() ?: error("Sin archivo")
                    val target = File(files.incomingDir, "received_${files.timestamp()}.db")
                    target.parentFile?.mkdirs()
                    val uri: Uri? = received.asUri()
                    if (uri != null) {
                        context.contentResolver.openInputStream(uri).use { input ->
                            target.outputStream().use { output -> requireNotNull(input).copyTo(output) }
                        }
                        runCatching { context.contentResolver.delete(uri, null, null) }
                    } else {
                        @Suppress("DEPRECATION")
                        val file = received.asJavaFile() ?: error("Sin archivo")
                        file.copyTo(target, overwrite = true)
                        file.delete()
                    }
                    target.absolutePath
                }
            }
            disconnect()
            stored
                .onSuccess { _state.value = SyncState.Received(activePeerName, it) }
                .onFailure { fail("No se pudo guardar el archivo recibido", it) }
        }
    }

    // --- Callbacks compartidos ------------------------------------------------

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            activeEndpointId = endpointId
            activePeerName = info.endpointName
            _state.value = SyncState.Connecting(info.endpointName, info.authenticationDigits)
            // Ambos lados aceptan: el receptor ya eligió ser visible y el
            // reemplazo de datos se confirma después, al llegar el archivo.
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { fail("No se pudo aceptar la conexión", it) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                if (isSender) {
                    sendFile(endpointId)
                } else {
                    client.stopAdvertising() // 1 a 1: nadie más puede conectarse
                    _state.value = SyncState.Transferring(activePeerName, 0f, isSending = false)
                }
            } else {
                activeEndpointId = null
                val reason = if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                    "El otro teléfono rechazó la conexión."
                } else {
                    "No se pudo establecer la conexión."
                }
                _state.value = SyncState.Failed(reason)
            }
        }

        override fun onDisconnected(endpointId: String) {
            activeEndpointId = null
            if (_state.value is SyncState.Transferring || _state.value is SyncState.Connecting) {
                _state.value = SyncState.Failed("Se perdió la conexión con $activePeerName. Acerquen los teléfonos e intenten de nuevo.")
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (!isSender && payload.type == Payload.Type.FILE) filePayload = payload
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val payload = filePayload ?: return
            if (update.payloadId != payload.id) return
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val progress = if (update.totalBytes > 0) {
                        update.bytesTransferred.toFloat() / update.totalBytes
                    } else {
                        0f
                    }
                    _state.value = SyncState.Transferring(activePeerName, progress.coerceIn(0f, 1f), isSender)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    filePayload = null
                    if (isSender) {
                        _state.value = SyncState.Sent(activePeerName)
                        disconnect()
                    } else {
                        storeReceivedFile(payload)
                    }
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    filePayload = null
                    disconnect()
                    _state.value = SyncState.Failed("La transferencia se interrumpió. Intenta de nuevo.")
                }
            }
        }
    }

    // --- Utilidades -----------------------------------------------------------

    override fun stop() {
        client.stopDiscovery()
        client.stopAdvertising()
        client.stopAllEndpoints()
        activeEndpointId = null
        filePayload = null
        _peers.value = emptyList()
        _state.value = SyncState.Idle
    }

    private fun disconnect() {
        activeEndpointId?.let(client::disconnectFromEndpoint)
        activeEndpointId = null
    }

    private fun fail(prefix: String, error: Throwable) {
        val detail = when ((error as? ApiException)?.statusCode) {
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
            -> "faltan permisos de ubicación o de dispositivos cercanos."
            ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "revisa que el Bluetooth esté encendido."
            ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "revisa que Bluetooth y Wi-Fi estén encendidos."
            null -> error.message ?: "error desconocido."
            else -> "código ${(error as ApiException).statusCode}. Revisa Bluetooth, Wi-Fi y ubicación."
        }
        disconnect()
        _state.value = SyncState.Failed("$prefix: $detail")
    }

    private companion object {
        /** Identifica a esta app: solo se ven teléfonos con MiTienda en modo sincronización. */
        const val SERVICE_ID = "com.eter.mitienda.sync"
        val STRATEGY: Strategy = Strategy.P2P_POINT_TO_POINT
    }
}
