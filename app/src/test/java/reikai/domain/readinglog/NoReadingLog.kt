package reikai.domain.readinglog

import reikai.domain.library.ContentType
import kotlin.time.Instant

/** A reading log holding no session, for a test whose subject is not the log. */
object NoReadingLog : ReadingLogRepository {
    override suspend fun record(contentType: ContentType, chapterId: Long, endedAt: Instant, duration: Long) = Unit

    override suspend fun sessionsByUrl(contentType: ContentType, entryIds: List<Long>) =
        emptyMap<Long, List<UrlReadingSession>>()

    override suspend fun restore(contentType: ContentType, entryId: Long, sessions: List<UrlReadingSession>) = Unit

    override suspend fun move(contentType: ContentType, targetBySourceChapter: Map<Long, Long>) = Unit

    override suspend fun sessions(contentType: ContentType) = emptyList<EntryReadingSession>()

    override suspend fun readDurationByEntry(contentType: ContentType) = emptyMap<Long, Long>()
}
