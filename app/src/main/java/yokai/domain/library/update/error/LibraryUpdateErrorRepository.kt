package yokai.domain.library.update.error

import kotlinx.coroutines.flow.Flow
import yokai.domain.library.update.error.model.LibraryUpdateErrorWithRelations

interface LibraryUpdateErrorRepository {
    fun subscribeAll(): Flow<List<LibraryUpdateErrorWithRelations>>
    fun countAsFlow(): Flow<Int>
    suspend fun upsert(mangaId: Long, message: String)
    suspend fun deleteByErrorIds(ids: List<Long>)
    suspend fun deleteByMangaIds(mangaIds: List<Long>)
    suspend fun cleanUnrelevant()
}
