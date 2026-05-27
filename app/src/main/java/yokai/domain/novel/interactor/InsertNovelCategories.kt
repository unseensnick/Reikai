package yokai.domain.novel.interactor

import eu.kanade.tachiyomi.data.database.models.NovelCategory
import yokai.domain.novel.NovelCategoryRepository

class InsertNovelCategories(
    private val novelCategoryRepository: NovelCategoryRepository,
) {
    suspend fun await(categories: List<NovelCategory>) = novelCategoryRepository.insertBulk(categories)
    suspend fun awaitOne(category: NovelCategory) = novelCategoryRepository.insert(category)
}
