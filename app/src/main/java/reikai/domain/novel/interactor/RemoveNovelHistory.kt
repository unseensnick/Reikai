package reikai.domain.novel.interactor

import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.model.NovelHistoryWithRelations

class RemoveNovelHistory(
    private val repository: NovelHistoryRepository,
) {
    suspend fun awaitAll() = repository.deleteAllNovelHistory()

    suspend fun await(history: NovelHistoryWithRelations) = repository.resetNovelHistory(history.id)

    suspend fun await(novelId: Long) = repository.resetNovelHistoryByNovelId(novelId)
}
