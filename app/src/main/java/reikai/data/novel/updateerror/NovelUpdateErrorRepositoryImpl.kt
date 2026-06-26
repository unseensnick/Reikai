package reikai.data.novel.updateerror

import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.updateerror.NovelUpdateError
import reikai.domain.novel.updateerror.NovelUpdateErrorRepository
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList

class NovelUpdateErrorRepositoryImpl(
    private val database: Database,
) : NovelUpdateErrorRepository {

    override fun subscribeAll(): Flow<List<NovelUpdateError>> {
        return database.novel_update_error_viewQueries.errors(::mapError).subscribeToList()
    }

    override suspend fun upsert(novelId: Long, message: String) {
        database.novel_update_errorsQueries.upsert(novelId, message)
    }

    override suspend fun deleteByErrorIds(errorIds: List<Long>) {
        database.novel_update_errorsQueries.deleteByErrorIds(errorIds)
    }

    override suspend fun deleteByNovelIds(novelIds: List<Long>) {
        database.novel_update_errorsQueries.deleteByNovelIds(novelIds)
    }

    override suspend fun deleteAll() {
        database.novel_update_errorsQueries.deleteAll()
    }

    override suspend fun deleteNonFavorites() {
        database.novel_update_errorsQueries.deleteNonFavorites()
    }

    private fun mapError(
        novelId: Long,
        novelTitle: String,
        source: String,
        url: String,
        thumbnailUrl: String?,
        coverLastModified: Long,
        errorId: Long,
        message: String,
        lastUpdate: Long,
    ): NovelUpdateError = NovelUpdateError(
        errorId = errorId,
        novelId = novelId,
        novelTitle = novelTitle,
        source = source,
        novelUrl = url,
        thumbnailUrl = thumbnailUrl,
        coverLastModified = coverLastModified,
        message = message,
        lastUpdate = lastUpdate,
    )
}
