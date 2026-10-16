package reikai.domain.readinglog

import reikai.domain.library.ContentType
import kotlin.time.Instant

/** A reading log holding no session, for a test whose subject is not the log. */
object NoReadingLog : ReadingLogRepository {
    override suspend fun record(contentType: ContentType, chapterId: Long, endedAt: Instant, duration: Long) = Unit

    override suspend fun sessionsByUrl(contentType: ContentType, entryId: Long) = emptyList<UrlReadingSession>()

    override suspend fun restore(contentType: ContentType, entryId: Long, sessions: List<UrlReadingSession>) = Unit

    override suspend fun copy(contentType: ContentType, sourceByTargetChapter: Map<Long, Long>) = Unit

    override suspend fun sessions(contentType: ContentType) = emptyList<EntryReadingSession>()

    override suspend fun readDurationByEntry(contentType: ContentType) = emptyMap<Long, Long>()
}
