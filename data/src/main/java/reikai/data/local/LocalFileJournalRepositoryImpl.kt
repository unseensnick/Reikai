package reikai.data.local

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import reikai.data.toContentType
import reikai.data.toDbValue
import reikai.domain.library.ContentType
import reikai.domain.local.LocalFileJournal
import reikai.domain.local.LocalFileJournalEntry
import reikai.domain.local.LocalFileJournalPlan
import tachiyomi.core.common.util.system.logcat
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

    /**
     * Every row this build can read. One it cannot, a plan a newer build wrote before a downgrade, is kept for that
     * build and passed over here, so it cannot stop the rest being replayed.
     */
    override suspend fun all(): List<LocalFileJournalEntry> = queries.getAll().awaitAsList().mapNotNull {
        val plan = try {
            json.decodeFromString<LocalFileJournalPlan>(it.plan)
        } catch (e: SerializationException) {
            logcat(LogPriority.WARN, e) { "Local file journal row ${it._id} is not one this build can read" }
            return@mapNotNull null
        }
        LocalFileJournalEntry(it._id, it.content_type.toContentType(), plan)
    }
}
