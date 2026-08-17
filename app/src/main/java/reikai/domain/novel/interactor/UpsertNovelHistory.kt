package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.model.NovelHistoryUpdate

@Inject
class UpsertNovelHistory(
    private val repository: NovelHistoryRepository,
) {
    suspend fun await(update: NovelHistoryUpdate) = repository.upsertNovelHistory(update)
}
