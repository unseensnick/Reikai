package tachiyomi.data.updates

import app.cash.sqldelight.Query
import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.util.lang.toLong
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.repository.UpdatesRepository
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class UpdatesRepositoryImpl(
    private val database: Database,
) : UpdatesRepository {

    override suspend fun awaitWithRead(
        read: Boolean,
        after: Instant,
        limit: Long,
        seriesMerging: Boolean, // RK
    ): List<UpdatesWithRelations> {
        return getUpdatesByReadStatus(read = read, after = after, limit = limit, seriesMerging = seriesMerging) // RK
            .awaitAsList()
    }

    override fun subscribeAll(
        after: Instant,
        limit: Long,
        unread: Boolean?,
        started: Boolean?,
        bookmarked: Boolean?,
        hideExcludedScanlators: Boolean,
        includedCategories: List<Long>,
        excludedCategories: List<Long>,
        seriesMerging: Boolean, // RK
    ): Flow<List<UpdatesWithRelations>> {
        return database.updatesViewQueries
            .getRecentUpdatesWithFilters(
                after = after,
                limit = limit,
                read = unread?.let { !it },
                started = started?.toLong(),
                bookmarked = bookmarked,
                hideExcludedScanlators = hideExcludedScanlators.toLong(),
                seriesMerging = seriesMerging.toLong(), // RK
                includedEmpty = includedCategories.isEmpty(),
                includedCategories = includedCategories,
                excludedEmpty = excludedCategories.isEmpty(), // RK: reordered to the query's parameter order
                excludedCategories = excludedCategories,
                mapper = ::mapUpdatesWithRelations,
            )
            .subscribeToList()
    }

    override fun subscribeWithRead(
        read: Boolean,
        after: Instant,
        limit: Long,
        seriesMerging: Boolean, // RK
    ): Flow<List<UpdatesWithRelations>> {
        return getUpdatesByReadStatus(read = read, after = after, limit = limit, seriesMerging = seriesMerging) // RK
            .subscribeToList()
    }

    // RK: seriesMerging
    private fun getUpdatesByReadStatus(
        read: Boolean,
        after: Instant,
        limit: Long,
        seriesMerging: Boolean,
    ): Query<UpdatesWithRelations> {
        return if (read) {
            database.updatesViewQueries.getReadUpdates(
                after = after,
                seriesMerging = seriesMerging.toLong(), // RK
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
        } else {
            database.updatesViewQueries.getUnreadUpdates(
                after = after,
                seriesMerging = seriesMerging.toLong(), // RK
                limit = limit,
                mapper = ::mapUpdatesWithRelations,
            )
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapUpdatesWithRelations(
        mangaId: Long,
        mangaTitle: String,
        chapterId: Long,
        chapterName: String,
        scanlator: String?,
        chapterUrl: String,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Int,
        pageCount: Int, // RK: for the progress line's how-far-through
        sourceId: Long,
        favorite: Boolean,
        thumbnailUrl: String?,
        coverLastModified: Instant?,
        dateUpload: Instant?,
        dateFetch: Instant,
        excludedScanlator: String?,
    ): UpdatesWithRelations = UpdatesWithRelations(
        mangaId = mangaId,
        mangaTitle = mangaTitle,
        chapterId = chapterId,
        chapterName = chapterName,
        scanlator = scanlator,
        chapterUrl = chapterUrl,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        pageCount = pageCount, // RK
        sourceId = sourceId,
        dateFetch = dateFetch,
        coverData = MangaCover(
            mangaId = mangaId,
            sourceId = sourceId,
            isMangaFavorite = favorite,
            url = thumbnailUrl,
            lastModified = coverLastModified,
        ),
    )
}
