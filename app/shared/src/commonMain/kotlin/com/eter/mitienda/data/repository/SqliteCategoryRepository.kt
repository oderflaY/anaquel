package com.eter.mitienda.data.repository

import com.eter.mitienda.data.local.CategoryDao
import com.eter.mitienda.domain.model.CategoryWithCount
import com.eter.mitienda.domain.model.DataError
import com.eter.mitienda.domain.repository.CategoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

class SqliteCategoryRepository(
    private val dao: CategoryDao,
    private val changes: DatabaseChanges,
) : CategoryRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCategories(): Flow<List<CategoryWithCount>> =
        changes.version.mapLatest {
            try {
                dao.getCategoriesWithCount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e.toDataError()
            }
        }

    override suspend fun createCategory(name: String): Result<Long> =
        write(name) { dao.insert(name.trim()) }

    override suspend fun renameCategory(id: Long, newName: String): Result<Unit> =
        write(newName) {
            if (dao.rename(id, newName.trim()) == 0) throw DataError.NotFound(id)
        }

    override suspend fun deleteCategory(id: Long): Result<Unit> =
        write(name = null) {
            if (dao.delete(id) == 0) throw DataError.NotFound(id)
        }

    /** Escritura + aviso a los observadores. Un nombre repetido se traduce a DuplicateCategory. */
    private suspend inline fun <T> write(name: String?, crossinline block: suspend () -> T): Result<T> =
        try {
            Result.success(block()).also { changes.notifyChanged() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val duplicate = name != null && "unique constraint failed" in e.message.orEmpty().lowercase()
            Result.failure(if (duplicate) DataError.DuplicateCategory(name!!.trim()) else e.toDataError())
        }
}
