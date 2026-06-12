package reikai.domain.novel.interactor

import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.model.NovelCategoryUpdate

class ReorderNovelCategories(
    private val novelCategoryRepository: NovelCategoryRepository,
) {
    suspend fun await(updates: List<NovelCategoryUpdate>) = novelCategoryRepository.updateAll(updates)
    suspend fun awaitOne(update: NovelCategoryUpdate) = novelCategoryRepository.update(update)
}
