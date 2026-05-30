package yokai.domain.library.update.error.interactor

import yokai.domain.library.update.error.LibraryUpdateErrorRepository

class UpsertLibraryUpdateError(
    private val repository: LibraryUpdateErrorRepository,
) {
    suspend fun await(mangaId: Long, message: String) = repository.upsert(mangaId, message)
}
