package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

@Inject
class RemoveHistory(
    private val repository: HistoryRepository,
) {

    suspend fun awaitAll(): Boolean {
        return repository.deleteAllHistory()
    }

    suspend fun await(history: HistoryWithRelations) {
        repository.resetHistory(history.id)
    }

    suspend fun await(mangaId: Long) {
        repository.resetHistoryByMangaId(mangaId)
    }

    // RK: batch reset for EHentai gallery-version reconciliation.
    suspend fun await(historyIds: List<Long>) {
        repository.resetHistory(historyIds)
    }
}
