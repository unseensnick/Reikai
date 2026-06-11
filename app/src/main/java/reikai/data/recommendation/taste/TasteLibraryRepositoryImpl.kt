package reikai.data.recommendation.taste

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import reikai.domain.recommendation.taste.TasteLibraryRepository
import reikai.domain.recommendation.taste.TrackStatus
import reikai.domain.recommendation.taste.TrackedEntry
import tachiyomi.data.Database

class TasteLibraryRepositoryImpl(
    private val database: Database,
) : TasteLibraryRepository {

    override suspend fun getAll(): List<TrackedEntry> =
        database.taste_libraryQueries.getAll(::mapEntry).awaitAsList()

    override suspend fun replaceTracker(trackerId: Long, entries: List<TrackedEntry>, fetchedAt: Long) {
        database.transaction {
            database.taste_libraryQueries.deleteByTracker(trackerId)
            for (entry in entries) {
                database.taste_libraryQueries.insert(
                    trackerId = entry.trackerId,
                    remoteId = entry.remoteId,
                    title = entry.title,
                    score = entry.score,
                    status = entry.status.name,
                    tags = entry.tags.joinToString(TAG_SEPARATOR),
                    malId = entry.malId,
                    anilistId = entry.anilistId,
                    fetchedAt = fetchedAt,
                )
            }
        }
    }

    override suspend fun deleteAll() {
        database.taste_libraryQueries.deleteAll()
    }

    override suspend fun count(): Long =
        database.taste_libraryQueries.count().awaitAsOne()

    override suspend fun lastFetch(trackerId: Long): Long? =
        database.taste_libraryQueries.lastFetchByTracker(trackerId).awaitAsOne().last_fetch

    private fun mapEntry(
        trackerId: Long,
        remoteId: Long,
        title: String,
        score: Double,
        status: String,
        tags: String,
        malId: Long?,
        anilistId: Long?,
    ): TrackedEntry = TrackedEntry(
        trackerId = trackerId,
        remoteId = remoteId,
        title = title,
        score = score,
        status = runCatching { TrackStatus.valueOf(status) }.getOrDefault(TrackStatus.UNKNOWN),
        tags = tags.split(TAG_SEPARATOR).filter { it.isNotBlank() },
        malId = malId,
        anilistId = anilistId,
    )

    companion object {
        // Tags are arbitrary genre strings (may contain commas/spaces) but never newlines, so a
        // newline join round-trips losslessly in the single tags column.
        private const val TAG_SEPARATOR = "\n"
    }
}
