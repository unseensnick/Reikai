package yokai.data.novel

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import yokai.data.DatabaseHandler
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.models.Novel

class NovelRepositoryImpl(private val handler: DatabaseHandler) : NovelRepository {

    override suspend fun getAll(): List<Novel> =
        handler.awaitList { novelsQueries.findAll(::novelMapper) }

    override suspend fun getById(id: Long): Novel? =
        handler.awaitOneOrNull { novelsQueries.findById(id, ::novelMapper) }

    override suspend fun getByUrlAndSource(url: String, source: String): Novel? =
        handler.awaitFirstOrNull { novelsQueries.findByUrlAndSource(url, source, ::novelMapper) }

    override suspend fun getFavorites(): List<Novel> =
        handler.awaitList { novelsQueries.findFavorites(::novelMapper) }

    override fun getAllAsFlow(): Flow<List<Novel>> =
        handler.subscribeToList { novelsQueries.findAll(::novelMapper) }

    override fun getByUrlAndSourceAsFlow(url: String, source: String): Flow<Novel?> =
        handler.subscribeToFirstOrNull { novelsQueries.findByUrlAndSource(url, source, ::novelMapper) }

    override suspend fun insert(novel: Novel): Long? = try {
        handler.awaitOneOrNullExecutable(inTransaction = true) {
            novelsQueries.insert(
                source = novel.source,
                url = novel.url,
                title = novel.title,
                author = novel.author,
                artist = novel.artist,
                description = novel.description,
                genre = novel.genres?.joinToString(),
                status = novel.status.toLong(),
                thumbnailUrl = novel.thumbnailUrl,
                favorite = novel.favorite,
                lastUpdate = novel.lastUpdate,
                initialized = novel.initialized,
                chapterFlags = novel.chapterFlags.toLong(),
                dateAdded = novel.dateAdded,
                updateStrategy = novel.updateStrategy.toLong(),
                coverLastModified = novel.coverLastModified,
                totalPages = novel.totalPages.toLong(),
                lastReadAt = novel.lastReadAt,
            )
            novelsQueries.selectLastInsertedRowId()
        }
    } catch (e: Exception) {
        Logger.e(e) { "Failed to insert novel '${novel.url}' (source=${novel.source})" }
        null
    }

    override suspend fun update(novel: Novel): Boolean = try {
        val novelId = novel.id ?: error("update() called with null id; insert first")
        handler.await {
            novelsQueries.update(
                source = novel.source,
                url = novel.url,
                title = novel.title,
                author = novel.author,
                artist = novel.artist,
                description = novel.description,
                genre = novel.genres?.joinToString(),
                status = novel.status.toLong(),
                thumbnailUrl = novel.thumbnailUrl,
                favorite = novel.favorite,
                lastUpdate = novel.lastUpdate,
                initialized = novel.initialized,
                chapterFlags = novel.chapterFlags.toLong(),
                dateAdded = novel.dateAdded,
                updateStrategy = novel.updateStrategy.toLong(),
                coverLastModified = novel.coverLastModified,
                totalPages = novel.totalPages.toLong(),
                lastReadAt = novel.lastReadAt,
                novelId = novelId,
            )
        }
        true
    } catch (e: Exception) {
        Logger.e(e) { "Failed to update novel id=${novel.id}" }
        false
    }
}
