package reikai.domain.novel.interactor

import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.model.NovelCategory

class InsertNovelCategories(
    private val novelCategoryRepository: NovelCategoryRepository,
) {
    suspend fun await(categories: List<NovelCategory>) = novelCategoryRepository.insertBulk(categories)
    suspend fun awaitOne(category: NovelCategory) = novelCategoryRepository.insert(category)
}
