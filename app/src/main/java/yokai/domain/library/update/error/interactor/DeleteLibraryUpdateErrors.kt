package yokai.domain.library.update.error.interactor

import yokai.domain.library.update.error.LibraryUpdateErrorRepository

class DeleteLibraryUpdateErrors(
    private val repository: LibraryUpdateErrorRepository,
) {
    suspend fun awaitByErrorIds(ids: List<Long>) = repository.deleteByErrorIds(ids)
    suspend fun awaitByMangaIds(mangaIds: List<Long>) = repository.deleteByMangaIds(mangaIds)
    suspend fun cleanUnrelevant() = repository.cleanUnrelevant()
}
