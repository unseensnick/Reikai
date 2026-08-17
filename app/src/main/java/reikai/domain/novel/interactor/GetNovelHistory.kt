package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.model.NovelHistoryWithRelations

@Inject
class GetNovelHistory(
    private val repository: NovelHistoryRepository,
) {
    /** Empty category lists mean no constraint, matching the manga twin. */
    fun subscribe(
        query: String,
        includedCategories: List<Long> = emptyList(),
        excludedCategories: List<Long> = emptyList(),
    ): Flow<List<NovelHistoryWithRelations>> =
        repository.getNovelHistory(query, includedCategories, excludedCategories)

    suspend fun getLast(): NovelHistoryWithRelations? = repository.getLastNovelHistory()
}
