package com.eter.mitienda.domain.device

import kotlinx.coroutines.flow.StateFlow

/** Dispositivo cercano descubierto (modo emisor). */
data class Peer(val id: String, val name: String)

/** Estados de la conexión P2P que la UI representa. */
sealed interface SyncState {
    data object Idle : SyncState

    /** Emisor buscando receptores. */
    data object Discovering : SyncState

    /** Receptor visible, esperando que alguien se conecte. */
    data object Advertising : SyncState

    /**
     * Negociando la conexión. [authCode] es el mismo en ambos teléfonos: el
     * usuario puede compararlos para confirmar que se conectó al equipo correcto.
     */
    data class Connecting(val peerName: String, val authCode: String? = null) : SyncState

    data class Transferring(val peerName: String, val progress: Float, val isSending: Boolean) : SyncState

    /** Emisor: el receptor recibió el archivo completo. */
    data class Sent(val peerName: String) : SyncState

    /** Receptor: archivo completo en [filePath], pendiente de que el usuario confirme. */
    data class Received(val peerName: String, val filePath: String) : SyncState

    data class Failed(val message: String) : SyncState
}

/**
 * Puerto de la transferencia P2P de la base de datos.
 * Android: Nearby Connections. iOS: no disponible (ver [isSupported]).
 */
interface PeerSyncService {
    val isSupported: Boolean
    val localDeviceName: String
    val state: StateFlow<SyncState>
    val peers: StateFlow<List<Peer>>

    /** Emisor: empieza a buscar receptores cercanos. */
    fun startDiscovery()

    /** Emisor: conecta con [peer] y le envía la BD completa. */
    fun sendDatabase(peer: Peer)

    /** Receptor: se hace visible y acepta conexiones entrantes. */
    fun startAdvertising()

    /** Corta todo (radios incluidas) y vuelve a [SyncState.Idle]. */
    fun stop()
}
