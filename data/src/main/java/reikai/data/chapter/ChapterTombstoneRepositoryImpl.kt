package reikai.data.chapter

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import reikai.domain.chapter.ChapterTombstoneRepository
import reikai.domain.chapter.Revival
import reikai.domain.chapter.SyncedChapter
import reikai.domain.library.ContentType
import tachiyomi.data.Database
import kotlin.time.Clock

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ChapterTombstoneRepositoryImpl(
    private val database: Database,
) : ChapterTombstoneRepository {

    private val queries = database.chapter_tombstoneQueries

    override suspend fun byOwner(type: ContentType, ownerId: Long): List<SyncedChapter> {
        val row = { url: String, number: Double -> SyncedChapter(-1L, url, number) }
        return when (type) {
            ContentType.NOVELS -> queries.novelByOwner(ownerId, row)
            else -> queries.mangaByOwner(ownerId, row)
        }.awaitAsList()
    }

    override suspend fun <T> capturing(
        type: ContentType,
        ownerId: Long,
        removedIds: List<Long>,
        revivals: List<Revival>,
        write: suspend () -> T,
    ): T = database.transactionWithResult {
        if (removedIds.isNotEmpty()) {
            val now = Clock.System.now().toEpochMilliseconds()
            when (type) {
                ContentType.NOVELS -> queries.captureNovel(now, removedIds)
                else -> queries.captureManga(now, removedIds)
            }
        }
        val result = write()
        revivals.forEach { revive(type, ownerId, it) }
        result
    }

    private suspend fun revive(type: ContentType, ownerId: Long, revival: Revival) {
        val (url, tombstoneUrl) = revival
        val sessions = database.reading_sessionQueries
        if (type == ContentType.NOVELS) {
            queries.reviveNovel(ownerId, tombstoneUrl, url)
            queries.reviveNovelHistory(url, ownerId, tombstoneUrl)
            queries.reviveNovelWords(url, ownerId, tombstoneUrl)
            sessionsOf(queries.novelSessions(ownerId, tombstoneUrl).awaitAsOneOrNull()).forEach { (endedAt, duration) ->
                sessions.restoreNovel(endedAt, duration, ownerId, url)
            }
            queries.consumeNovel(ownerId, tombstoneUrl)
        } else {
            queries.reviveManga(ownerId, tombstoneUrl, url)
            queries.reviveMangaHistory(url, ownerId, tombstoneUrl)
            sessionsOf(queries.mangaSessions(ownerId, tombstoneUrl).awaitAsOneOrNull()).forEach { (endedAt, duration) ->
                sessions.restoreManga(endedAt, duration, ownerId, url)
            }
            queries.consumeManga(ownerId, tombstoneUrl)
        }
    }

    /** The [ended_at, duration] pairs a tombstone's sessions column holds. */
    private fun sessionsOf(json: String?): List<Pair<Long, Long>> = json?.let { Json.parseToJsonElement(it) }
        ?.jsonArray.orEmpty()
        .map { pair -> pair.jsonArray.let { it[0].jsonPrimitive.long to it[1].jsonPrimitive.long } }
}
