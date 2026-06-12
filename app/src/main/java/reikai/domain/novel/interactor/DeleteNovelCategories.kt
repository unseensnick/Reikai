package reikai.domain.novel.interactor

import reikai.domain.novel.NovelCategoryRepository

class DeleteNovelCategories(
    private val novelCategoryRepository: NovelCategoryRepository,
) {
    suspend fun awaitOne(id: Long) = novelCategoryRepository.delete(id)
    suspend fun awaitAll(ids: List<Long>) = ids.forEach { awaitOne(it) }
}
