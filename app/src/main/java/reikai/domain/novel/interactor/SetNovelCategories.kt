package reikai.domain.novel.interactor

import reikai.domain.novel.NovelRepository

class SetNovelCategories(
    private val novelRepository: NovelRepository,
) {
    suspend fun await(novelId: Long?, categories: List<Long>) {
        novelRepository.setCategories(novelId ?: return, categories)
    }
}
