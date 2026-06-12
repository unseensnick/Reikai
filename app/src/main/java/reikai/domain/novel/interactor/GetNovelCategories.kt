package reikai.domain.novel.interactor

import reikai.domain.novel.NovelCategoryRepository

class GetNovelCategories(
    private val novelCategoryRepository: NovelCategoryRepository,
) {
    suspend fun await() = novelCategoryRepository.getAll()
    suspend fun awaitByNovelId(novelId: Long?) =
        novelId?.let { novelCategoryRepository.getAllByNovelId(it) }.orEmpty()
    fun subscribe() = novelCategoryRepository.getAllAsFlow()
}
