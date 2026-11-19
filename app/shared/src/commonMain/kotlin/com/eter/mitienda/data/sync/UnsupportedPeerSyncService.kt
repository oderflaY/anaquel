package com.eter.mitienda.data.sync

import com.eter.mitienda.domain.device.Peer
import com.eter.mitienda.domain.device.PeerSyncService
import com.eter.mitienda.domain.device.SyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plataformas sin transferencia P2P compatible (iOS por ahora: Nearby
 * Connections no tiene versión estable para iOS y MultipeerConnectivity no
 * habla con Android). La pantalla muestra el aviso en lugar de fallar.
 */
class UnsupportedPeerSyncService(override val localDeviceName: String) : PeerSyncService {
    override val isSupported = false
    override val state: StateFlow<SyncState> = MutableStateFlow(SyncState.Idle)
    override val peers: StateFlow<List<Peer>> = MutableStateFlow(emptyList())
    override fun startDiscovery() = Unit
    override fun sendDatabase(peer: Peer) = Unit
    override fun startAdvertising() = Unit
    override fun stop() = Unit
}
