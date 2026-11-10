package reikai.data.local

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import reikai.data.toContentType
import reikai.data.toDbValue
import reikai.domain.library.ContentType
import reikai.domain.local.LocalFileJournal
import reikai.domain.local.LocalFileJournalEntry
import reikai.domain.local.LocalFileJournalPlan
import tachiyomi.data.Database

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocalFileJournalRepositoryImpl(
    private val database: Database,
) : LocalFileJournal {

    private val queries = database.local_file_journalQueries

    // Its own, so a plan written by one build reads back in the next whatever the app's Json is set to.
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun insert(type: ContentType, plan: LocalFileJournalPlan): Long =
        queries.insert(type.toDbValue(), json.encodeToString(plan)).awaitAsOne()

    override suspend fun update(id: Long, plan: LocalFileJournalPlan) {
        queries.update(json.encodeToString(plan), id)
    }

    override suspend fun delete(id: Long) {
        queries.delete(id)
    }

    override suspend fun all(): List<LocalFileJournalEntry> = queries.getAll().awaitAsList().map {
        LocalFileJournalEntry(it._id, it.content_type.toContentType(), json.decodeFromString(it.plan))
    }
}
