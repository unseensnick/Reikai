package reikai.data.novel

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import logcat.LogPriority
import reikai.domain.novel.FavoritedNovels
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.NovelUpdateWithRelations
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.domain.source.healedCover
import reikai.domain.source.keptCover
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

    override fun getFavoritesBySourceAsFlow(source: String): Flow<List<Novel>> =
        database.novelsQueries.findFavoritesBySource(source, ::mapNovel).subscribeToList()

    override fun getSourcesWithLibraryNovelAsFlow(): Flow<List<Pair<String, Long>>> =
        database.novelsQueries.getSourcesWithLibraryNovel { source, count -> source to count }
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

    // Every novels-table write re-runs the query, browsed rows included; distinct keeps those from reaching a list.
    override fun getFavoritedKeysAsFlow(): Flow<FavoritedNovels> =
        database.novelsQueries.findFavorites(::mapNovel).subscribeToList()
            .map(FavoritedNovels::of)
            .distinctUntilChanged()

    override fun getByUrlAndSourceAsFlow(url: String, source: String): Flow<Novel?> =
        database.novelsQueries.findByUrlAndSource(url, source, ::mapNovel).subscribeToOneOrNull()
            .distinctUntilChanged()

    override suspend fun insertOrGet(novel: Novel): Novel? {
        val listed = novel.copy(thumbnailUrl = keptCover(null, novel.thumbnailUrl))
        val stored = getByUrlAndSource(listed.url, listed.source) ?: run {
            // A writer that stored the same novel since the read above wins, and this insert stores nothing
            insert(listed)
            getByUrlAndSource(listed.url, listed.source)
        } ?: return null
        val cover = healedCover(stored.thumbnailUrl, listed.thumbnailUrl) ?: return stored
        val healed = update(NovelUpdate(stored.id) { thumbnailUrl = cover })
        return if (healed) stored.copy(thumbnailUrl = cover) else stored
    }

    // A RETURNING insert announces itself before the device driver runs it; a transaction holds that
    // notice until the commit, so watchers re-read a row that exists
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
        }
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
            authorSet = isSet(::author),
            author = author,
            artistSet = isSet(::artist),
            artist = artist,
            descriptionSet = isSet(::description),
            description = description,
            status = status,
            thumbnailUrlSet = isSet(::thumbnailUrl),
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
        if (isSet(::genre)) database.novelsQueries.setGenre(genre = genre, id = id)
    }
}
