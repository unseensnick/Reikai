package yokai.domain.novel.interactor

import yokai.domain.novel.NovelCategoryRepository

class GetNovelCategories(
    private val novelCategoryRepository: NovelCategoryRepository,
) {
    suspend fun await() = novelCategoryRepository.getAll()
    suspend fun awaitByNovelId(novelId: Long?) =
        novelId?.let { novelCategoryRepository.getAllByNovelId(it) }.orEmpty()
    fun subscribe() = novelCategoryRepository.getAllAsFlow()
}
