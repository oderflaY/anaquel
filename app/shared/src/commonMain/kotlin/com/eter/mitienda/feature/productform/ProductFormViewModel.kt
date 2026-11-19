package com.eter.mitienda.feature.productform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.core.image.ImagePickResult
import com.eter.mitienda.core.image.ImageStorage
import com.eter.mitienda.core.util.centsToInput
import com.eter.mitienda.core.util.isValidMoneyInput
import com.eter.mitienda.core.util.parseMoneyToCents
import com.eter.mitienda.domain.model.Category
import com.eter.mitienda.domain.model.DataError
import com.eter.mitienda.domain.model.Product
import com.eter.mitienda.domain.repository.CategoryRepository
import com.eter.mitienda.domain.repository.ProductRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Estado del formulario. Los campos numéricos se guardan como texto tal cual
 * los escribe el usuario; se convierten y validan solo al guardar.
 */
data class ProductFormUiState(
    val isEditing: Boolean = false,
    val isLoadingProduct: Boolean = false,
    val barcode: String = "",
    val name: String = "",
    val description: String = "",
    val price: String = "",
    val cost: String = "",
    val stock: String = "",
    val minStock: String = Product.DEFAULT_MIN_STOCK.toString(),
    val category: Category? = null,
    val location: String = "",
    /** Categorías del usuario para el selector. */
    val categories: List<Category> = emptyList(),
    /** Ubicaciones ya usadas en otros productos, para sugerirlas. */
    val knownLocations: List<String> = emptyList(),
    /** Foto ya guardada (ruta relativa de SQLite); `null` si no tiene o se quitó. */
    val imagePath: String? = null,
    /** Foto recién elegida, aún en la carpeta temporal; se persiste al guardar. */
    val pendingImagePath: String? = null,
    val barcodeError: String? = null,
    val nameError: String? = null,
    val priceError: String? = null,
    val costError: String? = null,
    val stockError: String? = null,
    val minStockError: String? = null,
    /** Error al crear una categoría desde el formulario (nombre repetido…). */
    val newCategoryError: String? = null,
    val isSaving: Boolean = false,
    /** Error general (BD ocupada, producto borrado…). */
    val errorMessage: String? = null,
    /** `true` tras guardar/eliminar con éxito: la pantalla debe cerrarse. */
    val isFinished: Boolean = false,
) {
    /** Lo que muestra el selector: la foto nueva si hay, si no la guardada. */
    val displayImagePath: String? get() = pendingImagePath ?: imagePath

    /** Sugerencias que coinciden con lo escrito (sin repetir lo que ya está escrito tal cual). */
    val locationSuggestions: List<String>
        get() = knownLocations
            .filter { it.contains(location.trim(), ignoreCase = true) && !it.equals(location.trim(), ignoreCase = true) }
            .take(6)
}

/**
 * @param productId `null` = alta de producto; con valor = edición.
 * @param initialBarcode código prellenado (alta lanzada desde un escaneo sin resultado).
 */
class ProductFormViewModel(
    private val repository: ProductRepository,
    private val categoryRepository: CategoryRepository,
    private val imageStorage: ImageStorage,
    private val productId: Long?,
    initialBarcode: String? = null,
) : ViewModel() {

    /** Estado de Compose (síncrono): evita saltos de cursor en los TextField. */
    var state by mutableStateOf(
        ProductFormUiState(isEditing = productId != null, barcode = initialBarcode.orEmpty()),
    )
        private set

    /** Foto que tenía el producto al abrirlo: se borra del disco si se reemplaza o se quita. */
    private var originalImagePath: String? = null

    init {
        if (productId != null) loadProduct(productId)
        viewModelScope.launch {
            categoryRepository.observeCategories()
                .catch { emit(emptyList()) }
                .collect { list -> state = state.copy(categories = list.map { it.category }) }
        }
        viewModelScope.launch {
            repository.getKnownLocations().onSuccess { state = state.copy(knownLocations = it) }
        }
    }

    private fun loadProduct(id: Long) {
        state = state.copy(isLoadingProduct = true)
        viewModelScope.launch {
            repository.getProductById(id)
                .onSuccess { product ->
                    state = if (product == null) {
                        state.copy(isLoadingProduct = false, errorMessage = DataError.NotFound(id).message)
                    } else {
                        originalImagePath = product.imagePath
                        state.copy(
                            isLoadingProduct = false,
                            barcode = product.barcode,
                            name = product.name,
                            description = product.description.orEmpty(),
                            price = centsToInput(product.priceCents),
                            cost = centsToInput(product.costCents),
                            stock = product.stock.toString(),
                            category = product.category,
                            imagePath = product.imagePath,
                            minStock = product.minStock.toString(),
                            location = product.location.orEmpty(),
                        )
                    }
                }
                .onFailure { e ->
                    state = state.copy(isLoadingProduct = false, errorMessage = e.message)
                }
        }
    }

    // --- Edición de campos (cada cambio limpia el error de ese campo) ----------

    /** Mismo punto de entrada para teclado y escáner: ambos solo cambian el texto. */
    fun onBarcodeChange(value: String) {
        if (value.length <= MAX_BARCODE_LENGTH) state = state.copy(barcode = value, barcodeError = null)
    }

    fun onNameChange(value: String) {
        if (value.length <= MAX_NAME_LENGTH) state = state.copy(name = value, nameError = null)
    }

    fun onDescriptionChange(value: String) {
        if (value.length <= MAX_DESCRIPTION_LENGTH) state = state.copy(description = value)
    }

    fun onPriceChange(value: String) {
        if (isValidMoneyInput(value)) state = state.copy(price = value, priceError = null)
    }

    fun onCostChange(value: String) {
        if (isValidMoneyInput(value)) state = state.copy(cost = value, costError = null)
    }

    fun onStockChange(value: String) {
        if (value.length <= 6 && value.all(Char::isDigit)) state = state.copy(stock = value, stockError = null)
    }

    fun onMinStockChange(value: String) {
        if (value.length <= 6 && value.all(Char::isDigit)) state = state.copy(minStock = value, minStockError = null)
    }

    fun onLocationChange(value: String) {
        if (value.length <= MAX_LOCATION_LENGTH) state = state.copy(location = value)
    }

    fun onCategoryChange(category: Category?) {
        state = state.copy(category = category)
    }

    /** "+ Nueva categoría" desde el selector: se crea y queda elegida. */
    fun onCreateCategory(name: String, onCreated: () -> Unit) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            state = state.copy(newCategoryError = "Escribe un nombre")
            return
        }
        viewModelScope.launch {
            categoryRepository.createCategory(trimmed)
                .onSuccess { id ->
                    state = state.copy(category = Category(id, trimmed), newCategoryError = null)
                    onCreated()
                }
                .onFailure { e -> state = state.copy(newCategoryError = e.message) }
        }
    }

    fun onNewCategoryDialogClosed() {
        state = state.copy(newCategoryError = null)
    }

    /** Resultado de la cámara/galería. Cancelar no cambia nada. */
    fun onImagePicked(result: ImagePickResult) {
        when (result) {
            ImagePickResult.Cancelled -> Unit
            is ImagePickResult.Error -> state = state.copy(errorMessage = result.message)
            is ImagePickResult.Success -> {
                state.pendingImagePath?.let(imageStorage::discardTemp) // la anterior ya no sirve
                state = state.copy(pendingImagePath = result.tempPath, errorMessage = null)
            }
        }
    }

    /** "Quitar foto": el archivo guardado se borra solo si el usuario guarda. */
    fun onRemoveImage() {
        state.pendingImagePath?.let(imageStorage::discardTemp)
        state = state.copy(pendingImagePath = null, imagePath = null)
    }

    fun onErrorShown() {
        state = state.copy(errorMessage = null)
    }

    // --- Acciones ---------------------------------------------------------------

    fun save() {
        if (state.isSaving || state.isLoadingProduct) return
        val product = validate() ?: return

        state = state.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            // 1) Foto nueva → directorio persistente. 2) Ruta → SQLite.
            val pending = state.pendingImagePath
            val savedImage = if (pending != null) {
                try {
                    imageStorage.saveImageLocally(pending)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state = state.copy(isSaving = false, errorMessage = "No se pudo guardar la foto. Intenta de nuevo.")
                    return@launch
                }
            } else {
                null
            }
            val finalImagePath = savedImage ?: state.imagePath
            val toSave = product.copy(imagePath = finalImagePath)

            val result = if (productId == null) {
                repository.insertProduct(toSave).map { }
            } else {
                repository.updateProduct(toSave)
            }
            result
                .onSuccess {
                    pending?.let(imageStorage::discardTemp)
                    // La foto anterior quedó huérfana (reemplazada o quitada): fuera del disco.
                    originalImagePath?.takeIf { it != finalImagePath }?.let { imageStorage.deleteLocalImage(it) }
                    state = state.copy(isSaving = false, pendingImagePath = null, isFinished = true)
                }
                .onFailure { e ->
                    // Deshacer la copia; el temporal sigue intacto para reintentar.
                    savedImage?.let { imageStorage.deleteLocalImage(it) }
                    state = when (e) {
                        is DataError.DuplicateBarcode -> state.copy(
                            isSaving = false,
                            barcodeError = "Ya existe un producto con este código",
                        )
                        else -> state.copy(isSaving = false, errorMessage = e.message)
                    }
                }
        }
    }

    fun delete() {
        val id = productId ?: return
        if (state.isSaving) return
        state = state.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            repository.deleteProduct(id)
                .onSuccess {
                    // Primero la fila y después el archivo: si falla el borrado en BD,
                    // el producto conserva su foto.
                    originalImagePath?.let { imageStorage.deleteLocalImage(it) }
                    state.pendingImagePath?.let(imageStorage::discardTemp)
                    state = state.copy(isSaving = false, pendingImagePath = null, isFinished = true)
                }
                .onFailure { e -> state = state.copy(isSaving = false, errorMessage = e.message) }
        }
    }

    /** Si el usuario sale sin guardar, la foto temporal elegida no debe quedarse ocupando espacio. */
    override fun onCleared() {
        state.pendingImagePath?.let(imageStorage::discardTemp)
    }

    /**
     * Valida todos los campos a la vez (el usuario ve todos los errores juntos,
     * no uno por intento). Devuelve el producto listo o `null` si hay errores.
     */
    private fun validate(): Product? {
        val s = state
        val barcode = s.barcode.trim()
        val name = s.name.trim()
        val priceCents = parseMoneyToCents(s.price)
        val costCents = if (s.cost.isBlank()) 0L else parseMoneyToCents(s.cost)
        val stock = if (s.stock.isBlank()) 0 else s.stock.toIntOrNull()
        val minStock = if (s.minStock.isBlank()) 0 else s.minStock.toIntOrNull()

        val barcodeError = if (barcode.isEmpty()) "El código de barras es obligatorio" else null
        val nameError = if (name.isEmpty()) "El nombre es obligatorio" else null
        val priceError = when {
            s.price.isBlank() -> "El precio es obligatorio"
            priceCents == null -> "Precio no válido"
            else -> null
        }
        val costError = if (costCents == null) "Costo no válido" else null
        val stockError = if (stock == null) "Stock no válido" else null
        val minStockError = if (minStock == null) "Mínimo no válido" else null

        state = s.copy(
            barcodeError = barcodeError,
            nameError = nameError,
            priceError = priceError,
            costError = costError,
            stockError = stockError,
            minStockError = minStockError,
        )
        if (listOf(barcodeError, nameError, priceError, costError, stockError, minStockError).any { it != null }) {
            return null
        }

        return Product(
            id = productId ?: 0,
            barcode = barcode,
            name = name,
            description = s.description.trim().ifEmpty { null },
            priceCents = priceCents!!,
            costCents = costCents!!,
            stock = stock!!,
            minStock = minStock!!,
            category = s.category,
            location = s.location.trim().ifEmpty { null },
            imagePath = s.imagePath,
            // updatedAt lo fija el DAO al escribir.
        )
    }

    private companion object {
        const val MAX_BARCODE_LENGTH = 48
        const val MAX_NAME_LENGTH = 120
        const val MAX_DESCRIPTION_LENGTH = 500
        const val MAX_LOCATION_LENGTH = 60
    }
}
