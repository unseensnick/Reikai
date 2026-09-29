package reikai.data.dedupe

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.data.toContentType
import reikai.domain.dedupe.MergedDuplicate
import reikai.domain.dedupe.MergedDuplicateChapter
import reikai.domain.dedupe.MergedDuplicateRepository
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
}
