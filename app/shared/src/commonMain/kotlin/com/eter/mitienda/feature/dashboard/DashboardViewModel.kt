package com.eter.mitienda.feature.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.domain.model.Category
import com.eter.mitienda.domain.model.Product
import com.eter.mitienda.domain.repository.CategoryRepository
import com.eter.mitienda.domain.repository.ProductRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Filtro activo de la fila de chips. */
sealed interface ProductFilter {
    data object All : ProductFilter
    data object LowStock : ProductFilter
    data class ByCategory(val categoryId: Long) : ProductFilter
}

/** Estado inmutable que la pantalla renderiza. */
data class DashboardUiState(
    val products: List<Product> = emptyList(),
    val totalProducts: Int = 0,
    val lowStockCount: Int = 0,
    val categories: List<Category> = emptyList(),
    val filter: ProductFilter = ProductFilter.All,
    val isLoading: Boolean = true,
    /** Mensaje para el usuario si la lectura de la BD falló. */
    val error: String? = null,
)

/** Avisos de un solo uso (Snackbar). */
sealed interface DashboardEvent {
    /** Se escaneó un código que no está en el inventario. */
    data class NotFound(val barcode: String) : DashboardEvent
    data class Message(val text: String) : DashboardEvent
}

/** Resultado de leer la tabla, antes de aplicar filtros. */
private sealed interface ProductsLoad {
    data object Loading : ProductsLoad
    data class Loaded(val products: List<Product>) : ProductsLoad
    data class Failed(val message: String) : ProductsLoad
}

class DashboardViewModel(
    private val repository: ProductRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    /**
     * El texto de búsqueda es estado de Compose (síncrono) y no un StateFlow:
     * un TextField alimentado por un flujo asíncrono puede perder caracteres o
     * mover el cursor al escribir rápido.
     */
    var query by mutableStateOf("")
        private set

    private val filter = MutableStateFlow<ProductFilter>(ProductFilter.All)

    /** Aviso pendiente de mostrar (`null` = nada que mostrar). */
    var event by mutableStateOf<DashboardEvent?>(null)
        private set

    /** Cada incremento vuelve a suscribirse a la BD (botón "Reintentar"). */
    private val retryTrigger = MutableStateFlow(0)

    /**
     * Lista reactiva desde SQLite. Un error no mata el flujo de la pantalla:
     * se convierte en estado `Failed` y el usuario puede reintentar.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val productsLoad: Flow<ProductsLoad> = retryTrigger.flatMapLatest {
        repository.observeProducts()
            .map<List<Product>, ProductsLoad> { ProductsLoad.Loaded(it) }
            .onStart { emit(ProductsLoad.Loading) }
            .catch { e -> emit(ProductsLoad.Failed(e.message ?: "No se pudo leer el inventario.")) }
    }

    /** Si fallan las categorías, la lista de productos sigue funcionando sin chips de categoría. */
    private val categories: Flow<List<Category>> = categoryRepository.observeCategories()
        .map { list -> list.map { it.category } }
        .catch { emit(emptyList()) }

    val uiState: StateFlow<DashboardUiState> = combine(
        productsLoad,
        categories,
        snapshotFlow { query },
        filter,
    ) { load, categories, query, selected ->
        // Si la categoría filtrada se borró, se vuelve a "Todos".
        val activeFilter = selected.takeUnless { f ->
            f is ProductFilter.ByCategory && categories.none { it.id == f.categoryId }
        } ?: ProductFilter.All
        when (load) {
            ProductsLoad.Loading -> DashboardUiState(categories = categories, filter = activeFilter)
            is ProductsLoad.Failed -> DashboardUiState(
                categories = categories,
                filter = activeFilter,
                isLoading = false,
                error = load.message,
            )
            is ProductsLoad.Loaded -> DashboardUiState(
                products = load.products.filter { it.matches(query, activeFilter) },
                totalProducts = load.products.size,
                lowStockCount = load.products.count { it.isLowStock },
                categories = categories,
                filter = activeFilter,
                isLoading = false,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    fun onQueryChange(newQuery: String) {
        query = newQuery
    }

    fun onFilterSelected(newFilter: ProductFilter) {
        filter.value = newFilter
    }

    /** Botones − / + de la tarjeta. La lista se refresca sola al terminar. */
    fun onAdjustStock(product: Product, delta: Int) {
        viewModelScope.launch {
            repository.adjustProductStock(product.id, delta)
                .onFailure { e -> event = DashboardEvent.Message(e.message ?: "No se pudo cambiar el stock.") }
        }
    }

    /**
     * Búsqueda exacta por índice. Si existe, se escribe el código en el buscador
     * (y se quita el filtro) para que la lista muestre solo ese producto; si no,
     * se emite [DashboardEvent.NotFound] para ofrecer darlo de alta.
     */
    fun onBarcodeScanned(barcode: String) {
        viewModelScope.launch {
            repository.getProductByBarcode(barcode)
                .onSuccess { product ->
                    if (product != null) {
                        query = product.barcode
                        filter.value = ProductFilter.All
                    } else {
                        event = DashboardEvent.NotFound(barcode)
                    }
                }
                .onFailure { e ->
                    event = DashboardEvent.Message(e.message ?: "No se pudo buscar el producto.")
                }
        }
    }

    fun onEventShown() {
        event = null
    }

    fun onRetry() {
        retryTrigger.update { it + 1 }
    }

    /** La búsqueda también encuentra por ubicación ("pasillo 3") y por categoría. */
    private fun Product.matches(query: String, filter: ProductFilter): Boolean {
        val inFilter = when (filter) {
            ProductFilter.All -> true
            ProductFilter.LowStock -> isLowStock
            is ProductFilter.ByCategory -> category?.id == filter.categoryId
        }
        val text = query.trim()
        val inQuery = text.isEmpty() ||
            name.contains(text, ignoreCase = true) ||
            barcode.contains(text) ||
            location?.contains(text, ignoreCase = true) == true ||
            category?.name?.contains(text, ignoreCase = true) == true
        return inFilter && inQuery
    }
}
