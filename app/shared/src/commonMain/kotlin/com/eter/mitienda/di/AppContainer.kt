package com.eter.mitienda.di

import androidx.sqlite.SQLiteDriver
import com.eter.mitienda.core.notifications.OrderReminderScheduler
import com.eter.mitienda.core.image.ImageStorage
import com.eter.mitienda.data.local.PapeleriaDatabase
import com.eter.mitienda.data.local.CategoryDao
import com.eter.mitienda.data.local.OrderDao
import com.eter.mitienda.data.local.StatsDao
import com.eter.mitienda.data.local.ProductDao
import com.eter.mitienda.data.local.SaleDao
import com.eter.mitienda.data.repository.DatabaseChanges
import com.eter.mitienda.data.repository.SqliteCategoryRepository
import com.eter.mitienda.data.repository.SqliteOrderRepository
import com.eter.mitienda.data.repository.StatsRepository
import com.eter.mitienda.data.repository.SqliteProductRepository
import com.eter.mitienda.data.repository.SqliteSaleRepository
import com.eter.mitienda.data.sync.DatabaseFileStore
import com.eter.mitienda.data.sync.SyncManager
import com.eter.mitienda.domain.device.PeerSyncService
import com.eter.mitienda.domain.repository.CategoryRepository
import com.eter.mitienda.domain.repository.OrderRepository
import com.eter.mitienda.domain.repository.ProductRepository
import com.eter.mitienda.domain.repository.SaleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Inyección de dependencias manual. Debe existir UNA instancia por proceso
 * (Application en Android, singleton en iOS): así hay una sola conexión a la
 * BD y un solo repositorio, que es el que avisa a todas las pantallas.
 *
 * @param peerSyncFactory crea el servicio P2P de la plataforma a partir del SyncManager.
 * @param reminderScheduler avisos locales de pedidos (WorkManager / UNUserNotificationCenter).
 */
class AppContainer(
    driver: SQLiteDriver,
    val databaseFiles: DatabaseFileStore,
    val imageStorage: ImageStorage,
    reminderScheduler: OrderReminderScheduler,
    peerSyncFactory: (SyncManager) -> PeerSyncService,
) {
    /** Trabajo de fondo que no pertenece a ninguna pantalla. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database = PapeleriaDatabase(driver, databaseFiles.databasePath)

    private val changes = DatabaseChanges()
    val productRepository: ProductRepository = SqliteProductRepository(ProductDao(database), changes)
    val categoryRepository: CategoryRepository = SqliteCategoryRepository(CategoryDao(database), changes)
    val saleRepository: SaleRepository = SqliteSaleRepository(SaleDao(database), changes)
    val orderRepository: OrderRepository = SqliteOrderRepository(OrderDao(database), changes, reminderScheduler)
    val statsRepository = StatsRepository(StatsDao(database), changes)

    /** Anaquel (nube): cliente del servidor y ajustes donde se guarda la sesión. */
    val anaquelApi = com.eter.mitienda.feature.anaquel.AnaquelApi()
    val ajustesDao = com.eter.mitienda.data.local.AjustesDao(database)

    val syncManager = SyncManager(
        database = database,
        files = databaseFiles,
        // Tras importar otra BD, todas las pantallas vuelven a leer y los
        // avisos se reprograman con los pedidos que trae la BD nueva.
        onDatabaseReplaced = {
            changes.notifyChanged()
            appScope.launch { orderRepository.rescheduleAllReminders() }
        },
    )

    /** Perezoso: Nearby no se inicializa hasta abrir la pantalla de sincronización. */
    val peerSyncService: PeerSyncService by lazy { peerSyncFactory(syncManager) }
}
