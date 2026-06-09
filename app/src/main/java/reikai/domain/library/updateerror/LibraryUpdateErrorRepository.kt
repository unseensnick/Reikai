package reikai.domain.library.updateerror

import kotlinx.coroutines.flow.Flow

interface LibraryUpdateErrorRepository {
    fun subscribeAll(): Flow<List<LibraryUpdateError>>
    fun countAsFlow(): Flow<Long>
    suspend fun upsert(mangaId: Long, message: String)
    suspend fun deleteByErrorIds(errorIds: List<Long>)
    suspend fun deleteByMangaIds(mangaIds: List<Long>)
    suspend fun deleteAll()
    suspend fun deleteNonFavorites()
}
