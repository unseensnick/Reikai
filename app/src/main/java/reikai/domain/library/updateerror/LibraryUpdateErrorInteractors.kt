package reikai.domain.library.updateerror

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

@Inject
class GetLibraryUpdateErrors(
    private val repository: LibraryUpdateErrorRepository,
) {
    fun subscribeAll(): Flow<List<LibraryUpdateError>> = repository.subscribeAll()
    fun count(): Flow<Long> = repository.countAsFlow()
}

@Inject
class UpsertLibraryUpdateError(
    private val repository: LibraryUpdateErrorRepository,
) {
    suspend fun await(mangaId: Long, message: String) = repository.upsert(mangaId, message)
}

@Inject
class DeleteLibraryUpdateErrors(
    private val repository: LibraryUpdateErrorRepository,
) {
    suspend fun byErrorIds(errorIds: List<Long>) = repository.deleteByErrorIds(errorIds)
    suspend fun byMangaIds(mangaIds: List<Long>) = repository.deleteByMangaIds(mangaIds)
    suspend fun all() = repository.deleteAll()
    suspend fun nonFavorites() = repository.deleteNonFavorites()
}
