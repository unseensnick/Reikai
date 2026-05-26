package yokai.domain.novel.interactor

import eu.kanade.tachiyomi.data.database.models.NovelInCategory
import yokai.domain.novel.NovelRepository

class SetNovelCategories(
    private val novelRepository: NovelRepository,
) {
    suspend fun await(novelId: Long?, categories: List<Long>) {
        novelRepository.setCategories(novelId ?: return, categories)
    }
    suspend fun awaitAll(novelIds: List<Long>, novelCategories: List<NovelInCategory>) =
        novelRepository.setMultipleNovelCategories(novelIds, novelCategories)
}
