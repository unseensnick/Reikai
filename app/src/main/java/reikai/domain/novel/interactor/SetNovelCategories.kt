package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.novel.NovelRepository

@Inject
class SetNovelCategories(
    private val novelRepository: NovelRepository,
) {
    suspend fun await(novelId: Long?, categories: List<Long>) {
        novelRepository.setCategories(novelId ?: return, categories)
    }
}
