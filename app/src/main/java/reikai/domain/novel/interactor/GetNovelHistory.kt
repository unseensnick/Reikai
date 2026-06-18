package reikai.domain.novel.interactor

import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.model.NovelHistoryWithRelations

class GetNovelHistory(
    private val repository: NovelHistoryRepository,
) {
    fun subscribe(query: String): Flow<List<NovelHistoryWithRelations>> = repository.getNovelHistory(query)

    suspend fun getLast(): NovelHistoryWithRelations? = repository.getLastNovelHistory()
}
