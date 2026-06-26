package reikai.domain.novel.updateerror

import kotlinx.coroutines.flow.Flow

class GetNovelUpdateErrors(
    private val repository: NovelUpdateErrorRepository,
) {
    fun subscribeAll(): Flow<List<NovelUpdateError>> = repository.subscribeAll()
}

class UpsertNovelUpdateError(
    private val repository: NovelUpdateErrorRepository,
) {
    suspend fun await(novelId: Long, message: String) = repository.upsert(novelId, message)
}

class DeleteNovelUpdateErrors(
    private val repository: NovelUpdateErrorRepository,
) {
    suspend fun byErrorIds(errorIds: List<Long>) = repository.deleteByErrorIds(errorIds)
    suspend fun byNovelIds(novelIds: List<Long>) = repository.deleteByNovelIds(novelIds)
    suspend fun all() = repository.deleteAll()
    suspend fun nonFavorites() = repository.deleteNonFavorites()
}
