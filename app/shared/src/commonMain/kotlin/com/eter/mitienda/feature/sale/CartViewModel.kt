package com.eter.mitienda.feature.sale

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.core.util.formatPrice
import com.eter.mitienda.core.util.isValidMoneyInput
import com.eter.mitienda.core.util.parseMoneyToCents
import com.eter.mitienda.domain.model.CartItem
import com.eter.mitienda.domain.model.Product
import com.eter.mitienda.domain.repository.ProductRepository
import com.eter.mitienda.domain.repository.SaleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Carrito del punto de venta. Todo el estado editable es estado de Compose
 * (síncrono); los totales se calculan al vuelo a partir de [items], así que
 * nunca pueden quedar desfasados respecto a lo que se ve.
 */
class CartViewModel(
    private val products: ProductRepository,
    private val sales: SaleRepository,
) : ViewModel() {

    var items by mutableStateOf<List<CartItem>>(emptyList())
        private set

    /** Texto del buscador (nombre o código). */
    var query by mutableStateOf("")
        private set

    /** Efectivo recibido, tal como se escribe. */
    var received by mutableStateOf("")
        private set

    var receivedError by mutableStateOf<String?>(null)
        private set

    /** Lo que el panel del escáner muestra tras cada lectura. */
    var scanStatus by mutableStateOf<String?>(null)
        private set

    /** Aviso puntual para el Snackbar. */
    var message by mutableStateOf<String?>(null)
        private set

    var isCharging by mutableStateOf(false)
        private set

    /** Id de la venta recién cobrada: la pantalla navega al ticket. */
    var completedSaleId by mutableStateOf<Long?>(null)
        private set

    /** Catálogo completo para las sugerencias del buscador. */
    val catalog: StateFlow<List<Product>> = products.observeProducts()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCents: Long get() = items.sumOf { it.subtotalCents }
    val itemCount: Int get() = items.sumOf { it.quantity }
    val changeCents: Long? get() = parseMoneyToCents(received)?.let { it - totalCents }

    /** Hasta 5 productos cuyo nombre o código contiene lo escrito. */
    fun suggestions(catalog: List<Product>): List<Product> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        return catalog
            .filter { it.name.contains(text, ignoreCase = true) || it.barcode.contains(text) }
            .take(5)
    }

    fun onQueryChange(value: String) {
        query = value
    }

    /** Enter en el buscador: código exacto (lector USB/Bluetooth o tecleado) o única coincidencia. */
    fun onQuerySubmit(catalog: List<Product>) {
        val text = query.trim()
        if (text.isEmpty()) return
        val match = catalog.firstOrNull { it.barcode == text } ?: suggestions(catalog).singleOrNull()
        if (match != null) {
            add(match)
            query = ""
        } else {
            message = "No se encontró \"$text\" en el inventario"
        }
    }

    fun onSuggestionSelected(product: Product) {
        add(product)
        query = ""
    }

    /** Cada lectura del escáner continuo suma una pieza. */
    fun onBarcodeScanned(code: String) {
        viewModelScope.launch {
            products.getProductByBarcode(code)
                .onSuccess { product ->
                    scanStatus = if (product != null) {
                        add(product)
                        val qty = items.first { it.product.id == product.id }.quantity
                        "✓ ${product.name} (×$qty)\nTotal ${formatPrice(totalCents)} · $itemCount artículos"
                    } else {
                        "✗ El código $code no está en el inventario"
                    }
                }
                .onFailure { e -> scanStatus = e.message ?: "No se pudo buscar el producto" }
        }
    }

    fun increment(productId: Long) = changeQuantity(productId) { it + 1 }

    /** No baja de 1: para quitar el renglón se usa [remove]. */
    fun decrement(productId: Long) = changeQuantity(productId) { maxOf(1, it - 1) }

    fun remove(productId: Long) {
        items = items.filterNot { it.product.id == productId }
    }

    fun clear() {
        items = emptyList()
        received = ""
        receivedError = null
        scanStatus = null
    }

    fun onReceivedChange(value: String) {
        if (isValidMoneyInput(value)) {
            received = value
            receivedError = null
        }
    }

    fun onMessageShown() {
        message = null
    }

    /**
     * Cobra: valida el efectivo (opcional; si se captura debe alcanzar), guarda
     * la venta y descuenta el stock en una transacción.
     */
    fun charge() {
        if (items.isEmpty() || isCharging) return
        val receivedCents = if (received.isBlank()) null else parseMoneyToCents(received)
        if (received.isNotBlank() && receivedCents == null) {
            receivedError = "Cantidad no válida"
            return
        }
        if (receivedCents != null && receivedCents < totalCents) {
            receivedError = "Faltan ${formatPrice(totalCents - receivedCents)}"
            return
        }
        isCharging = true
        viewModelScope.launch {
            sales.registerSale(items, receivedCents)
                .onSuccess { sale ->
                    clear()
                    completedSaleId = sale.id
                }
                .onFailure { e -> message = e.message ?: "No se pudo registrar la venta" }
            isCharging = false
        }
    }

    fun onSaleShown() {
        completedSaleId = null
    }

    /** Suma una pieza; si ya estaba, actualiza también sus datos (precio, stock). */
    private fun add(product: Product) {
        val existing = items.any { it.product.id == product.id }
        items = if (existing) {
            items.map { if (it.product.id == product.id) CartItem(product, it.quantity + 1) else it }
        } else {
            items + CartItem(product, 1)
        }
        receivedError = null
    }

    private fun changeQuantity(productId: Long, transform: (Int) -> Int) {
        items = items.map { if (it.product.id == productId) it.copy(quantity = transform(it.quantity)) else it }
        receivedError = null
    }
}
