package com.eter.mitienda

import android.app.Application
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.eter.mitienda.core.image.AndroidImageStorage
import com.eter.mitienda.data.sync.AndroidDatabaseFileStore
import com.eter.mitienda.data.sync.NearbyPeerSyncService
import com.eter.mitienda.core.notifications.AndroidOrderReminderScheduler
import com.eter.mitienda.di.AppContainer
import com.eter.mitienda.di.AppContainerProvider

/**
 * El contenedor vive en la Application y no en la Activity: al rotar la
 * pantalla la Activity se recrea, pero la conexión a la BD sigue siendo la misma.
 */
class MiTiendaApplication : Application(), AppContainerProvider {

    override fun onCreate() {
        super.onCreate()
        // El almacén seguro del mercado necesita el contexto de la app.
        com.eter.mitienda.feature.mercado.ContextoMercado.app = this
    }

    override val container: AppContainer by lazy {
        val databaseFiles = AndroidDatabaseFileStore(this)
        AppContainer(
            driver = AndroidSQLiteDriver(),
            databaseFiles = databaseFiles,
            imageStorage = AndroidImageStorage(this),
            reminderScheduler = AndroidOrderReminderScheduler(this),
            peerSyncFactory = { syncManager -> NearbyPeerSyncService(this, syncManager, databaseFiles) },
        )
    }
}
