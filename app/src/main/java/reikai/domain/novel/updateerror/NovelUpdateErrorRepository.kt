package reikai.domain.novel.updateerror

import kotlinx.coroutines.flow.Flow

interface NovelUpdateErrorRepository {
    fun subscribeAll(): Flow<List<NovelUpdateError>>
    suspend fun upsert(novelId: Long, message: String)
    suspend fun deleteByErrorIds(errorIds: List<Long>)
    suspend fun deleteByNovelIds(novelIds: List<Long>)
    suspend fun deleteAll()
    suspend fun deleteNonFavorites()
}
