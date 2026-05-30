package yokai.data.library.update.error

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import yokai.data.DatabaseHandler
import yokai.domain.library.update.error.LibraryUpdateErrorRepository
import yokai.domain.library.update.error.model.LibraryUpdateErrorWithRelations

class LibraryUpdateErrorRepositoryImpl(private val handler: DatabaseHandler) : LibraryUpdateErrorRepository {

    override fun subscribeAll(): Flow<List<LibraryUpdateErrorWithRelations>> =
        handler.subscribeToList { library_update_error_viewQueries.errors(::mapWithRelations) }

    override fun countAsFlow(): Flow<Int> =
        handler.subscribeToOne { library_update_errorsQueries.count() }.map { it.toInt() }

    override suspend fun upsert(mangaId: Long, message: String) {
        handler.await { library_update_errorsQueries.upsert(mangaId, message) }
    }

    override suspend fun deleteByErrorIds(ids: List<Long>) {
        handler.await { library_update_errorsQueries.deleteByErrorIds(ids) }
    }

    override suspend fun deleteByMangaIds(mangaIds: List<Long>) {
        handler.await { library_update_errorsQueries.deleteByMangaIds(mangaIds) }
    }

    override suspend fun cleanUnrelevant() {
        handler.await { library_update_errorsQueries.cleanUnrelevant() }
    }

    private fun mapWithRelations(
        mangaId: Long,
        mangaTitle: String,
        source: Long,
        thumbnailUrl: String?,
        coverLastModified: Long,
        errorId: Long,
        message: String,
        lastUpdate: Long,
    ): LibraryUpdateErrorWithRelations = LibraryUpdateErrorWithRelations(
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        mangaSource = source,
        thumbnailUrl = thumbnailUrl,
        coverLastModified = coverLastModified,
        errorId = errorId,
        message = message,
        lastUpdate = lastUpdate,
    )
}
