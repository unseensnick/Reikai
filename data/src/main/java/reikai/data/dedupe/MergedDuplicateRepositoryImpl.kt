package reikai.data.dedupe

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateChapter
import reikai.domain.dedupe.MergedDuplicateRepository
import reikai.domain.library.ContentType
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MergedDuplicateRepositoryImpl(
    private val database: Database,
) : MergedDuplicateRepository {

    private val queries = database.dedupe_merged_idsQueries

    override suspend fun getAll(): List<MergedDuplicate> =
        queries.getAll { contentType, discardedId, survivorId, discardedTitle ->
            MergedDuplicate(contentType.toContentType(), discardedId, survivorId, discardedTitle)
        }.awaitAsList()

    override suspend fun getChapters(): List<MergedDuplicateChapter> =
        queries.getAllChapters { contentType, discardedId, survivorId ->
            MergedDuplicateChapter(contentType.toContentType(), discardedId, survivorId)
        }.awaitAsList()

    override suspend fun clear() {
        database.transaction {
            queries.deleteAll()
            queries.deleteAllChapters()
        }
    }

    private companion object {
        // The values 50.sqm and 51.sqm write, the same as merge_group's
        fun Long.toContentType(): ContentType = when (this) {
            0L -> ContentType.MANGA
            1L -> ContentType.NOVELS
            else -> error("Unknown dedupe_merged_ids content_type $this")
        }
    }
}
