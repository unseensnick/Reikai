package yokai.domain.novel.interactor

import yokai.domain.novel.NovelCategoryRepository
import yokai.domain.novel.models.NovelCategoryUpdate

class ReorderNovelCategories(
    private val novelCategoryRepository: NovelCategoryRepository,
) {
    suspend fun await(updates: List<NovelCategoryUpdate>) = novelCategoryRepository.updateAll(updates)
    suspend fun awaitOne(update: NovelCategoryUpdate) = novelCategoryRepository.update(update)
}
