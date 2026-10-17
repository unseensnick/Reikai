package reikai.data.readinglog

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import reikai.domain.library.ContentType
import reikai.domain.readinglog.EntryReadingSession
import reikai.domain.readinglog.ReadingLogRepository
import reikai.domain.readinglog.UrlReadingSession
import reikai.domain.readinglog.isLoggableSession
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.Database
import kotlin.time.Instant

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReadingLogRepositoryImpl(
    private val database: Database,
) : ReadingLogRepository {

    private val queries = database.reading_sessionQueries

    override suspend fun record(contentType: ContentType, chapterId: Long, endedAt: Instant, duration: Long) {
        // A reader opened and closed in the same instant spent no time, and history adds nothing for it either.
        if (!isLoggableSession(endedAt, duration)) return
        val isManga = isManga(contentType)
        try {
            if (isManga) {
                queries.insertManga(chapterId, endedAt.toEpochMilliseconds(), duration)
            } else {
                queries.insertNovel(chapterId, endedAt.toEpochMilliseconds(), duration)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to record a reading session for chapterId=$chapterId" }
        }
    }

    override suspend fun sessionsByUrl(contentType: ContentType, entryId: Long): List<UrlReadingSession> {
        val mapper = { url: String, endedAt: Long, duration: Long ->
            UrlReadingSession(url, Instant.fromEpochMilliseconds(endedAt), duration)
        }
        return if (isManga(contentType)) {
            queries.sessionsByUrlOfManga(entryId, mapper).awaitAsList()
        } else {
            queries.sessionsByUrlOfNovel(entryId, mapper).awaitAsList()
        }
    }

    override suspend fun restore(contentType: ContentType, entryId: Long, sessions: List<UrlReadingSession>) {
        val loggable = sessions.filter { isLoggableSession(it.endedAt, it.duration) }
        if (loggable.isEmpty()) return
        val isManga = isManga(contentType)
        database.transaction {
            loggable.forEach {
                val endedAt = it.endedAt.toEpochMilliseconds()
                if (isManga) {
                    queries.restoreManga(endedAt, it.duration, entryId, it.chapterUrl)
                } else {
                    queries.restoreNovel(endedAt, it.duration, entryId, it.chapterUrl)
                }
            }
        }
    }

    override suspend fun move(contentType: ContentType, targetBySourceChapter: Map<Long, Long>) {
        if (targetBySourceChapter.isEmpty()) return
        val isManga = isManga(contentType)
        database.transaction {
            targetBySourceChapter.forEach { (source, target) ->
                if (source == target) return@forEach
                // A grouped statement runs only when awaited, unlike a single one.
                if (isManga) queries.moveManga(target, source).await() else queries.moveNovel(target, source).await()
            }
        }
    }

    override suspend fun sessions(contentType: ContentType): List<EntryReadingSession> {
        val mapper = { entryId: Long, title: String, endedAt: Long, duration: Long ->
            EntryReadingSession(entryId, title, Instant.fromEpochMilliseconds(endedAt), duration)
        }
        return if (isManga(contentType)) {
            queries.sessionsOfManga(mapper).awaitAsList()
        } else {
            queries.sessionsOfNovel(mapper).awaitAsList()
        }
    }

    override suspend fun readDurationByEntry(contentType: ContentType): Map<Long, Long> {
        val mapper = { entryId: Long, duration: Long -> entryId to duration }
        return if (isManga(contentType)) {
            queries.readDurationByManga(mapper).awaitAsList().toMap()
        } else {
            queries.readDurationByNovel(mapper).awaitAsList().toMap()
        }
    }

    private fun isManga(contentType: ContentType): Boolean {
        check(contentType != ContentType.ALL) { "ContentType.ALL has no reading log" }
        return contentType == ContentType.MANGA
    }
}
