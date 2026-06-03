package yokai.data.novel

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelInCategory
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
                editedFlags = novel.editedFlags.toLong(),
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
                editedFlags = novel.editedFlags.toLong(),
                novelId = novelId,
            )
        }
        true
    } catch (e: Exception) {
        Logger.e(e) { "Failed to update novel id=${novel.id}" }
        false
    }

    override suspend fun getLibraryNovel(): List<LibraryNovel> =
        handler.awaitList { library_novel_viewQueries.findAll(LibraryNovel::mapper) }

    override fun getLibraryNovelAsFlow(): Flow<List<LibraryNovel>> =
        handler.subscribeToList { library_novel_viewQueries.findAll(LibraryNovel::mapper) }

    override suspend fun setCategories(novelId: Long, categoryIds: List<Long>) =
        handler.await(inTransaction = true) {
            novels_categoriesQueries.delete(novelId)
            categoryIds.forEach { id ->
                novels_categoriesQueries.insert(novelId, id)
            }
        }

    override suspend fun setMultipleNovelCategories(novelIds: List<Long>, novelCategories: List<NovelInCategory>) =
        handler.await(inTransaction = true) {
            novels_categoriesQueries.deleteBulk(novelIds)
            novelCategories.forEach {
                novels_categoriesQueries.insert(it.novel_id, it.category_id.toLong())
            }
        }
}
