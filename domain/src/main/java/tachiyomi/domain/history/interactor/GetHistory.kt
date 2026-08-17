package tachiyomi.domain.history.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.history.repository.HistoryRepository

@Inject
class GetHistory(
    private val repository: HistoryRepository,
) {

    suspend fun await(mangaId: Long): List<History> {
        return repository.getHistoryByMangaId(mangaId)
    }

    // RK --> the category id lists are Reikai's recents filter; empty means no constraint.
    fun subscribe(
        query: String,
        includedCategories: List<Long> = emptyList(),
        excludedCategories: List<Long> = emptyList(),
    ): Flow<List<HistoryWithRelations>> {
        return repository.getHistory(query, includedCategories, excludedCategories)
    }

    /** The most recent read, deliberately unfiltered: a resume must not skip what the feed hides. */
    suspend fun getLast(): HistoryWithRelations? {
        return repository.getLastHistory()
    }
    // RK <--
}
