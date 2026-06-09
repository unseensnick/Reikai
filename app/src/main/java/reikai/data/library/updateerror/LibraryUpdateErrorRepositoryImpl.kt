package reikai.data.library.updateerror

import kotlinx.coroutines.flow.Flow
import reikai.domain.library.updateerror.LibraryUpdateError
import reikai.domain.library.updateerror.LibraryUpdateErrorRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOne

class LibraryUpdateErrorRepositoryImpl(
    private val database: Database,
) : LibraryUpdateErrorRepository {

    override fun subscribeAll(): Flow<List<LibraryUpdateError>> {
        return database.library_update_error_viewQueries.errors(::mapError).subscribeToList()
    }

    override fun countAsFlow(): Flow<Long> {
        return database.library_update_errorsQueries.count().subscribeToOne()
    }

    override suspend fun upsert(mangaId: Long, message: String) {
        database.library_update_errorsQueries.upsert(mangaId, message)
    }

    override suspend fun deleteByErrorIds(errorIds: List<Long>) {
        database.library_update_errorsQueries.deleteByErrorIds(errorIds)
    }

    override suspend fun deleteByMangaIds(mangaIds: List<Long>) {
        database.library_update_errorsQueries.deleteByMangaIds(mangaIds)
    }

    override suspend fun deleteAll() {
        database.library_update_errorsQueries.deleteAll()
    }

    override suspend fun deleteNonFavorites() {
        database.library_update_errorsQueries.deleteNonFavorites()
    }

    private fun mapError(
        mangaId: Long,
        mangaTitle: String,
        source: Long,
        thumbnailUrl: String?,
        coverLastModified: Long,
        errorId: Long,
        message: String,
        lastUpdate: Long,
    ): LibraryUpdateError = LibraryUpdateError(
        errorId = errorId,
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        sourceId = source,
        thumbnailUrl = thumbnailUrl,
        coverLastModified = coverLastModified,
        message = message,
        lastUpdate = lastUpdate,
    )
}
