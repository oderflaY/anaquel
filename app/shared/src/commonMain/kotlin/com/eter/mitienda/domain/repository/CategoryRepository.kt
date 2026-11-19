package com.eter.mitienda.domain.repository

import com.eter.mitienda.domain.model.CategoryWithCount
import kotlinx.coroutines.flow.Flow

/** Categorías creadas por el usuario. Mismo contrato que [ProductRepository]: nunca lanza. */
interface CategoryRepository {
    /** Ordenadas por nombre, con cuántos productos tiene cada una. Reactivo. */
    fun observeCategories(): Flow<List<CategoryWithCount>>

    /** @return id de la nueva categoría. Falla con DataError.DuplicateCategory si el nombre existe. */
    suspend fun createCategory(name: String): Result<Long>

    suspend fun renameCategory(id: Long, newName: String): Result<Unit>

    /** Sus productos quedan "Sin categoría" (ON DELETE SET NULL); no se borra ningún producto. */
    suspend fun deleteCategory(id: Long): Result<Unit>
}
