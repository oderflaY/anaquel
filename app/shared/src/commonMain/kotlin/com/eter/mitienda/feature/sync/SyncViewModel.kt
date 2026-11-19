package com.eter.mitienda.feature.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.data.sync.InvalidDatabaseException
import com.eter.mitienda.data.sync.SyncManager
import com.eter.mitienda.domain.device.Peer
import com.eter.mitienda.domain.device.PeerSyncService
import com.eter.mitienda.domain.device.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class SyncMode { Sender, Receiver }

/** Resultado de reemplazar la BD local con la recibida. */
sealed interface ImportStatus {
    data object None : ImportStatus
    data object Importing : ImportStatus
    data class Done(val backupFileName: String) : ImportStatus
    data class Failed(val message: String) : ImportStatus
}

class SyncViewModel(
    private val service: PeerSyncService,
    private val syncManager: SyncManager,
) : ViewModel() {

    val isSupported: Boolean get() = service.isSupported
    val localDeviceName: String get() = service.localDeviceName
    val connection: StateFlow<SyncState> = service.state
    val peers: StateFlow<List<Peer>> = service.peers

    var mode by mutableStateOf<SyncMode?>(null)
        private set

    var importStatus by mutableStateOf<ImportStatus>(ImportStatus.None)
        private set

    /** Cambiar de modo corta lo anterior. El receptor se hace visible al instante. */
    fun selectMode(newMode: SyncMode) {
        service.stop()
        importStatus = ImportStatus.None
        mode = newMode
        if (newMode == SyncMode.Receiver) service.startAdvertising()
    }

    fun startDiscovery() = service.startDiscovery()

    fun sendTo(peer: Peer) = service.sendDatabase(peer)

    /** Reintenta lo que corresponda al modo actual tras un error. */
    fun retry() {
        when (mode) {
            SyncMode.Sender -> service.startDiscovery()
            SyncMode.Receiver -> service.startAdvertising()
            null -> Unit
        }
    }

    /** "Actualizar": respaldo + reemplazo de la BD. El Dashboard se refresca solo. */
    fun confirmImport(filePath: String) {
        if (importStatus == ImportStatus.Importing) return
        importStatus = ImportStatus.Importing
        viewModelScope.launch {
            importStatus = try {
                val backup = syncManager.importAndReplaceDatabase(filePath)
                ImportStatus.Done(backup.substringAfterLast('/'))
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidDatabaseException) {
                syncManager.discardReceived(filePath)
                ImportStatus.Failed(e.message ?: "El archivo recibido no es válido.")
            } catch (e: Exception) {
                ImportStatus.Failed("No se pudo actualizar el inventario. Tus datos actuales no se modificaron.")
            }
            service.stop()
        }
    }

    /** "Cancelar": se descarta el archivo y el teléfono vuelve a quedar visible. */
    fun cancelImport(filePath: String) {
        syncManager.discardReceived(filePath)
        service.startAdvertising()
    }

    /** Al salir de la pantalla se apagan las radios: no se queda buscando en segundo plano. */
    override fun onCleared() {
        service.stop()
    }
}
