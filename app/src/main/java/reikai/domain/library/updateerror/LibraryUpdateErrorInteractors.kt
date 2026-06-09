package reikai.domain.library.updateerror

import kotlinx.coroutines.flow.Flow

class GetLibraryUpdateErrors(
    private val repository: LibraryUpdateErrorRepository,
) {
    fun subscribeAll(): Flow<List<LibraryUpdateError>> = repository.subscribeAll()
    fun count(): Flow<Long> = repository.countAsFlow()
}

class UpsertLibraryUpdateError(
    private val repository: LibraryUpdateErrorRepository,
) {
    suspend fun await(mangaId: Long, message: String) = repository.upsert(mangaId, message)
}

class DeleteLibraryUpdateErrors(
    private val repository: LibraryUpdateErrorRepository,
) {
    suspend fun byErrorIds(errorIds: List<Long>) = repository.deleteByErrorIds(errorIds)
    suspend fun byMangaIds(mangaIds: List<Long>) = repository.deleteByMangaIds(mangaIds)
    suspend fun all() = repository.deleteAll()
    suspend fun nonFavorites() = repository.deleteNonFavorites()
}
