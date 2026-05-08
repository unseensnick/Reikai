package yokai.domain.category.interactor

import eu.kanade.tachiyomi.data.database.models.Category
import yokai.domain.category.CategoryRepository
import yokai.domain.category.models.CategoryUpdate

class DeleteCategories(
    private val categoryRepository: CategoryRepository,
) {
//    suspend fun await(updates: List<Int>) =
    suspend fun awaitOne(id: Long) = categoryRepository.delete(id)
    suspend fun awaitAll(ids: List<Long>) = ids.forEach { awaitOne(it) }
}
