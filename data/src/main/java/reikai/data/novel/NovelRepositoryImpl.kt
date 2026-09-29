package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
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

    override suspend fun getReadNovelsNotInLibrary(): List<Novel> =
        database.novelsQueries.getReadNovelsNotInLibrary(::mapNovel).awaitAsList()

    override fun getSourcesWithNonLibraryNovelAsFlow(): Flow<List<Pair<String, Long>>> =
        database.novelsQueries.getSourcesWithNonLibraryNovel { source, count -> source to count }
            .subscribeToList()

    override suspend fun deleteNonLibraryNovels(sources: List<String>, keepReadNovels: Boolean) {
        database.novelsQueries.deleteNonLibraryNovel(sources, if (keepReadNovels) 1L else 0L)
    }

    override suspend fun getDuplicateLibraryNovel(id: Long, title: String): List<NovelWithChapterCount> =
        database.novelsQueries.getDuplicateLibraryNovel(id, title, ::mapNovelWithChapterCount).awaitAsList()

    override fun getLibraryNovelAsFlow(): Flow<List<LibraryNovel>> =
        database.novelLibraryViewQueries.novelLibrary(::mapLibraryNovel).subscribeToList()

    override fun getFilteredNovelUpdatesAsFlow(
        after: Long,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
    ): Flow<List<NovelUpdateWithRelations>> =
        database.novelUpdatesViewQueries.getRecentNovelUpdatesWithFilters(
            after = after,
            read = unread?.let { !it },
            started = started?.let { if (it) 1L else 0L },
            bookmarked = bookmarked,
            includedEmpty = includedCategories.isEmpty(),
            includedCategories = includedCategories,
            excludedEmpty = excludedCategories.isEmpty(),
            excludedCategories = excludedCategories,
            limit = limit,
            mapper = ::mapNovelUpdate,
        ).subscribeToList()

    override fun getAllAsFlow(): Flow<List<Novel>> =
        database.novelsQueries.findAll(::mapNovel).subscribeToList()

    override fun getByUrlAndSourceAsFlow(url: String, source: String): Flow<Novel?> =
        database.novelsQueries.findByUrlAndSource(url, source, ::mapNovel).subscribeToOneOrNull()

    override suspend fun insertOrGet(novel: Novel): Novel? {
        getByUrlAndSource(novel.url, novel.source)?.let { return it }
        // A writer that stored the same novel since the read above wins, and this insert stores nothing
        insert(novel)
        return getByUrlAndSource(novel.url, novel.source)
    }

    override suspend fun insert(novel: Novel): Long? = try {
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
            favoriteAt = novel.favoriteAt,
            lastUpdate = novel.lastUpdate,
            initialized = novel.initialized,
            chapterFlags = novel.chapterFlags,
            updateStrategy = novel.updateStrategy,
            coverLastModified = novel.coverLastModified,
            totalPages = novel.totalPages,
            notes = novel.notes,
            viewerFlags = novel.viewerFlags,
        ).awaitAsOneOrNull()
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to insert novel '${novel.url}' (source=${novel.source})" }
        null
    }

    override suspend fun update(novel: Novel): Boolean = try {
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
            favoriteAt = novel.favoriteAt,
            lastUpdate = novel.lastUpdate,
            initialized = novel.initialized,
            chapterFlags = novel.chapterFlags,
            updateStrategy = novel.updateStrategy,
            coverLastModified = novel.coverLastModified,
            totalPages = novel.totalPages,
            notes = novel.notes,
            viewerFlags = novel.viewerFlags,
            novelId = novel.id,
        )
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to update novel id=${novel.id}" }
        false
    }

    override suspend fun update(update: NovelUpdate): Boolean = try {
        database.transaction { write(update) }
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to partial-update novel id=${update.id}" }
        false
    }

    override suspend fun updateAll(updates: List<NovelUpdate>): Boolean = try {
        database.transaction { updates.forEach { write(it) } }
        true
    } catch (e: Exception) {
        logcat(LogPriority.ERROR, e) { "Failed to batch-update ${updates.size} novels" }
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

    private suspend fun write(update: NovelUpdate) = with(update) {
        database.novelsQueries.partialUpdate(
            source = source,
            url = url,
            title = title,
            author = author,
            artist = artist,
            description = description,
            status = status,
            thumbnailUrl = thumbnailUrl,
            favoriteAtSet = isSet(::favoriteAt),
            favoriteAt = favoriteAt,
            lastUpdate = lastUpdate,
            initialized = initialized,
            chapterFlags = chapterFlags,
            coverLastModified = coverLastModified,
            totalPages = totalPages,
            notes = notes,
            viewerFlags = viewerFlags,
            nextUpdate = nextUpdate,
            calculateInterval = fetchInterval?.toLong(),
            id = id,
        )
        genre?.let { database.novelsQueries.setGenre(genre = it, id = id) }
    }
}
