package reikai.domain.novel.interactor

import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.model.NovelHistoryUpdate

class UpsertNovelHistory(
    private val repository: NovelHistoryRepository,
) {
    suspend fun await(update: NovelHistoryUpdate) = repository.upsertNovelHistory(update)
}
