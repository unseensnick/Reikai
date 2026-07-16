package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.novel.model.NovelWithChapterCount
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull

class NovelRepositoryImpl(
    private val database: Database,
) : NovelRepository {

    override suspend fun getAll(): List<Novel> =
        database.novelsQueries.findAll(::mapNovel).awaitAsList()

    override suspend fun getById(id: Long): Novel? =
        database.novelsQueries.findById(id, ::mapNovel).awaitAsOneOrNull()

    override suspend fun getByUrlAndSource(url: String, source: String): Novel? =
        database.novelsQueries.findByUrlAndSource(url, source, ::mapNovel).awaitAsOneOrNull()

    override suspend fun getFavorites(): List<Novel> =
        database.novelsQueries.findFavorites(::mapNovel).awaitAsList()

    override suspend fun getDuplicateLibraryNovel(id: Long, title: String): List<NovelWithChapterCount> =
        database.novelsQueries.getDuplicateLibraryNovel(id, title, ::mapNovelWithChapterCount).awaitAsList()

    override fun getLibraryNovelAsFlow(): Flow<List<LibraryNovel>> =
        database.novelLibraryViewQueries.novelLibrary(::mapLibraryNovel).subscribeToList()

    override fun getRecentNovelUpdatesAsFlow(after: Long, limit: Long): Flow<List<NovelUpdateWithRelations>> =
        database.novelUpdatesViewQueries.getRecentNovelUpdates(after, limit, ::mapNovelUpdate).subscribeToList()

    override fun getAllAsFlow(): Flow<List<Novel>> =
        database.novelsQueries.findAll(::mapNovel).subscribeToList()

    override fun getByUrlAndSourceAsFlow(url: String, source: String): Flow<Novel?> =
        database.novelsQueries.findByUrlAndSource(url, source, ::mapNovel).subscribeToOneOrNull()

    override suspend fun insertOrGet(novel: Novel): Novel? {
        getByUrlAndSource(novel.url, novel.source)?.let { return it }
        val id = insert(novel) ?: return null
        return getById(id)
    }

    override suspend fun insert(novel: Novel): Long? = try {
        database.transactionWithResult {
            database.novelsQueries.insert(
                source = novel.source,
                url = novel.url,
                title = novel.title,
                author = novel.author,
                artist = novel.artist,
                description = novel.description,
                genre = novel.genre,
                status = novel.status,
                thumbnailUrl = novel.thumbnailUrl,
                favorite = novel.favorite,
                lastUpdate = novel.lastUpdate,
                initialized = novel.initialized,
                chapterFlags = novel.chapterFlags,
                dateAdded = novel.dateAdded,
                updateStrategy = novel.updateStrategy,
                coverLastModified = novel.coverLastModified,
                totalPages = novel.totalPages,
                lastReadAt = novel.lastReadAt,
                notes = novel.notes,
                viewerFlags = novel.viewerFlags,
                version = novel.version,
            )
            database.novelsQueries.selectLastInsertedRowId().awaitAsOne()
        }
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to insert novel '${novel.url}' (source=${novel.source})" }
        null
    }

    override suspend fun update(novel: Novel, isSyncing: Boolean): Boolean = try {
        database.novelsQueries.update(
            source = novel.source,
            url = novel.url,
            title = novel.title,
            author = novel.author,
            artist = novel.artist,
            description = novel.description,
            genre = novel.genre,
            status = novel.status,
            thumbnailUrl = novel.thumbnailUrl,
            favorite = novel.favorite,
            lastUpdate = novel.lastUpdate,
            initialized = novel.initialized,
            chapterFlags = novel.chapterFlags,
            dateAdded = novel.dateAdded,
            updateStrategy = novel.updateStrategy,
            coverLastModified = novel.coverLastModified,
            totalPages = novel.totalPages,
            lastReadAt = novel.lastReadAt,
            notes = novel.notes,
            viewerFlags = novel.viewerFlags,
            version = novel.version,
            isSyncing = if (isSyncing) 1L else 0L,
            novelId = novel.id,
        )
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to update novel id=${novel.id}" }
        false
    }

    override suspend fun update(update: NovelUpdate): Boolean = try {
        database.novelsQueries.partialUpdate(
            source = update.source,
            url = update.url,
            title = update.title,
            author = update.author,
            artist = update.artist,
            description = update.description,
            status = update.status,
            thumbnailUrl = update.thumbnailUrl,
            favorite = update.favorite,
            lastUpdate = update.lastUpdate,
            initialized = update.initialized,
            chapterFlags = update.chapterFlags,
            dateAdded = update.dateAdded,
            coverLastModified = update.coverLastModified,
            totalPages = update.totalPages,
            lastReadAt = update.lastReadAt,
            notes = update.notes,
            viewerFlags = update.viewerFlags,
            id = update.id,
        )
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to partial-update novel id=${update.id}" }
        false
    }

    override suspend fun setLastReadAt(id: Long, at: Long): Boolean = try {
        database.novelsQueries.setLastReadAt(at, id)
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to set last_read_at on novel id=$id" }
        false
    }

    override suspend fun setCategories(novelId: Long, categoryIds: List<Long>) {
        database.transaction {
            database.novels_categoriesQueries.delete(novelId)
            categoryIds.forEach { categoryId ->
                database.novels_categoriesQueries.insert(novelId, categoryId)
            }
        }
    }
}
