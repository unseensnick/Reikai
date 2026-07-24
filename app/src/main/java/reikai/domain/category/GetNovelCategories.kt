package reikai.domain.category

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Reads novel-visible categories (content_type 2 plus the universal row 0) over the shared
 * [CategoryRepository], the novel twin of Mihon's [tachiyomi.domain.category.interactor.GetCategories].
 * Returns the shared [Category] type so novel screens flow through the same category UI/views as manga.
 */
class GetNovelCategories(
    private val categoryRepository: CategoryRepository,
) {
    suspend fun await(): List<Category> = categoryRepository.getAll(CategoryContentType.NOVEL)

    suspend fun awaitByNovelId(novelId: Long?): List<Category> =
        novelId?.let { categoryRepository.getCategoriesByNovelId(it) }.orEmpty()

    fun subscribe(): Flow<List<Category>> = categoryRepository.getAllAsFlow(CategoryContentType.NOVEL)
}
