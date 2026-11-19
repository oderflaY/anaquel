package com.eter.mitienda.feature.categories

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.mitienda.domain.model.Category
import com.eter.mitienda.domain.model.CategoryWithCount
import com.eter.mitienda.domain.repository.CategoryRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Qué diálogo está abierto en la pantalla de categorías. */
sealed interface CategoryDialog {
    data object Create : CategoryDialog
    data class Rename(val category: Category) : CategoryDialog
    data class Delete(val item: CategoryWithCount) : CategoryDialog
}

/** `null` mientras carga. */
data class CategoriesUiState(
    val categories: List<CategoryWithCount>? = null,
    val error: String? = null,
)

class CategoriesViewModel(
    private val repository: CategoryRepository,
) : ViewModel() {

    val uiState: StateFlow<CategoriesUiState> = repository.observeCategories()
        .map { CategoriesUiState(categories = it) }
        .catch { e -> emit(CategoriesUiState(categories = emptyList(), error = e.message)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    var dialog by mutableStateOf<CategoryDialog?>(null)
        private set

    /** Error dentro del diálogo abierto (nombre repetido, BD ocupada…). */
    var dialogError by mutableStateOf<String?>(null)
        private set

    fun openDialog(newDialog: CategoryDialog) {
        dialogError = null
        dialog = newDialog
    }

    fun closeDialog() {
        dialog = null
        dialogError = null
    }

    fun create(name: String) = submit(name) { repository.createCategory(name).map { } }

    fun rename(category: Category, newName: String) = submit(newName) { repository.renameCategory(category.id, newName) }

    fun delete(category: Category) {
        viewModelScope.launch {
            repository.deleteCategory(category.id)
                .onSuccess { closeDialog() }
                .onFailure { e -> dialogError = e.message }
        }
    }

    private fun submit(name: String, action: suspend () -> Result<Unit>) {
        if (name.isBlank()) {
            dialogError = "Escribe un nombre"
            return
        }
        viewModelScope.launch {
            action()
                .onSuccess { closeDialog() }
                .onFailure { e -> dialogError = e.message }
        }
    }
}
